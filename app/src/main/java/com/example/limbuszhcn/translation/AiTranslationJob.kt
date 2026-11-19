package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.TranslationJsonPairer
import com.example.limbuszhcn.container.TranslationSnapshot
import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.storage.AtomicFiles
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class AiProgress(val completed: Int = 0, val limit: Int = 0, val reused: Int = 0,
    val inputTokens: Long = 0, val outputTokens: Long = 0, val message: String = "",
    val scannedFiles: Int = 0, val totalFiles: Int = 0)
internal data class AiJobResult(val archive: Path, val identity: String, val progress: AiProgress, val remaining: Int,
    val carried: Int = 0)

/** Batch translation stays outside game processes. Validated responses survive pauses until explicit task cancellation. */
internal class AiTranslationJob(private val cache: Path, private val storage: GameStorage,
    private val config: AiConfig, private val client: AiTranslationClient,
    private val cancelled: () -> Boolean = { false }, private val progress: (AiProgress) -> Unit = {}) {
    fun run(baseId: String, base: PreparedTranslation?, identityOverride: String? = null,
        reuse: Map<String, String> = emptyMap(), reuseOnly: Boolean = false,
        reference: PreparedTranslation? = base, session: AiTaskStore? = null): AiJobResult {
        val c = config.validated()
        val inspection = TranslationInspection(storage, base.takeIf { c.missingOnly }, cancelled)
        val pendingTask = session?.read()
        pendingTask?.binding?.language?.let { language ->
            require(language == inspection.language.code) {
                "游戏语言已切换，请切回${com.example.limbuszhcn.gamefs.GameTextLanguage.fromCode(language)?.displayName ?: "原语言"}后继续此任务，或取消后重新检查。"
            }
        }
        val resources = inspection.resources
        require(resources.isNotEmpty()) { "尚无可翻译的游戏原文，请先进入游戏完成资源下载，再回来生成 AI 汉化" }
        val identity = identityOverride ?: digest("v2|$baseId|${c.endpoint}|${c.model}|${c.missingOnly}|${digest(c.prompt)}").take(32)
        require(identity.matches(Regex("[a-f0-9]{32}")))
        val root = (session?.work ?: cache).resolve("ai-jobs/$identity"); Files.createDirectories(root)
        val output = root.resolve("output")
        if (Files.exists(output)) AtomicFiles.deleteOwnedTree(root, output)
        Files.createDirectories(output)
        val originals = base?.manifest?.entries?.associateBy { inspection.language.resourceKey(it.targetPath) }.orEmpty()
        val terminology = AiTerminology.build(storage, reference, cancelled)
        val contextReference = reference?.takeUnless { it === base }?.let { TranslationInspection(storage, it, cancelled) }
        val shared = if (c.missingOnly) inspection.sharedTranslations() else emptyMap()
        fun checkCancelled() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("翻译已暂停，已完成内容会在下次继续时复用") }
        var snapshot = pendingTask
        val upstream = snapshot?.upstream == true
        // A completed entry is carried outside the new batch. For upstream sync only a genuine
        // gap can be carried, even when the new official wording happens to equal our previous AI.
        fun carriedText(field: TranslationField): String? {
            val masked = reuse[field.id] ?: return null
            if (!field.canTranslate || c.missingOnly && !(upstream && AiTextSelection.missing(field, shared))) return null
            return runCatching { ProtectedTranslation(field.text).restore(masked).also {
                if (upstream) AiTerminology.validate(it, terminology.forText(field.text, field.context))
            } }.getOrNull()
        }
        fun candidate(field: TranslationField) = AiTextSelection.includes(field, c, shared) && carriedText(field) == null
        fun emit(value: AiProgress) {
            snapshot = snapshot?.let { session!!.write(it.copy(progress = value)) }
            progress(value)
        }
        val previousInput = snapshot?.progress?.inputTokens ?: 0L
        val previousOutput = snapshot?.progress?.outputTokens ?: 0L
        val plan = if (session == null) null else snapshot?.plan ?: run {
            val selected = linkedMapOf<String, Set<String>>(); val hashes = linkedMapOf<String, String>()
            var available = 0; var chosen = 0
            resources.forEachIndexed { index, key ->
                checkCancelled()
                val resource = inspection.readable(key) ?: return@forEachIndexed
                val candidates = resource.fields.filter(::candidate)
                available += candidates.size
                val ids = candidates.take((c.limit - chosen).coerceAtLeast(0)).map { it.id }.toSet()
                if (ids.isNotEmpty()) { selected[key] = ids; hashes[key] = digest(resource.raw); chosen += ids.size }
                if (index % 50 == 0) emit(AiProgress(message = "正在核对所选范围", scannedFiles = index + 1, totalFiles = resources.size))
            }
            AiTaskPlan(selected, hashes, available).also { frozen ->
                snapshot = session.write(requireNotNull(snapshot).copy(plan = frozen,
                    progress = AiProgress(limit = frozen.total, message = "本次翻译 ${frozen.total} 条")))
            }
        }
        require(plan == null || resources.containsAll(plan.selected.keys)) { "本次任务的游戏原文已变化，请取消任务后重新检查范围" }
        if (plan != null && plan.total > 0) requireNotNull(snapshot).writableOutput(TranslationSources(cache))
        // Validate every selected resource before any paid request, including a changed file later in the list.
        plan?.hashes?.forEach { (key, hash) ->
            checkCancelled()
            val current = inspection.readable(key)
            require(current != null && digest(current.raw) == hash) { "本次任务的游戏原文已变化，请取消任务后重新检查范围" }
            val eligible = current.fields.filter(::candidate).map { it.id }.toSet()
            require(eligible.containsAll(plan.selected.getValue(key))) { "本次任务的翻译范围已变化，请取消任务后重新检查" }
        }
        if (c.missingOnly && base != null) for ((key, entry) in originals) {
            val target = output.resolve(key); Files.createDirectories(target.parent)
            Files.copy(base.directory.resolve(entry.cachePath), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        var done = 0; var reused = 0; var carried = 0; var remaining = 0; var input = previousInput; var tokens = previousOutput; var totalBytes = 0L
        val progressLimit = plan?.total ?: c.limit
        for (key in resources) {
            checkCancelled()
            val resource = inspection.readable(key)
            if (resource == null) {
                require(plan?.selected?.containsKey(key) != true) { "所选文本已无法读取，请重新检查" }
                continue
            }
            totalBytes += resource.bytes; require(totalBytes <= 128L * 1024 * 1024) { "本次游戏文本总量超出限制" }
            val raw = resource.raw
            require(plan?.hashes?.get(key)?.let { it == digest(raw) } != false) { "本次任务的游戏原文已变化，请取消任务后重新检查范围" }
            val original = resource.original; val document = resource.document; val fields = resource.fields
            val referenceFields = if (reference == null) emptyList() else if (contextReference == null) fields
                else contextReference.readable(key)?.fields.orEmpty()
            val referencePositions = referenceFields.withIndex().associate { it.value.id to it.index }
            val examples = referenceFields.withIndex().filter { (_, item) -> item.covered && item.canTranslate &&
                !item.needsReview && item.text.length <= 180 && item.translated.orEmpty().length <= 250 }
            val carriedInFile = mutableMapOf<String, String>()
            val leaves = fields.filter { leaf ->
                val kept = carriedText(leaf)
                if (kept != null) {
                    leaf.apply(kept); carriedInFile[leaf.id] = reuse.getValue(leaf.id); carried++
                    return@filter false
                }
                // Retain provenance for an unchanged AI base in case a paused job must save a separate copy.
                if (!upstream && !reuseOnly && snapshot?.binding?.selected?.startsWith("ai-") == true && c.missingOnly && leaf.covered) {
                    reuse[leaf.id]?.let { masked -> if (runCatching { ProtectedTranslation(leaf.text).restore(masked) }.getOrNull() == leaf.translated)
                        carriedInFile[leaf.id] = masked }
                }
                val selected = AiTextSelection.includes(leaf, c, shared)
                if (!leaf.covered) {
                    val publicText = shared[leaf.text]
                    if (publicText != null) leaf.apply(publicText) else leaf.clear()
                }
                selected && (plan == null || leaf.id in plan.selected[key].orEmpty())
            }
            require(plan == null || leaves.map { it.id }.toSet() == plan.selected[key].orEmpty()) {
                "本次任务的翻译范围已变化，请取消任务后重新检查"
            }
            // Coverage is recomputed above. A new base release must not rebill unchanged missing text.
            val cacheKey = digest("${c.endpoint}|${c.model}|$key|${digest(raw)}|${c.missingOnly}|${digest(c.prompt)}")
            val savedFile = root.resolve("cache/$cacheKey.json")
            val saved = if (Files.isRegularFile(savedFile) && Files.size(savedFile) <= 8 * 1024 * 1024)
                runCatching { JSONObject(String(Files.readAllBytes(savedFile), Charsets.UTF_8)) }.getOrDefault(JSONObject()) else JSONObject()
            val pending = mutableListOf<TranslationField>()
            val applied = hashSetOf<String>()
            var reusedChanged = carriedInFile.isNotEmpty()
            carriedInFile.forEach { (id, value) -> saved.put(id, value) }
            fun responseKey(leaf: TranslationField): String {
                val prior = leaf.translated ?: shared[leaf.text]?.takeIf { c.includeReview }
                return if (prior != null) leaf.id + "-" + digest(prior).take(16) else leaf.id
            }
            for (leaf in leaves) {
                val savedId = responseKey(leaf)
                val review = c.missingOnly && c.includeReview && AiTextSelection.review(leaf, shared)
                val old = if (review || upstream) saved.optString(savedId) else reuse[leaf.id] ?: saved.optString(savedId)
                if (old.isNotBlank()) {
                    val validated = runCatching { ProtectedTranslation(leaf.text).restore(old).also {
                        AiTerminology.validate(it, terminology.forText(leaf.text, leaf.context))
                    } }.getOrNull()
                    if (validated != null) {
                        leaf.apply(validated); applied += leaf.id; reused++; saved.put(savedId, old); reusedChanged = true; continue
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
                emit(AiProgress(done, progressLimit, reused, input, tokens, "正在翻译 · 已完成 ${done + reused}/$progressLimit 条"))
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
                    applied += leaf.id
                    // Store the protected form so reading cache applies the same validator.
                    val savedId = responseKey(leaf)
                    saved.put(savedId, protectTranslated(leaf.text, value))
                }
                TranslationSources.writeAtomic(savedFile, saved.toString())
                done += batch.size; input += result.inputTokens; tokens += result.outputTokens
                emit(AiProgress(done, progressLimit, reused, input, tokens, "已完成 ${done + reused}/$progressLimit 条"))
            }
            remaining += pending.size - cursor
            val target = output.resolve(key); Files.createDirectories(target.parent)
            val generated = TranslationSnapshot.encode(raw, document)
            // Validate with the actual reader before publishing; a successful API response is not proof of loadability.
            if (applied.isNotEmpty() || carriedInFile.isNotEmpty()) {
                val verified = TranslationJsonPairer.mergeWithCoverage("Localize/$key", raw, generated)
                val loaded = TranslationFields.collect(original, verified.document, key, verified).filter { it.covered }.map { it.id }.toSet()
                check(loaded.containsAll(applied) && loaded.containsAll(carriedInFile.keys)) { "译文已保存到任务，但部分条目未通过加载检查；未替换当前汉化" }
            }
            Files.write(target, generated.toByteArray(Charsets.UTF_8))
        }
        checkCancelled()
        if (plan != null) remaining = plan.available - plan.total
        require(done + reused + carried > 0 || base != null) { "没有可翻译的显示文本" }
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
        val result = AiProgress(done, progressLimit, reused, input, tokens,
            if (reuseOnly) "已复用 $reused 条 AI 译文，另有 $remaining 条未覆盖"
            else if (done + reused == 0) "所选范围没有需要 AI 处理的文本"
            else if (remaining > 0) "本批完成，还有 $remaining 条可继续翻译" else "翻译完成，下次启动时加载")
        emit(result)
        return AiJobResult(archive, identity, result, remaining, carried)
    }

    companion object {
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun resourceKey(target: String): String = com.example.limbuszhcn.gamefs.GameTextLanguage.Japanese.resourceKey(target)
        private fun protectTranslated(original: String, translated: String): String {
            // Re-mask the original token identities, including valid Chinese word-order changes.
            val expected = ProtectedTranslation(original)
            val masked = expected.maskTranslated(translated)
            require(expected.restore(masked) == translated) { "译文缓存格式校验失败" }
            return masked
        }
    }
}
