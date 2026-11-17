package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.TranslationJsonPairer
import com.example.limbuszhcn.container.TranslationTextPolicy
import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.storage.AtomicFiles
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class AiProgress(val completed: Int = 0, val limit: Int = 0, val reused: Int = 0,
    val inputTokens: Long = 0, val outputTokens: Long = 0, val message: String = "")
internal data class AiJobResult(val archive: Path, val identity: String, val progress: AiProgress, val remaining: Int)

/** Batch translation stays outside game processes. Per-resource caches survive cancellation. */
internal class AiTranslationJob(private val cache: Path, private val storage: GameStorage,
    private val config: AiConfig, private val client: AiTranslationClient,
    private val cancelled: () -> Boolean = { false }, private val progress: (AiProgress) -> Unit = {}) {
    fun run(baseId: String, base: PreparedTranslation?, identityOverride: String? = null,
        reuse: Map<String, String> = emptyMap(), reuseOnly: Boolean = false,
        reference: PreparedTranslation? = base): AiJobResult {
        val resources = storage.japaneseResources()
        require(resources.isNotEmpty()) { "尚无可翻译的游戏原文，请先进入游戏完成资源下载，再回来生成 AI 汉化" }
        val c = config.validated()
        val identity = identityOverride ?: digest("v2|$baseId|${c.endpoint}|${c.model}|${c.missingOnly}|${digest(c.prompt)}").take(32)
        require(identity.matches(Regex("[a-f0-9]{32}")))
        val root = cache.resolve("ai-jobs/$identity"); Files.createDirectories(root)
        val output = root.resolve("output")
        if (Files.exists(output)) AtomicFiles.deleteOwnedTree(root, output)
        Files.createDirectories(output)
        val originals = base?.manifest?.entries?.associateBy { resourceKey(it.targetPath) }.orEmpty()
        val terminology = AiTerminology.build(storage, reference, cancelled)
        val contextReference = reference?.takeUnless { it === base }?.let { TranslationInspection(storage, it, cancelled) }
        val shared = if (c.missingOnly) TranslationInspection(storage, base, cancelled).sharedTranslations() else emptyMap()
        if (c.missingOnly && base != null) for ((key, entry) in originals) {
            val target = output.resolve(key); Files.createDirectories(target.parent)
            Files.copy(base.directory.resolve(entry.cachePath), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        var done = 0; var reused = 0; var remaining = 0; var input = 0L; var tokens = 0L; var totalBytes = 0L
        fun checkCancelled() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("翻译已暂停，已完成内容会在下次继续时复用") }
        for ((resourceIndex, key) in resources.withIndex()) {
            checkCancelled()
            val bytes = storage.read(storage.rootPath().trimEnd('/') + "/$key") ?: continue
            totalBytes += bytes.size; require(totalBytes <= 128L * 1024 * 1024) { "本次游戏文本总量超出限制" }
            val raw = bytes.toString(Charsets.UTF_8)
            val original = TranslationFields.parse(raw)
            val baseline = if (c.missingOnly) originals[key]?.let { entry ->
                val text = String(Files.readAllBytes(requireNotNull(base).directory.resolve(entry.cachePath)), Charsets.UTF_8)
                TranslationJsonPairer.mergeWithCoverage("Localize/$key", raw, text)
            } else null
            val document = baseline?.document ?: TranslationFields.parse(raw)
            val fields = TranslationFields.collect(original, document, key, baseline)
            val referenceFields = if (reference == null) emptyList() else contextReference?.read(key)?.fields ?: fields
            val referencePositions = referenceFields.withIndex().associate { it.value.id to it.index }
            val examples = referenceFields.withIndex().filter { (_, item) -> item.covered && item.canTranslate &&
                !item.needsReview && item.text.length <= 180 && item.translated.orEmpty().length <= 250 }
            val leaves = fields.filter { leaf ->
                if (!leaf.covered) {
                    val publicText = shared[leaf.text]
                    if (publicText != null) leaf.apply(publicText) else leaf.clear()
                    leaf.canTranslate && (!c.missingOnly ||
                        (publicText == null && c.includeMissing) ||
                        (publicText != null && c.includeReview && TranslationFields.hasResidualLanguage(publicText)))
                } else leaf.canTranslate && c.includeReview && leaf.needsReview
            }
            // Coverage is recomputed above. A new base release must not rebill unchanged missing text.
            val cacheKey = digest("${c.endpoint}|${c.model}|$key|${digest(raw)}|${c.missingOnly}|${digest(c.prompt)}")
            val savedFile = root.resolve("cache/$cacheKey.json")
            val saved = if (Files.isRegularFile(savedFile) && Files.size(savedFile) <= 8 * 1024 * 1024)
                runCatching { JSONObject(String(Files.readAllBytes(savedFile), Charsets.UTF_8)) }.getOrDefault(JSONObject()) else JSONObject()
            val pending = mutableListOf<TranslationField>()
            var reusedChanged = false
            for (leaf in leaves) {
                val savedId = if (leaf.covered) leaf.id + "-" + digest(leaf.translated.orEmpty()).take(16) else leaf.id
                val old = reuse[leaf.id] ?: saved.optString(savedId)
                if (old.isNotBlank()) {
                    val validated = runCatching { ProtectedTranslation(leaf.text).restore(old).also {
                        AiTerminology.validate(it, terminology.forText(leaf.text, leaf.context))
                    } }.getOrNull()
                    if (validated != null) {
                        leaf.apply(validated); reused++; saved.put(savedId, old); reusedChanged = true; continue
                    }
                }
                pending += leaf
            }
            if (reusedChanged) TranslationSources.writeAtomic(savedFile, saved.toString())
            var cursor = 0
            while (!reuseOnly && cursor < pending.size && done < c.limit) {
                checkCancelled()
                val batch = mutableListOf<TranslationField>(); var characters = 0
                while (cursor < pending.size && batch.size < 8 && batch.size < c.limit - done) {
                    val leaf = pending[cursor]
                    if (characters + leaf.text.length > 6000 && batch.isNotEmpty()) break
                    batch += leaf; characters += leaf.text.length; cursor++
                }
                progress(AiProgress(done, c.limit, reused, input, tokens, "正在翻译 · ${resourceIndex + 1}/${resources.size} 个文本文件"))
                val result = client.translate(batch.map { leaf ->
                    val position = referencePositions[leaf.id]
                    val neighbors = if (position == null) emptyList() else examples.filter { it.index != position }
                        .sortedBy { kotlin.math.abs(it.index - position) }.take(3).sortedBy { it.index }
                        .map { it.value.text to it.value.translated.orEmpty() }
                    AiText(leaf.id, leaf.text, leaf.context, terminology.forText(leaf.text, leaf.context), neighbors)
                })
                for (leaf in batch) {
                    val value = result.translations.getValue(leaf.id)
                    leaf.apply(value)
                    // Store the protected form so reading cache applies the same validator.
                    val savedId = if (leaf.covered) leaf.id + "-" + digest(leaf.translated.orEmpty()).take(16) else leaf.id
                    saved.put(savedId, protectTranslated(leaf.text, value))
                }
                TranslationSources.writeAtomic(savedFile, saved.toString())
                done += batch.size; input += result.inputTokens; tokens += result.outputTokens
                progress(AiProgress(done, c.limit, reused, input, tokens, "已翻译 $done 条 · 已复用 $reused 条"))
            }
            remaining += pending.size - cursor
            val target = output.resolve(key); Files.createDirectories(target.parent)
            Files.write(target, document.toString().toByteArray(Charsets.UTF_8))
        }
        checkCancelled()
        require(done + reused > 0 || base != null) { "没有可翻译的显示文本" }
        val archive = root.resolve("generated.zip")
        val pendingArchive = root.resolve("generated.zip.tmp")
        try {
            ZipOutputStream(Files.newOutputStream(pendingArchive)).use { zip ->
                Files.walk(output).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                    checkCancelled()
                    val path = output.relativize(file).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                    Files.newInputStream(file).use { it.copyTo(zip) }; zip.closeEntry()
                } }
            }
            AtomicFiles.move(pendingArchive, archive, true)
        } finally { Files.deleteIfExists(pendingArchive) }
        val result = AiProgress(done, c.limit, reused, input, tokens,
            if (reuseOnly) "已复用 $reused 条 AI 译文，另有 $remaining 条未覆盖"
            else if (done + reused == 0) "所选范围没有需要 AI 处理的文本"
            else if (remaining > 0) "本批完成，还有 $remaining 条可继续翻译" else "翻译完成，下次启动时加载")
        progress(result)
        return AiJobResult(archive, identity, result, remaining)
    }

    companion object {
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun resourceKey(target: String): String {
            val relative = target.removePrefix("en/")
            return "jp/" + relative.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } +
                "JP_" + relative.substringAfterLast('/').removePrefix("EN_").removePrefix("JP_")
        }
        private fun protectTranslated(original: String, translated: String): String {
            // Re-mask the original token identities, including valid Chinese word-order changes.
            val expected = ProtectedTranslation(original)
            val masked = expected.maskTranslated(translated)
            require(expected.restore(masked) == translated) { "译文缓存格式校验失败" }
            return masked
        }
    }
}
