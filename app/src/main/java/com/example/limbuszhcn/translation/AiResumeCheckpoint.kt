package com.example.limbuszhcn.translation

import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.gamefs.GameTextLanguage
import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.update.sha256
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** A reliable change token for app-owned files. Unsupported file systems always rehash. */
internal fun aiPrivateFileStamp(file: Path): String? = com.example.limbuszhcn.storage.reliableFileChangeStamp(file)

internal class AiSnapshotVerification(private val directory: Path) {
    private val marker = directory.resolve("verified-files.json")
    private val previous = readDerived(marker, 2 * 1024 * 1024) ?: JSONObject()
    private val verified = JSONObject()
    fun verify(path: String, source: Path, size: Long, sha: String) {
        val before = aiPrivateFileStamp(source)
        val proof = "$sha|$size|$before"
        if (before == null || previous.optString(path) != proof) {
            require(Files.size(source) == size && source.sha256() == sha) { "已保存的汉化快照损坏，请取消任务后重新生成" }
            require(before == aiPrivateFileStamp(source)) { "汉化快照正在变化，请稍后继续" }
        }
        if (before != null) verified.put(path, proof)
    }
    fun save() { writeDerived(marker, verified) }
}

internal data class AiPreparedContext(val terminology: AiTerminology, val shared: Map<String, String>)

/** No paid answers live here. A damaged derived cache can be rebuilt without resending translations. */
internal class AiPreparationCheckpoint(private val directory: Path, private val key: String?) {
    private val file = directory.resolve("prepared-context.json")
    fun read(): AiPreparedContext? = if (key == null) null else runCatching {
        val data = readDerived(file, 64 * 1024 * 1024) ?: return@runCatching null
        if (data.optString("key") != key) return@runCatching null
        val shared = data.getJSONObject("shared")
        require(shared.length() <= MAX_AI_TASK_ENTRIES)
        AiPreparedContext(AiTerminology.read(data.getJSONObject("terminology")),
            shared.keys().asSequence().associateWith { shared.getString(it) })
    }.getOrNull()
    fun save(context: AiPreparedContext) {
        if (key != null) writeDerived(file, JSONObject().put("key", key)
            .put("terminology", context.terminology.json()).put("shared", JSONObject(context.shared)))
    }
    companion object {
        fun fingerprint(storage: GameStorage, language: GameTextLanguage, base: PreparedTranslation?,
            reference: PreparedTranslation?, taskId: String): String? {
            val metadata = storage.textResourceMetadata(language) ?: return null
            require(metadata.size <= 5200)
            return AiTranslationJob.digest(buildString {
                append("ai-preparation-v1|$taskId|${storage.rootPath()}|${language.code}|")
                append(base?.manifest?.toJson()?.let(AiTranslationJob::digest)); append('|')
                append(reference?.manifest?.toJson()?.let(AiTranslationJob::digest))
                metadata.toSortedMap().forEach { (key, stamp) -> append('\n'); append(key); append('\u0000'); append(stamp) }
            })
        }
    }
}

internal data class AiFinishedResource(val selected: Int, val carried: Int, val aiEntries: Int,
    val bytes: Long, val remaining: Int, val changes: AiChangeSummary)

/** One durable cursor per completed file. An unfinished file resumes from its per-batch answer cache. */
internal class AiOutputCheckpoint(private val directory: Path, private val fingerprint: String?) {
    val output: Path = directory.resolve("output")
    private val marker = directory.resolve("output-progress.json")
    private val records = directory.resolve("output-records")
    init {
        val prior = if (fingerprint == null) null else readDerived(marker, 4096)
        if (prior == null || fingerprint == null || prior.optInt("schema") != 1 ||
            prior.optString("fingerprint") != fingerprint || !Files.isDirectory(output) || !Files.isDirectory(records)) {
            if (Files.exists(output)) AtomicFiles.deleteOwnedTree(directory, output)
            if (Files.exists(records)) AtomicFiles.deleteOwnedTree(directory, records)
            Files.createDirectories(output)
            Files.createDirectories(records)
            writeDerived(marker, JSONObject().put("schema", 1).put("fingerprint", fingerprint))
        }
    }
    fun completed(key: String): AiFinishedResource? = runCatching {
        if (fingerprint == null) return@runCatching null
        val row = readDerived(records.resolve(AiTranslationJob.digest(key) + ".json"), 64 * 1024) ?: return@runCatching null
        require(row.getString("key") == key)
        val target = output.resolve(key).normalize()
        require(target.startsWith(output) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
        val stamp = aiPrivateFileStamp(target)
        require(Files.size(target) == row.getLong("size"))
        if (stamp == null || stamp != row.optString("stamp")) require(target.sha256() == row.getString("sha"))
        AiFinishedResource(row.getInt("selected"), row.getInt("carried"), row.getInt("aiEntries"),
            row.getLong("bytes"), row.getInt("remaining"), AiChangeSummary.read(row.getJSONObject("changes"))).also {
            require(listOf(it.selected, it.carried, it.aiEntries, it.remaining).all { n -> n in 0..MAX_AI_TASK_ENTRIES })
            require(it.bytes in 0..8L * 1024 * 1024)
        }
    }.getOrNull()
    fun save(key: String, result: AiFinishedResource) {
        if (fingerprint == null) return
        val target = output.resolve(key)
        val row = JSONObject().put("key", key).put("size", Files.size(target)).put("sha", target.sha256()).put("stamp", aiPrivateFileStamp(target))
            .put("selected", result.selected).put("carried", result.carried).put("aiEntries", result.aiEntries)
            .put("bytes", result.bytes).put("remaining", result.remaining).put("changes", result.changes.json())
        writeDerived(records.resolve(AiTranslationJob.digest(key) + ".json"), row)
    }
}

private fun readDerived(file: Path, limit: Int): JSONObject? = runCatching {
    if (!Files.isRegularFile(file) || Files.size(file) > limit) return@runCatching null
    val envelope = JSONObject(String(Files.readAllBytes(file), Charsets.UTF_8))
    val payload = envelope.getString("payload")
    require(AiTranslationJob.digest(payload) == envelope.getString("sha256"))
    JSONObject(payload)
}.getOrNull()

private fun writeDerived(file: Path, data: JSONObject) {
    val payload = data.toString()
    require(payload.toByteArray(Charsets.UTF_8).size <= 48 * 1024 * 1024) { "任务准备记录超出限制" }
    TranslationSources.writeAtomic(file, JSONObject().put("payload", payload).put("sha256", AiTranslationJob.digest(payload)).toString())
}
