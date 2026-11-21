package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.TranslationJsonPairer
import com.example.limbuszhcn.container.TranslationSnapshot
import com.example.limbuszhcn.gamefs.GameStorage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Match through the same original IDs and fields used by coverage and runtime. Never overwrite a local leaf. */
internal object LocalSourceSupplement {
    fun build(local: PreparedTranslation, official: PreparedTranslation, storage: GameStorage, archive: Path,
        progress: (String, Int, Int) -> Unit = { _, _, _ -> }): Int {
        val ours = TranslationInspection(storage, local)
        require(ours.resources.isNotEmpty()) { "请先在游戏内下载资源，再检查并补齐汉化" }
        val theirs = TranslationInspection(storage, official, requestedLanguage = ours.language)
        val shared = ours.sharedTranslations { done, total -> progress("核对导入包译文", done, total) }
        val resources = (ours.entries.keys + theirs.entries.keys + ours.resources).sorted()
        var added = 0
        var totalBytes = 0L
        Files.createDirectories(archive.parent)
        try {
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                resources.forEachIndexed { index, key ->
                    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                    progress("零协补齐空缺", index, resources.size)
                    val localEntry = ours.entries[key]
                    val officialEntry = theirs.entries[key]
                    val localBytes = localEntry?.let { Files.readAllBytes(local.directory.resolve(it.cachePath)) }
                    val officialBytes = officialEntry?.let { Files.readAllBytes(official.directory.resolve(it.cachePath)) }
                    val current = if (key in ours.resources) ours.readable(key) else null
                    val upstream = if (current != null && officialEntry != null) theirs.readable(key) else null
                    var bytes = localBytes ?: officialBytes
                    if (current != null && upstream != null) {
                        // If the imported pack contains newer/unreadable rows, keep the complete original file.
                        // Generating a snapshot from older game data must not silently throw those rows away.
                        val localCount = localBytes?.let { TranslationJsonPairer.releasedTexts(it.toString(Charsets.UTF_8)).size } ?: 0
                        val canRetainAll = current.fields.count { it.covered } >= localCount
                        if (canRetainAll) {
                            val values = upstream.fields.filter { it.covered && !it.needsReview && it.excludedReason == null }
                                .associateBy { it.id }
                            val applied = mutableMapOf<String, String>()
                            current.fields.forEach { field ->
                                val existing = field.sharedTranslation(shared)
                                val fresh = values[field.id]?.translated
                                when {
                                    field.covered -> Unit
                                    existing != null -> field.apply(existing)
                                    fresh != null && field.excludedReason == null -> {
                                        field.apply(fresh); applied[field.id] = fresh
                                    }
                                    else -> field.clear()
                                }
                            }
                            if (applied.isNotEmpty() || localBytes == null) {
                                val encoded = TranslationSnapshot.encode(current.raw, current.document)
                                val check = TranslationJsonPairer.mergeWithCoverage("Localize/$key", current.raw, encoded)
                                val matched = TranslationFields.collect(current.original, check.document, key, check).associateBy { it.id }
                                require(applied.all { (id, value) -> matched[id]?.let { it.covered && it.translated == value } == true }) {
                                    "补齐译文未通过匹配校验，已保留原包"
                                }
                                bytes = encoded.toByteArray(Charsets.UTF_8)
                                added += applied.size
                            }
                        }
                    }
                    if (bytes != null) {
                        totalBytes += bytes.size
                        require(totalBytes <= 128L * 1024 * 1024 && bytes.size <= 2L * 1024 * 1024) { "合并后的汉化包过大，已保留原包" }
                        val path = "en/" + requireNotNull(LocalTranslationArchive.mapResource(key))
                        zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                        zip.write(bytes); zip.closeEntry()
                    }
                }
                progress("零协补齐空缺", resources.size, resources.size)
            }
            return added
        } catch (error: Exception) { Files.deleteIfExists(archive); throw error }
    }
}
