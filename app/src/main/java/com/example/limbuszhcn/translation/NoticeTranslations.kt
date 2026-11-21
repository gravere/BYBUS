package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.LiveTranslationStore
import com.example.limbuszhcn.container.TranslationJsonPairer
import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.gamefs.GameTextLanguage
import com.example.limbuszhcn.update.sha256
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Only the public notice body's exact SetData argument enters this store. No response headers or accounts. */
internal class NoticeTexts(private val cache: Path) {
    private val root = cache.resolve("notice-texts")
    fun key(language: GameTextLanguage, body: String): String =
        "${language.code}/${language.code.uppercase()}_BYBUS_NOTICE_${language.code}_${AiTranslationJob.digest(body)}.json"
    fun capture(language: GameTextLanguage, body: String) {
        val chunks = chunks(body) ?: return
        val path = root.resolve(key(language, body))
        if (Files.isRegularFile(path)) return
        TranslationSources.writeAtomic(path, document(chunks).toString())
        // Public announcements are bounded and replaceable; never touch user packages or task snapshots.
        Files.list(path.parent).use { files -> files.filter { Files.isRegularFile(it) }.sorted(
            compareByDescending<Path> { Files.getLastModifiedTime(it).toMillis() }.thenBy { it.fileName.toString() })
            .skip(200).forEach { Files.deleteIfExists(it) } }
    }
    fun resources(language: GameTextLanguage): Map<String, Path> {
        val folder = root.resolve(language.code)
        if (!Files.isDirectory(folder)) return emptyMap()
        return Files.list(folder).use { files -> files.filter { Files.isRegularFile(it) &&
            it.fileName.toString().matches(Regex("${language.code.uppercase()}_BYBUS_NOTICE_${language.code}_[a-f0-9]{64}\\.json")) }
            .limit(200).toArray().map { it as Path }.associateBy { "${language.code}/${it.fileName}" } }
    }
    fun resource(key: String): Path? {
        val language = GameTextLanguage.fromCode(key.substringBefore('/')) ?: return null
        if (key != "${language.code}/${key.substringAfterLast('/')}" || !isResource(key) ||
            !key.substringAfter('/').startsWith("${language.code.uppercase()}_BYBUS_NOTICE_${language.code}_")) return null
        return root.resolve(key).takeIf(Files::isRegularFile)
    }
    companion object {
        fun isResource(path: String) = path.substringAfterLast('/').matches(Regex("(?:JP|EN|KR)_BYBUS_NOTICE_(jp|en|kr)_[a-f0-9]{64}\\.json"))
        /** Preserve exact concatenation, paragraph boundaries and complete markup tokens. */
        fun chunks(body: String): List<String>? {
            if (body.isBlank() || body.length > 128 * 1024 || '\u0000' in body) return null
            val parts = mutableListOf<String>(); var at = 0
            while (at < body.length) {
                var end = minOf(at + 5800, body.length)
                if (end < body.length) {
                    val newline = body.lastIndexOf('\n', end - 1)
                    if (newline >= at + 128) end = newline + 1
                    else {
                        if (body[end - 1].isHighSurrogate()) end--
                        val open = body.lastIndexOf('<', end - 1); val close = body.lastIndexOf('>', end - 1)
                        if (open > close && open >= at) end = open
                    }
                }
                if (end <= at || end - at > 6000 || parts.size >= 32) return null
                parts += body.substring(at, end); at = end
            }
            return parts
        }
        fun document(parts: List<String>) = JSONObject().put("dataList", JSONArray().apply {
            parts.forEachIndexed { index, text -> put(JSONObject().put("id", index).put("content", text)) }
        })
    }
}

/** Read-only additional AI/coverage inputs. Never placed in the game's resource tree. */
internal class NoticeGameStorage(private val delegate: GameStorage, cache: Path) : GameStorage by delegate {
    private val notices = NoticeTexts(cache)
    private val captured get() = GameTextLanguage.entries.flatMap { notices.resources(it).entries }.associate { it.key to it.value }
    private fun notice(path: String): Path? = notices.resource(path.removePrefix(rootPath().trimEnd('/') + "/"))
    override fun textResources(language: GameTextLanguage) = (delegate.textResources(language) + captured.keys.filter { it.startsWith("${language.code}/") }).distinct().sorted()
    override fun read(path: String): ByteArray? = notice(path)?.let { if (Files.size(it) <= 1024 * 1024) Files.readAllBytes(it) else null } ?: delegate.read(path)
    override fun readAtMost(path: String, limit: Int): ByteArray? = notice(path)?.let { if (Files.size(it) <= limit) Files.readAllBytes(it) else null } ?: delegate.readAtMost(path, limit)
    override fun exists(path: String) = notice(path)?.let(Files::isRegularFile) ?: delegate.exists(path)
    override fun textResourceMetadata(language: GameTextLanguage): Map<String, String>? {
        val base = delegate.textResourceMetadata(language) ?: return null
        return base + captured.filterKeys { it.startsWith("${language.code}/") }.mapValues { it.value.sha256() }
    }
}

/** No network here. Each exact display chunk has its own translation and placeholder validation. */
internal class NoticeRuntime(private val cache: Path, snapshot: LiveTranslationStore.Snapshot?) : AutoCloseable {
    private val entries = snapshot?.entries?.filter { NoticeTexts.isResource(it.key) }?.associateBy { it.key }.orEmpty()
    private val notices = NoticeTexts(cache)
    private val seen = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    private val worker = ThreadPoolExecutor(0, 1, 20, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "bybus-notice").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val answers = object : LinkedHashMap<String, String?>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>) = size > 32
    }
    override fun close() { worker.shutdown(); worker.awaitTermination(2, TimeUnit.SECONDS) }
    @Synchronized fun translate(code: String, original: String): String? {
        val language = GameTextLanguage.fromCode(code) ?: return null
        val parts = NoticeTexts.chunks(original) ?: return null
        val key = notices.key(language, original)
        // Only bounded public text is queued; no paid request or disk write on the game UI thread.
        if (seen.add(key)) {
            synchronized(seen) {
                while (seen.size > 400) seen.remove(seen.first())
            }
            try { worker.execute { runCatching { notices.capture(language, original) }.onFailure { seen.remove(key) } } }
            catch (_: java.util.concurrent.RejectedExecutionException) { seen.remove(key) }
        }
        if (answers.containsKey(key)) return answers[key]
        val translated = runCatching {
            val entry = entries[GameTextLanguage.Japanese.resourceKey(key)] ?: return@runCatching null
            val file = cache.resolve(entry.path).toRealPath()
            require(file.startsWith(cache.resolve("patch-cache").toRealPath()) && Files.size(file) == entry.size && file.sha256() == entry.hash)
            val source = NoticeTexts.document(parts)
            val merged = TranslationJsonPairer.mergeWithCoverage("Localize/$key", source.toString(), String(Files.readAllBytes(file), Charsets.UTF_8))
            val fields = TranslationFields.collect(source, merged.document, key, merged)
            require(fields.isNotEmpty())
            run {
                val approved = fields.filter { field -> field.covered && !field.needsReview && runCatching {
                    ProtectedTranslation(field.text).maskTranslated(requireNotNull(field.translated))
                }.isSuccess }.associateBy { it.pointer }
                val output = parts.mapIndexed { index, originalPart ->
                    approved["/dataList/$index/content"]?.translated ?: originalPart
                }.joinToString("")
                output.takeIf { it != original && it.length <= 256 * 1024 }
            }
        }.getOrNull()
        answers[key] = translated
        return translated
    }
}
