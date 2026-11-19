package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.TranslationSnapshot
import com.example.limbuszhcn.gamefs.GameStorage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Rebuild only the requested display fields. The original game files are never written. */
internal object AiEntryRemoval {
    fun remove(cache: Path, sources: TranslationSources, storage: GameStorage, request: AiEntryRequest): Boolean {
        val store = AiEntryStore(cache)
        store.validate(request.report, sources, storage, true)
        val plan = store.plan(request, 1000, deletion = true)
        val source = requireNotNull(sources.read().sources.find { it.id == request.report.sourceId && it.kind == "ai" })
        val prepared = sources.prepare(source)
        val proof = AiTranslationReuse.collect(cache, source, prepared, storage, requestedLanguage = request.report.language)
        require(proof.keys.containsAll(plan.selected.values.flatten())) { "所选条目不再是此包的 AI 译文，请重新读取" }
        val inspection = TranslationInspection(storage, prepared, requestedLanguage = request.report.language)
        val rewritten = mutableMapOf<String, ByteArray>()
        for ((key, ids) in plan.selected) {
            val r = inspection.read(key)
            require(AiTranslationJob.digest(r.raw) == plan.hashes[key]) { "原文已变化，请重新读取条目" }
            val fields = r.fields.filter { it.id in ids && it.covered }
            require(fields.size == ids.size) { "译文已变化，请重新读取条目" }
            fields.forEach { it.clear() }
            // Keep absent translations absent; otherwise untouched original text could become a fake translation.
            r.fields.filter { !it.covered }.forEach { it.clear() }
            rewritten[key] = TranslationSnapshot.encode(r.raw, r.document).toByteArray(Charsets.UTF_8)
        }
        val output = cache.resolve("ai-entries/removal.zip")
        Files.createDirectories(output.parent)
        try {
            var hasChinese = false
            ZipOutputStream(Files.newOutputStream(output)).use { zip ->
                prepared.manifest.entries.forEach { entry ->
                    val key = request.report.language.resourceKey(entry.targetPath)
                    zip.putNextEntry(ZipEntry(key).apply { time = 0 })
                    val bytes = rewritten.remove(key) ?: Files.readAllBytes(prepared.directory.resolve(entry.cachePath))
                    hasChinese = hasChinese || com.example.limbuszhcn.container.TranslationJsonPairer.releasedTexts(bytes.toString(Charsets.UTF_8))
                        .any { text -> text.any { it.code in 0x3400..0x9fff } }
                    zip.write(bytes)
                    zip.closeEntry()
                }
                check(rewritten.isEmpty()) { "所选译文不在当前归档中，未修改汉化包" }
            }
            store.validate(request.report, sources, storage, true)
            if (!hasChinese) {
                sources.remove(source.id)
                return true
            }
            sources.addGenerated(output, source.baseId ?: "global", source.id.removePrefix("ai-"), source.name, source.officialVersion,
                activate = sources.read().selectedId == source.id, independent = source.independent, language = request.report.language.code,
                aiEntryCount = request.report.aiCount - plan.total, baseName = source.baseName)
            return false
        } finally { Files.deleteIfExists(output) }
    }
}
