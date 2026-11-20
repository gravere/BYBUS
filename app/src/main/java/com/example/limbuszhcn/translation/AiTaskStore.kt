package com.example.limbuszhcn.translation

import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.update.PatchManifest
import com.example.limbuszhcn.update.sha256
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal enum class AiTaskPhase { Running, Paused, Applying }
internal data class AiTaskBinding(val selected: String, val revision: String, val targetRevision: String?, val selectedKind: String = "",
    val language: String = "jp") {
    fun unchanged(state: SourceState, target: String, activeRevision: String) =
        state.selectedId == selected && activeRevision == revision && (selectedKind.isEmpty() || selectedKind == state.selected.kind) &&
            state.sources.find { it.id == target }.let { it?.revision == targetRevision && (it == null || it.kind == "ai") }
}
internal data class AiTaskPlan(val selected: Map<String, Set<String>>, val hashes: Map<String, String>, val available: Int) {
    val total = selected.values.sumOf { it.size }
}
internal data class AiTaskSnapshot(val id: String, val config: AiConfig, val baseId: String,
    val sourceName: String, val outputIdentity: String, val outputName: String, val officialVersion: Long?,
    val phase: AiTaskPhase = AiTaskPhase.Running, val progress: AiProgress = AiProgress(),
    val plan: AiTaskPlan? = null, val error: String? = null, val resultHash: String? = null,
    val binding: AiTaskBinding? = null, val upstream: Boolean = false, val resultTarget: String? = null,
    val explicitEntries: Boolean = false, val baseName: String? = null) {
    val scopeLabel get() = if (explicitEntries) "所选条目" else if (!config.missingOnly) "全部文本" else when {
        config.includeMissing && config.includeReview -> "未覆盖与待核对"
        config.includeReview -> "待核对"
        else -> "未覆盖"
    }
}

/** One private, explicitly resumed transaction. Keys are never written into this directory. */
internal class AiTaskStore(private val cache: Path) {
    val root: Path = cache.resolve("ai-task")
    val work: Path get() = root.resolve("work")
    private val marker get() = root.resolve("task.json")
    private var cachedPlanHash: String? = null
    private var cachedPlan: AiTaskPlan? = null
    private val planFile get() = root.resolve("plan.json")
    fun exists() = Files.isRegularFile(marker)

