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
    val scannedFiles: Int = 0, val totalFiles: Int = 0, val cachedTokens: Long = 0,
    val elapsedMillis: Long = 0, val requests: Int = 0, val unknownUsage: Boolean = false, val price: AiPrice? = null,
    val sampledAt: Long = 0, val billing: AiBillingCheckpoint? = null)
internal data class AiJobResult(val archive: Path, val identity: String, val progress: AiProgress, val remaining: Int,
    val carried: Int = 0, val aiEntryCount: Int? = null, val changes: AiChangeSummary? = null)

/** Batch translation stays outside game processes. Validated responses survive pauses until explicit task cancellation. */
internal class AiTranslationJob(private val cache: Path, private val storage: GameStorage,
    private val config: AiConfig, private val client: AiTranslationClient,
    private val cancelled: () -> Boolean = { false }, private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val progress: (AiProgress) -> Unit = {}) {
    fun run(baseId: String, base: PreparedTranslation?, identityOverride: String? = null,
        reuse: Map<String, String> = emptyMap(), reuseOnly: Boolean = false,
        reference: PreparedTranslation? = base, session: AiTaskStore? = null, price: AiPrice? = null): AiJobResult {
        val started = clock()
        val c = config.validated()
        val pendingTask = session?.read()
        val preserveBase = c.missingOnly || pendingTask?.explicitEntries == true
        val boundLanguage = pendingTask?.takeIf { it.explicitEntries }?.binding?.language?.let(com.example.limbuszhcn.gamefs.GameTextLanguage::fromCode)
        val inspection = TranslationInspection(storage, base.takeIf { preserveBase }, cancelled, boundLanguage)
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
        val contextReference = reference?.takeUnless { it === base }?.let { TranslationInspection(storage, it, cancelled, inspection.language) }
        fun checkCancelled() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("翻译已暂停，已完成内容会在下次继续时复用") }
        var snapshot = pendingTask
        val upstream = snapshot?.upstream == true
        val changes = AiChangeCollector(if (upstream) AiChangeKind.Upstream else AiChangeKind.Translation)
        val explicit = snapshot?.explicitEntries == true
        val requestedIds = if (explicit) snapshot?.plan?.selected?.values?.flatten()?.toSet().orEmpty() else emptySet()
        var shared: Map<String, String> = emptyMap()
        lateinit var terminology: AiTerminology
        fun selected(field: TranslationField) = if (explicit) field.canTranslate && field.id in requestedIds else AiTextSelection.includes(field, c, shared)
        // A completed entry is carried outside the new batch. For upstream sync only a genuine
        // gap can be carried, even when the new official wording happens to equal our previous AI.
        fun carriedText(field: TranslationField): String? {
            if (field.id in requestedIds) return null
            val masked = reuse[field.id] ?: return null
            if (!field.canTranslate || c.missingOnly && !(upstream && AiTextSelection.missing(field, shared))) return null
            return runCatching { ProtectedTranslation(field.text).restore(masked).also {
                if (upstream) AiTerminology.validate(it, terminology.forText(field.text, field.context))
            } }.getOrNull()
        }
        fun candidate(field: TranslationField) = selected(field) && carriedText(field) == null
        val previous = snapshot?.progress ?: AiProgress()
        var input = previous.inputTokens; var tokens = previous.outputTokens; var cached = previous.cachedTokens
        var requests = previous.requests; var unknown = previous.unknownUsage
        val rate = if (previous.requests > 0 || previous.billing != null) previous.price else previous.price ?: price
        fun measured(value: AiProgress): AiProgress { val now = clock(); return value.copy(inputTokens = input, outputTokens = tokens,
            cachedTokens = cached, requests = requests, unknownUsage = unknown, price = rate, billing = previous.billing,
            elapsedMillis = previous.elapsedMillis + (now - started).coerceAtLeast(0), sampledAt = now) }
        var lastPreparationAt = 0L
        fun emit(value: AiProgress) {
            val current = measured(value)
            snapshot = snapshot?.let { session!!.write(it.copy(progress = current)) }
            progress(current)
        }
        fun preparing(label: String, done: Int, total: Int) {
            val now = clock()
            if (done != 0 && done != total && now - lastPreparationAt < 200) return
            lastPreparationAt = now
            emit(previous.copy(message = "$label $done/$total", scannedFiles = done, totalFiles = total))
        }
        terminology = AiTerminology.build(storage, reference, cancelled, inspection.language) { done, total ->
            preparing("读取参考术语", done, total)
        }
        if (c.missingOnly) shared = inspection.sharedTranslations { done, total -> preparing("读取参考译文", done, total) }
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
        plan?.hashes?.entries?.forEachIndexed { index, (key, hash) ->
            checkCancelled()
            val current = inspection.readable(key)
            require(current != null && digest(current.raw) == hash) { "本次任务的游戏原文已变化，请取消任务后重新检查范围" }
            val eligible = current.fields.filter(::candidate).map { it.id }.toSet()
            require(eligible.containsAll(plan.selected.getValue(key))) { "本次任务的翻译范围已变化，请取消任务后重新检查" }
            preparing("核对所选文本", index + 1, plan.hashes.size)
        }
        if (preserveBase && base != null) for ((key, entry) in originals) {
            val target = output.resolve(key); Files.createDirectories(target.parent)
            Files.copy(base.directory.resolve(entry.cachePath), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        var done = 0; var reused = 0; var carried = 0; var remaining = 0; var totalBytes = 0L
        val aiIds = hashSetOf<String>()
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
                if (!upstream && !reuseOnly && preserveBase && leaf.covered && leaf.id !in requestedIds) {
                    reuse[leaf.id]?.let { masked -> if (runCatching { ProtectedTranslation(leaf.text).restore(masked) }.getOrNull() == leaf.translated)
                        carriedInFile[leaf.id] = masked }
                }
                val chosen = selected(leaf)
                if (!leaf.covered) {
                    val publicText = leaf.sharedTranslation(shared)
                    if (publicText != null) leaf.apply(publicText) else leaf.clear()
                }
                chosen && (plan == null || leaf.id in plan.selected[key].orEmpty())
            }
            require(plan == null || leaves.map { it.id }.toSet() == plan.selected[key].orEmpty()) {
                "本次任务的翻译范围已变化，请取消任务后重新检查"
            }
            // Coverage is recomputed above. A new base release must not rebill unchanged missing text.
            val cacheKey = aiResponseCacheKey(c, pendingTask?.cacheConnection, key, raw)
            val savedFile = root.resolve("cache/$cacheKey.json")
            val saved = readAiResponseCache(savedFile)
            val pending = mutableListOf<TranslationField>()
            val applied = hashSetOf<String>()
            var reusedChanged = carriedInFile.isNotEmpty()
            carriedInFile.forEach { (id, value) -> saved.put(id, value) }
            fun responseKey(leaf: TranslationField): String {
                val prior = leaf.translated ?: leaf.sharedTranslation(shared)?.takeIf { c.includeReview }
                return if (prior != null) leaf.id + "-" + digest(prior).take(16) else leaf.id
            }
            for (leaf in leaves) {
                val savedId = responseKey(leaf)
                val review = c.missingOnly && c.includeReview && AiTextSelection.review(leaf, shared)
                val old = if (explicit || review || upstream) saved.optString(savedId) else reuse[leaf.id] ?: saved.optString(savedId)
                if (old.isNotBlank()) {
                    val validated = runCatching { ProtectedTranslation(leaf.text).restore(old).also {
                        AiTerminology.validate(it, terminology.forText(leaf.text, leaf.context))
                    } }.getOrNull()
                    if (validated != null) {
                        changes.record(key, leaf.text, leaf.translated, validated)
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
                while (cursor < pending.size && batch.size < AI_REQUEST_ITEMS && batch.size < c.limit - done) {
                    val leaf = pending[cursor]
                    if (characters + leaf.text.length > AI_REQUEST_CHARACTERS && batch.isNotEmpty()) break
                    batch += leaf; characters += leaf.text.length; cursor++
                }
                emit(AiProgress(done, progressLimit, reused, input, tokens, "等待翻译结果 · 本批 ${batch.size} 条"))
                val result = client.translate(batch.map { leaf ->
                    val position = referencePositions[leaf.id]
                    val neighbors = if (position == null) emptyList() else examples.filter { it.index != position }
                        .sortedBy { kotlin.math.abs(it.index - position) }.take(3).sortedBy { it.index }
                        .map { it.value.text to it.value.translated.orEmpty() }
                    AiText(leaf.id, leaf.text, leaf.context, terminology.forText(leaf.text, leaf.context), neighbors)
                }, allowPartial = true, onUsage = { usage ->
                    input += usage.input; tokens += usage.output; cached += usage.cached; requests++; unknown = unknown || !usage.known
                    emit(AiProgress(done, progressLimit, reused, message = "正在校验本批译文"))
                })
                for (leaf in batch) {
                    val value = result.translations[leaf.id] ?: continue
                    changes.record(key, leaf.text, leaf.translated, value)
                    leaf.apply(value)
                    applied += leaf.id
                    // Store the protected form so reading cache applies the same validator.
                    val savedId = responseKey(leaf)
                    saved.put(savedId, result.protectedTranslations.getValue(leaf.id))
                }
                TranslationSources.writeAtomic(savedFile, saved.toString())
                done += result.translations.size
                emit(AiProgress(done, progressLimit, reused, input, tokens, "已完成 ${done + reused}/$progressLimit 条"))
                if (result.rejected.isNotEmpty()) throw AiBatchValidationException(result.rejected, result.translations.size)
            }
            remaining += pending.size - cursor
            val target = output.resolve(key); Files.createDirectories(target.parent)
            val generated = TranslationSnapshot.encode(raw, document)
            // Validate with the actual reader before publishing; a successful API response is not proof of loadability.
            if (applied.isNotEmpty() || carriedInFile.isNotEmpty()) {
                val verified = TranslationJsonPairer.mergeWithCoverage("Localize/$key", raw, generated)
                val loaded = TranslationFields.collect(original, verified.document, key, verified).filter { it.covered }.map { it.id }.toSet()
                check(loaded.containsAll(applied) && loaded.containsAll(carriedInFile.keys)) { "译文已保存到任务，但部分条目未通过加载检查；未替换当前汉化" }
                aiIds += applied; aiIds += carriedInFile.keys
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
        val result = measured(AiProgress(done, progressLimit, reused, input, tokens,
            if (reuseOnly) "已复用 $reused 条 AI 译文，另有 $remaining 条未覆盖"
            else if (done + reused == 0) "所选范围没有需要 AI 处理的文本"
            else if (remaining > 0) "本批完成，还有 $remaining 条可继续翻译" else "翻译完成，下次启动时加载"))
        emit(result)
        return AiJobResult(archive, identity, result, remaining, carried, aiIds.size, changes.snapshot())
    }

    companion object {
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun resourceKey(target: String): String = com.example.limbuszhcn.gamefs.GameTextLanguage.Japanese.resourceKey(target)
    }
}

/** An unreadable paid answer is not an empty cache. Keep it and stop rather than silently rebill it. */
internal fun readAiResponseCache(file: Path): JSONObject {
    if (!Files.exists(file)) return JSONObject()
    try {
        if (!Files.isRegularFile(file) || Files.size(file) > 8 * 1024 * 1024)
            throw AiTranslationException("已保存的译文文件超出读取范围，已停止；没有重新请求这些条目。")
        val data = parseAiResponseObject(String(Files.readAllBytes(file), Charsets.UTF_8), maxCharacters = 8 * 1024 * 1024)
        if (data.keys().asSequence().any { data.opt(it) !is String })
            throw AiTranslationException("已保存的译文记录损坏，已停止；没有重新请求这些条目。")
        return data
    } catch (_: java.io.IOException) {
        throw AiTranslationException("无法读取已保存的译文，已停止；请检查设备存储后再继续。")
    } catch (_: AiResponseFormatException) {
        throw AiTranslationException("已保存的译文记录损坏，已停止；没有重新请求这些条目。")
    }
}
