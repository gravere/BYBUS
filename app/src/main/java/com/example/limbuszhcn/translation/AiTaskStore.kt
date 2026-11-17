package com.example.limbuszhcn.translation

import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.update.PatchManifest
import com.example.limbuszhcn.update.sha256
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal enum class AiTaskPhase { Running, Paused, Applying }
internal data class AiTaskPlan(val selected: Map<String, Set<String>>, val hashes: Map<String, String>, val available: Int) {
    val total get() = selected.values.sumOf { it.size }
}
internal data class AiTaskSnapshot(val id: String, val config: AiConfig, val baseId: String,
    val sourceName: String, val outputIdentity: String, val outputName: String, val officialVersion: Long?,
    val phase: AiTaskPhase = AiTaskPhase.Running, val progress: AiProgress = AiProgress(),
    val plan: AiTaskPlan? = null, val error: String? = null, val resultHash: String? = null) {
    val scopeLabel get() = if (!config.missingOnly) "全部文本" else when {
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
        val plan = j.optJSONObject("plan")?.let { value ->
            val rows = value.getJSONArray("resources"); require(rows.length() <= 1000)
            val selected = linkedMapOf<String, Set<String>>(); val hashes = linkedMapOf<String, String>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); val key = row.getString("key"); validateRelative(key)
                val ids = row.getJSONArray("ids"); require(ids.length() in 1..1000)
                selected[key] = (0 until ids.length()).map { ids.getString(it).also { id -> require(id.matches(HASH)) } }.toSet()
                hashes[key] = row.getString("hash").also { require(it.matches(HASH)) }
            }
            AiTaskPlan(selected, hashes, value.getInt("available")).also { require(it.total <= config.limit && it.available >= it.total) }
        }
        return AiTaskSnapshot(j.getString("id"), config, j.getString("baseId"), j.getString("sourceName"),
            j.getString("outputIdentity"), j.getString("outputName"), j.optLong("officialVersion").takeIf { it > 0 },
            AiTaskPhase.valueOf(j.getString("phase")), AiProgress(p.getInt("completed"), p.getInt("limit"),
                p.getInt("reused"), p.getLong("inputTokens"), p.getLong("outputTokens"), p.getString("message"),
                p.optInt("scannedFiles"), p.optInt("totalFiles")),
            plan, j.optString("error").takeIf { it.isNotBlank() }, j.optString("resultHash").takeIf { it.isNotBlank() }).also {
            require(it.id.matches(ID) && it.outputIdentity.matches(ID))
        }
    }

    fun create(config: AiConfig, source: TranslationSource, base: PreparedTranslation?, reference: PreparedTranslation?,
        identityOverride: String? = null, officialVersionOverride: Long? = null): AiTaskSnapshot {
        check(!exists()) { "请先继续或取消已有翻译任务" }
        if (Files.exists(root)) AtomicFiles.deleteOwnedTree(cache, root)
        Files.createDirectories(root)
        snapshot("base", base)
        if (reference != null && (base == null || reference.manifest.archiveSha256 != base.manifest.archiveSha256)) snapshot("reference", reference)
        val identity = identityOverride ?: source.id.takeIf { source.kind == "ai" }?.removePrefix("ai-")
            ?: AiTranslationJob.digest("v2|${source.baseId ?: source.id}|${config.endpoint}|${config.model}|${config.missingOnly}|${AiTranslationJob.digest(config.prompt)}").take(32)
        return AiTaskSnapshot(UUID.randomUUID().toString().replace("-", ""), config.validated(), source.baseId ?: source.id,
            source.name, identity, if (!config.missingOnly) "AI 汉化" else if (source.kind == "ai")
                source.name.replace(Regex("( · AI 补全){2,}$"), " · AI 补全") else "${source.name.take(48)} · AI 补全",
            officialVersionOverride ?: if (config.missingOnly && source.isOfficial) base?.manifest?.requestedVersion?.toLongOrNull() else source.officialVersion,
            progress = AiProgress(message = "正在核对所选文本范围")).also {
                write(it)
                adoptLegacyCache(identity)
            }
    }

    fun write(task: AiTaskSnapshot): AiTaskSnapshot {
        val c = task.config; val p = task.progress
        val j = JSONObject().put("schema", 1).put("id", task.id).put("baseId", task.baseId)
            .put("sourceName", task.sourceName).put("outputIdentity", task.outputIdentity).put("outputName", task.outputName)
            .put("officialVersion", task.officialVersion).put("phase", task.phase.name).put("error", task.error)
            .put("resultHash", task.resultHash).put("config", JSONObject().put("endpoint", c.endpoint).put("model", c.model)
                .put("missingOnly", c.missingOnly).put("limit", c.limit).put("includeMissing", c.includeMissing)
                .put("includeReview", c.includeReview).put("prompt", c.prompt))
            .put("progress", JSONObject().put("completed", p.completed).put("limit", p.limit).put("reused", p.reused)
                .put("inputTokens", p.inputTokens).put("outputTokens", p.outputTokens).put("message", p.message)
                .put("scannedFiles", p.scannedFiles).put("totalFiles", p.totalFiles))
            .put("plan", task.plan?.let { plan -> JSONObject().put("available", plan.available).put("resources", JSONArray().apply {
                plan.selected.forEach { (key, ids) -> put(JSONObject().put("key", key).put("hash", plan.hashes.getValue(key))
                    .put("ids", JSONArray(ids.toList()))) }
            }) })
        TranslationSources.writeAtomic(marker, j.toString())
        return task
    }

    /** Process recovery never sends a request. A committed source must not acquire another undo point. */
    fun recover(sources: SourceState): AiTaskSnapshot? {
        if (Files.exists(root.resolve("discard"))) { discard(); return null }
        val task = read() ?: return null
        if (task.resultHash != null && sources.sources.any { it.id == "ai-${task.outputIdentity}" && it.revision == task.resultHash }) {
            discard(); return null
        }
        return write(task.copy(phase = AiTaskPhase.Paused, progress = task.progress.copy(message = "任务已保存，可继续或取消")))
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
    fun retainCommittedCache(identity: String) {
        require(identity.matches(ID))
        val from = work.resolve("ai-jobs/$identity/cache")
        if (!Files.isDirectory(from)) return
        val to = cache.resolve("ai-jobs/$identity/cache"); Files.createDirectories(to)
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