    fun read(): AiTaskSnapshot? {
        if (!exists()) return null
        require(Files.size(marker) <= 2 * 1024 * 1024) { "翻译任务记录损坏，请取消后重新生成" }
        val j = JSONObject(String(Files.readAllBytes(marker), Charsets.UTF_8))
        require(j.getInt("schema") == 1)
        val c = j.getJSONObject("config")
        val config = AiConfig(c.getString("endpoint"), c.getString("model"), c.getBoolean("missingOnly"),
            c.getInt("limit"), c.getBoolean("includeMissing"), c.getBoolean("includeReview"), c.getString("prompt")).validated()
        val p = j.getJSONObject("progress")
        val planHash = j.optString("planSha256").takeIf(String::isNotEmpty)
        val plan = if (planHash != null && planHash == cachedPlanHash && cachedPlan != null) cachedPlan else
            (if (planHash == null) j.optJSONObject("plan") else {
                require(planHash.matches(HASH) && Files.size(planFile) <= 64L * 1024 * 1024)
                val bytes = Files.readAllBytes(planFile)
                require(AiTranslationJob.digest(String(bytes, Charsets.UTF_8)) == planHash) { "翻译范围记录损坏，请重新选择" }
                JSONObject(String(bytes, Charsets.UTF_8))
            })?.let { value ->
            val rows = value.getJSONArray("resources"); require(rows.length() <= MAX_AI_TASK_ENTRIES)
            val selected = linkedMapOf<String, Set<String>>(); val hashes = linkedMapOf<String, String>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); val key = row.getString("key"); validateRelative(key)
                val ids = row.getJSONArray("ids"); require(ids.length() in 1..MAX_AI_TASK_ENTRIES)
                selected[key] = (0 until ids.length()).map { ids.getString(it).also { id -> require(id.matches(HASH)) } }.toSet()
                hashes[key] = row.getString("hash").also { require(it.matches(HASH)) }
            }
            AiTaskPlan(selected, hashes, value.getInt("available")).also {
                require(it.total <= config.limit && it.available in it.total..MAX_AI_TASK_ENTRIES)
                cachedPlan = it; cachedPlanHash = planHash
            }
        }
        return AiTaskSnapshot(j.getString("id"), config, j.getString("baseId"), j.getString("sourceName"),
            j.getString("outputIdentity"), j.getString("outputName"), j.optLong("officialVersion").takeIf { it > 0 },
            AiTaskPhase.valueOf(j.getString("phase")), AiProgress(p.getInt("completed"), p.getInt("limit"),
                p.getInt("reused"), p.getLong("inputTokens"), p.getLong("outputTokens"), p.getString("message"),
                p.optInt("scannedFiles"), p.optInt("totalFiles"), p.optLong("cachedTokens"), p.optLong("elapsedMillis"),
                p.optInt("requests"), p.optBoolean("unknownUsage"), p.optJSONObject("price")?.let(AiPrice::read)),
            plan, j.optString("error").takeIf { it.isNotBlank() }, j.optString("resultHash").takeIf { it.isNotBlank() },
            j.optJSONObject("binding")?.let { b -> AiTaskBinding(b.getString("selected"), b.getString("revision"),
                b.optString("targetRevision").takeIf(String::isNotEmpty), b.optString("selectedKind"), b.optString("language", "jp")) }, j.optBoolean("upstream"),
            j.optString("resultTarget").takeIf(String::isNotEmpty), j.optBoolean("explicitEntries"),
            j.optString("baseName").takeIf(String::isNotEmpty)).also {
            require(it.id.matches(ID) && it.outputIdentity.matches(ID))
        }
    }

    fun createForSelection(config: AiConfig, sources: TranslationSources, base: PreparedTranslation?, reference: PreparedTranslation?,
        independentInput: TranslationSource?, storage: GameStorage, cancelled: () -> Boolean = { false }, explicitPlan: AiTaskPlan? = null): AiTaskSnapshot {
        val source = sources.read().selected
        val language = requireNotNull(storage.textLanguage()).language.code
        val previous = (if (config.missingOnly) source.takeIf { it.kind == "ai" } else independentInput)
            ?.takeIf { it.language == null || it.language == language }
        val prior = previous?.let { from ->
            val pack = if (from.id == source.id && base != null) base else sources.prepare(from)
            AiTranslationReuse.collect(cache, from, pack, storage, cancelled)
        }.orEmpty()
        val target = previous?.takeIf { !config.missingOnly && (it.independent || it.id != source.id) }?.id?.removePrefix("ai-")
        return create(config, source, base, reference, identityOverride = target, committed = prior,
            language = language, explicitPlan = explicitPlan)
    }

    /** Build 109 froze a premerged archive for an upstream job. Upgrade it only when the stored
     * identity proves the active AI revision; otherwise publication will preserve the current selection. */
    fun forResume(sources: TranslationSources, storage: GameStorage, cancelled: () -> Boolean = { false }): AiTaskSnapshot {
        val task = requireNotNull(read())
        if (task.binding != null) return task
        val state = sources.read(); val selected = state.selected; val version = task.officialVersion
        val targetRevision = state.sources.find { it.id == "ai-${task.outputIdentity}" }?.revision
        val upstream = selected.kind == "ai" && version != null && task.config.missingOnly &&
            AiUpstreamUpdate.identity(selected, version, task.config) == task.outputIdentity && prepared("reference") != null
        val sameInput = !selected.isOfficial && (selected.id == "ai-${task.outputIdentity}" || selected.id == task.baseId) &&
            selected.revision == prepared("base")?.manifest?.archiveSha256
        if ((!upstream && !sameInput) || targetRevision != null && selected.id != "ai-${task.outputIdentity}") return task
        if (selected.kind == "ai") writeCommitted(AiTranslationReuse.collect(cache, selected, sources.prepare(selected), storage, cancelled))
        return write(task.copy(binding = AiTaskBinding(selected.id, selected.revision, targetRevision, selected.kind), upstream = upstream))
    }

    fun create(config: AiConfig, source: TranslationSource, base: PreparedTranslation?, reference: PreparedTranslation?,
        identityOverride: String? = null, officialVersionOverride: Long? = null,
        committed: Map<String, String> = emptyMap(), upstream: Boolean = false, language: String = "jp",
        explicitPlan: AiTaskPlan? = null): AiTaskSnapshot {
        check(!exists()) { "请先继续或取消已有翻译任务" }
        if (Files.exists(root)) AtomicFiles.deleteOwnedTree(cache, root)
        Files.createDirectories(root)
        TranslationSources.writeAtomic(root.resolve("input-source.json"), JSONObject()
            .put("id", source.id).put("name", source.name).put("kind", source.kind).put("revision", source.revision)
            .put("baseId", source.baseId).put("officialVersion", source.officialVersion).toString())
        snapshot("base", base)
        if (reference != null && (base == null || reference.manifest.archiveSha256 != base.manifest.archiveSha256)) snapshot("reference", reference)
        val preferred = identityOverride ?: source.id.takeIf { source.kind == "ai" && (source.language == null || source.language == language) && (config.missingOnly || source.independent) }?.removePrefix("ai-")
            ?: UUID.randomUUID().toString().replace("-", "")
        val state = TranslationSources(cache).read()
        val identity = TranslationSources.availableAiIdentity(preferred, state)
        val binding = AiTaskBinding(state.selectedId, CoverageStore(cache).sourceRevision(state.selected),
            state.sources.find { it.id == "ai-$identity" }?.revision, state.selected.kind, language)
        writeCommitted(committed)
        val baseId = source.baseId ?: source.id
        val baseName = if (baseId == "global") "全局 AI 翻译" else source.baseName ?: state.sources.find { it.id == baseId }?.name ?: source.name
        val label = requireNotNull(com.example.limbuszhcn.gamefs.GameTextLanguage.fromCode(language)).displayName
        val outputName = if (source.id == "ai-$identity") source.name else "${baseName.take(48)} · $label"
        return AiTaskSnapshot(UUID.randomUUID().toString().replace("-", ""), config.validated(), baseId,
            source.name, identity, outputName,
            if (!config.missingOnly) null else officialVersionOverride ?: if (source.isOfficial) base?.manifest?.requestedVersion?.toLongOrNull() else source.officialVersion,
            progress = AiProgress(message = "正在核对所选文本范围"), plan = explicitPlan, binding = binding, upstream = upstream,
            explicitEntries = explicitPlan != null, baseName = baseName).also {
                write(it)
                // Previously reviewed text is not a response to a newly requested recheck.
                if (!config.includeReview && !upstream && explicitPlan == null) adoptLegacyCache(identity)
            }
    }

    fun write(task: AiTaskSnapshot): AiTaskSnapshot {
        val c = task.config; val p = task.progress
        val planHash = task.plan?.let { plan ->
            if (cachedPlan === plan && cachedPlanHash != null && Files.isRegularFile(planFile)) cachedPlanHash else {
                require(plan.total <= c.limit && plan.available in plan.total..MAX_AI_TASK_ENTRIES)
                val raw = JSONObject().put("available", plan.available).put("resources", JSONArray().apply {
                    plan.selected.forEach { (key, ids) -> put(JSONObject().put("key", key).put("hash", plan.hashes.getValue(key))
                        .put("ids", JSONArray(ids.toList()))) }
                }).toString()
                require(raw.toByteArray(Charsets.UTF_8).size <= 64 * 1024 * 1024)
                TranslationSources.writeAtomic(planFile, raw)
                AiTranslationJob.digest(raw).also { cachedPlan = plan; cachedPlanHash = it }
            }
        }
        val j = JSONObject().put("schema", 1).put("id", task.id).put("baseId", task.baseId)
            .put("sourceName", task.sourceName).put("outputIdentity", task.outputIdentity).put("outputName", task.outputName)
            .put("officialVersion", task.officialVersion).put("phase", task.phase.name).put("error", task.error)
            .put("binding", task.binding?.let { JSONObject().put("selected", it.selected).put("revision", it.revision).put("targetRevision", it.targetRevision).put("selectedKind", it.selectedKind).put("language", it.language) })
            .put("upstream", task.upstream).put("resultTarget", task.resultTarget)
            .put("explicitEntries", task.explicitEntries).put("baseName", task.baseName)
            .put("resultHash", task.resultHash).put("config", JSONObject().put("endpoint", c.endpoint).put("model", c.model)
                .put("missingOnly", c.missingOnly).put("limit", c.limit).put("includeMissing", c.includeMissing)
                .put("includeReview", c.includeReview).put("prompt", c.prompt))
            .put("progress", JSONObject().put("completed", p.completed).put("limit", p.limit).put("reused", p.reused)
                .put("inputTokens", p.inputTokens).put("outputTokens", p.outputTokens).put("message", p.message)
                .put("scannedFiles", p.scannedFiles).put("totalFiles", p.totalFiles).put("cachedTokens", p.cachedTokens)
                .put("elapsedMillis", p.elapsedMillis).put("requests", p.requests).put("unknownUsage", p.unknownUsage).put("price", p.price?.json()))
            .put("planSha256", planHash)
        TranslationSources.writeAtomic(marker, j.toString())
        return task
    }

    /** Process recovery never sends a request. A committed source must not acquire another undo point. */
    fun recover(sources: SourceState): AiTaskSnapshot? {
        if (Files.exists(root.resolve("discard"))) { discard(); return null }
        val task = read() ?: return null
        if (task.resultHash != null && (sources.sources.any { it.id == (task.resultTarget ?: "ai-${task.outputIdentity}") && it.revision == task.resultHash } ||
                task.resultTarget == "zero" && sources.selectedId == "zero")) {
            discard(); return null
        }
        return write(task.copy(phase = AiTaskPhase.Paused, progress = task.progress.copy(message = "任务已保存，可继续或取消")))
    }

    fun hasFrozenSource() = Files.isRegularFile(root.resolve("input-source.json"))
    fun frozenSource(): TranslationSource {
        val file = root.resolve("input-source.json")
        require(Files.size(file) <= 16 * 1024)
        val value = JSONObject(String(Files.readAllBytes(file), Charsets.UTF_8))
        return TranslationSource(value.getString("id"), value.getString("name"), value.getString("kind"),
            value.getString("revision"), baseId = value.optString("baseId").takeIf(String::isNotEmpty),
            officialVersion = value.optLong("officialVersion").takeIf { it > 0 })
    }

    fun prepared(name: String): PreparedTranslation? {
        require(name == "base" || name == "reference")
        val directory = root.resolve(name); val file = directory.resolve("manifest.json")
        if (!Files.isRegularFile(file)) return null
        require(Files.size(file) <= 2 * 1024 * 1024)
        val manifest = PatchManifest.fromJson(String(Files.readAllBytes(file), Charsets.UTF_8))
        manifest.entries.forEach { entry ->
            val source = ownedFile(directory, entry.cachePath)
            require(Files.size(source) == entry.size && source.sha256() == entry.sha256) { "已保存的汉化快照损坏，请取消任务后重新生成" }
        }
        return PreparedTranslation(directory, manifest)
    }

    /** Legacy upstream tasks stored a premerged base plus a separate official reference. */
    fun inputs(task: AiTaskSnapshot): Pair<PreparedTranslation?, PreparedTranslation?> {
        val reference = prepared("reference")
        val base = if (task.upstream && reference != null) reference else prepared("base")
        return base to (reference ?: base)
    }

    private fun snapshot(name: String, source: PreparedTranslation?) {
        if (source == null) return
        val target = root.resolve(name); Files.createDirectories(target)
        var bytes = 0L
        source.manifest.entries.forEach { entry ->
            bytes += entry.size; require(entry.size in 0..8L * 1024 * 1024 && bytes <= 128L * 1024 * 1024)
            val from = ownedFile(source.directory, entry.cachePath)
            require(Files.size(from) == entry.size && from.sha256() == entry.sha256) { "汉化文件损坏，请先修复" }
            validateRelative(entry.cachePath)
            val to = target.resolve(entry.cachePath); Files.createDirectories(to.parent); Files.copy(from, to)
        }
        TranslationSources.writeAtomic(target.resolve("manifest.json"), source.manifest.toJson())
    }

    /** Only committed, validated caches become eligible for reuse by a later translation pack. */
    fun retainCommittedCache(identity: String, destination: String = identity) {
        require(identity.matches(ID) && destination.matches(ID))
        val from = work.resolve("ai-jobs/$identity/cache")
        if (!Files.isDirectory(from)) return
        val to = cache.resolve("ai-jobs/$destination/cache"); Files.createDirectories(to)
        Files.list(from).use { files -> files.forEach { file ->
            require(file.fileName.toString().matches(Regex("[a-f0-9]{64}\\.json")) && Files.size(file) <= 8 * 1024 * 1024)
            val target = to.resolve(file.fileName)
            val old = if (Files.isRegularFile(target) && Files.size(target) <= 8 * 1024 * 1024)
                runCatching { JSONObject(String(Files.readAllBytes(target), Charsets.UTF_8)) }.getOrDefault(JSONObject()) else JSONObject()
            val fresh = JSONObject(String(Files.readAllBytes(file), Charsets.UTF_8))
            fresh.keys().forEach { old.put(it, fresh.get(it)) }
            TranslationSources.writeAtomic(target, old.toString())
        } }
    }

    // Keep proof from the original source inside the transaction; replacing that source while paused
    // must not lose already accepted translations or make the next batch pay for them again.
    private fun writeCommitted(values: Map<String, String>) {
        if (values.isEmpty()) return
        val file = root.resolve("committed.jsonl")
        Files.newBufferedWriter(file, Charsets.UTF_8).use { out ->
            var bytes = 0L
            for ((id, text) in values) {
                require(id.matches(HASH) && text.length in 1..24000)
                val line = JSONArray().put(id).put(text).toString()
                bytes += line.toByteArray(Charsets.UTF_8).size + 1
                require(bytes <= 128L * 1024 * 1024) { "已保存的 AI 译文超过本地处理上限" }
                out.append(line).append('\n')
            }
        }
    }

    fun committed(): Map<String, String> {
        val file = root.resolve("committed.jsonl")
        if (!Files.exists(file)) return emptyMap()
        require(Files.size(file) <= 128L * 1024 * 1024)
        return buildMap { Files.newBufferedReader(file, Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
            val row = JSONArray(line); val id = row.getString(0); val value = row.getString(1)
            require(id.matches(HASH) && value.length in 1..24000)
            put(id, value)
        } } }
    }

    /** Old versions kept response caches without a task record. Adopt them once, still validating each selected field in the runner. */
    private fun adoptLegacyCache(identity: String) {
        require(identity.matches(ID))
        val legacy = cache.resolve("ai-jobs/$identity")
        val adopted = legacy.resolve("task-format-v1")
        if (Files.exists(adopted)) return
        val from = legacy.resolve("cache"); val to = work.resolve("ai-jobs/$identity/cache")
        if (Files.isDirectory(from)) {
            Files.createDirectories(to); var size = 0L; var count = 0
            Files.list(from).use { files -> files.forEach { file ->
                if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !file.fileName.toString().matches(Regex("[a-f0-9]{64}\\.json"))) return@forEach
                size += Files.size(file); require(++count <= 8192 && Files.size(file) <= 8 * 1024 * 1024 && size <= 128L * 1024 * 1024)
                Files.copy(file, to.resolve(file.fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } }
        }
        // Cancellation deletes the adopted copies; later tasks must not resurrect abandoned responses.
        TranslationSources.writeAtomic(adopted, "1")
    }

    fun discard() { if (Files.exists(root)) AtomicFiles.deleteOwnedTree(cache, root) }
    fun requestDiscard() { TranslationSources.writeAtomic(root.resolve("discard"), "cancelled") }

    companion object {
        fun identityFor(base: String, config: AiConfig, language: String? = null) =
            AiTranslationJob.digest("${if (language == null) "v2" else "v3|$language"}|$base|${config.endpoint}|${config.model}|${config.missingOnly}|${AiTranslationJob.digest(config.prompt)}").take(32)
        private val ID = Regex("[a-f0-9]{32}")
        private val HASH = Regex("[a-f0-9]{64}")
        private fun validateRelative(path: String) {
            require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path && path.split('/').none { it in setOf("", ".", "..") })
        }
        private fun ownedFile(root: Path, path: String): Path {
            validateRelative(path)
            return root.resolve(path).toRealPath().also { require(it.startsWith(root.toRealPath()) && Files.isRegularFile(it)) }
        }
    }
}
