package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.TranslationJsonPairer
import com.example.limbuszhcn.container.TranslationSnapshot
import com.example.limbuszhcn.gamefs.GameStorage
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Reuse only committed AI text with an exact original-field fingerprint, never arbitrary old output. */
internal object AiTranslationReuse {
    fun collect(cache: Path, source: TranslationSource, previous: PreparedTranslation, storage: GameStorage,
        cancelled: () -> Boolean = { false }): Map<String, String> {
        if (source.kind != "ai" || !source.id.matches(Regex("ai-[a-f0-9]{32}"))) return emptyMap()
        val folder = cache.resolve("ai-jobs/${source.id.removePrefix("ai-")}/cache")
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
        fun checkCancelled() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("已取消本次启动")
        }
        val saved = mutableMapOf<String, MutableSet<String>>()
        var bytes = 0L; var count = 0
        Files.list(folder).use { paths -> paths.forEach { file ->
            checkCancelled()
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
                !file.fileName.toString().matches(Regex("[a-f0-9]{64}\\.json"))) return@forEach
            val size = Files.size(file)
            require(++count <= 8192 && size <= 8 * 1024 * 1024 && bytes + size <= 128L * 1024 * 1024) { "AI 缓存超出检查范围，请先管理已有汉化" }
            bytes += size
            val json = runCatching { JSONObject(String(Files.readAllBytes(file), Charsets.UTF_8)) }.getOrNull() ?: return@forEach
            json.keys().forEach { key ->
                if (key.matches(Regex("[a-f0-9]{64}(?:-[a-f0-9]{16})?"))) {
                    val text = json.opt(key) as? String
                    if (text != null && text.length in 1..24000) saved.getOrPut(key.take(64)) { mutableSetOf() }.add(text)
                }
            }
        } }
        val entries = previous.manifest.entries.associateBy { AiTranslationJob.resourceKey(it.targetPath) }
        val reusable = mutableMapOf<String, String>()
        var readBytes = 0L
        for (key in storage.japaneseResources()) {
            checkCancelled()
            val entry = entries[key] ?: continue
            val raw = storage.read(storage.rootPath().trimEnd('/') + "/$key") ?: continue
            readBytes += raw.size; require(readBytes <= 128L * 1024 * 1024) { "游戏文本超出检查范围" }
            val original = String(raw, Charsets.UTF_8)
            val old = String(Files.readAllBytes(previous.directory.resolve(entry.cachePath)), Charsets.UTF_8)
            // Legacy generated files lacked a binding. Reuse requires BOTH the committed archive's
            // exact non-display structure and a cached original-field fingerprint with the same value.
            // This temporary binding alone is never exported or used to mark coverage.
            val payload = TranslationSnapshot.decode(TranslationFields.parse(old)).document
            val candidate = if (TranslationSnapshot.matchingStructure(TranslationFields.parse(original), payload) != null)
                TranslationSnapshot.encode(original, payload) else old
            val merged = TranslationJsonPairer.mergeWithCoverage("Localize/$key", original, candidate)
            for (leaf in TranslationFields.collect(TranslationFields.parse(original), merged.document, key, merged)) {
                if (!leaf.canTranslate || !leaf.covered) continue
                val candidate = saved[leaf.id]?.firstOrNull { masked ->
                    runCatching { ProtectedTranslation(leaf.text).restore(masked) == leaf.translated }.getOrDefault(false)
                }
                if (candidate != null) reusable[leaf.id] = candidate
            }
        }
        return reusable
    }
}
