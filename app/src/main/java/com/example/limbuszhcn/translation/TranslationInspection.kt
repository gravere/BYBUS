package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.TranslationIndexCompiler
import com.example.limbuszhcn.container.TranslationJsonPairer
import com.example.limbuszhcn.gamefs.GameStorage
import java.nio.file.Files
import java.io.IOException
import java.util.concurrent.CancellationException
import org.json.JSONException

/** Bounded per-file reads; public-text fallback uses the production compiler, not guessed IDs. */
internal class TranslationInspection(private val storage: GameStorage, private val base: PreparedTranslation?, private val cancelled: () -> Boolean = { false }) {
    val language = requireNotNull(storage.textLanguage()) { "请先从 BYBUS 启动一次游戏，以读取当前游戏语言。" }.language
    val resources = storage.textResources(language).sorted()
    val entries = base?.manifest?.entries?.associateBy { language.resourceKey(it.targetPath) }.orEmpty()
    data class Resource(val raw: String, val original: Any, val document: Any, val fields: List<TranslationField>, val bytes: Int)
    fun read(key: String): Resource {
        checkCancelled()
        val bytes = requireNotNull(storage.read(storage.rootPath().trimEnd('/') + "/$key"))
        require(bytes.size <= 8 * 1024 * 1024) { "文本文件过大" }
        val raw = bytes.toString(Charsets.UTF_8)
        val original = TranslationFields.parse(raw)
        val baseline = entries[key]?.let {
            val file = requireNotNull(base).directory.resolve(it.cachePath)
            require(Files.size(file) <= 8 * 1024 * 1024)
            TranslationJsonPairer.mergeWithCoverage("Localize/$key", raw, String(Files.readAllBytes(file), Charsets.UTF_8))
        }
        val document = baseline?.document ?: TranslationFields.parse(raw)
        return Resource(raw, original, document, TranslationFields.collect(original, document, key, baseline), bytes.size)
    }
    fun readable(key: String): Resource? = readableResource { read(key) }
    fun sharedTranslations(): Map<String, String> {
        val prepared = base ?: return emptyMap()
        val wanted = hashSetOf<String>(); var bytes = 0L
        resources.forEach { key ->
            checkCancelled()
            readable(key)?.let { r ->
                bytes += r.raw.length * 2L
                r.fields.filter { !it.covered && it.excludedReason == null }.forEach { wanted += it.text }
            }
            require(bytes <= 256L * 1024 * 1024) { "本地文本总量超出检查范围" }
        }
        if (wanted.isEmpty()) return emptyMap()
        val compiler = TranslationIndexCompiler()
        resources.filter { it in entries }.forEach { key ->
            checkCancelled()
            readableResource {
                val bytes = requireNotNull(storage.read(storage.rootPath().trimEnd('/') + "/$key"))
                require(bytes.size <= 8 * 1024 * 1024)
                val raw = bytes.toString(Charsets.UTF_8)
                val file = prepared.directory.resolve(entries.getValue(key).cachePath)
                require(Files.size(file) <= 8 * 1024 * 1024)
                TranslationJsonPairer.pair("Localize/$key", raw, String(Files.readAllBytes(file), Charsets.UTF_8)).records
                    .filter { it.source in wanted }.forEach(compiler::add)
            }
        }
        return compiler.compile("coverage", "0".repeat(64)).uniqueEntries.filterKeys { it in wanted }
    }
    private fun checkCancelled() {
        if (cancelled() || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
    }
}

/** Skip only individual unreadable documents. Cancellation and process-wide failures must propagate. */
internal inline fun <T> readableResource(read: () -> T): T? = try { read() }
catch (e: CancellationException) { throw e }
catch (_: IOException) { null }
catch (_: JSONException) { null }
catch (_: IllegalArgumentException) { null }
catch (_: IllegalStateException) { null }
