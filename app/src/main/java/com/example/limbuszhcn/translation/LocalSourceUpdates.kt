package com.example.limbuszhcn.translation

import com.example.limbuszhcn.gamefs.GameStorage
import java.nio.file.Files

/** Each operation publishes exactly once, after archive validation and optional supplement generation. */
internal class LocalSourceUpdates(private val sources: TranslationSources) {
    fun supplemented(source: TranslationSource, official: PreparedTranslation, storage: GameStorage,
        progress: (String, Int, Int) -> Unit = { _, _, _ -> }): TranslationSource {
        require(source.kind == "local")
        val original = source.original
        val archive = sources.cache.resolve("sources/.supplement.zip")
        try {
            val added = LocalSourceSupplement.build(sources.prepare(original), official, storage, archive, progress)
            val result = Files.newInputStream(archive).use { sources.stage(it, source.name, source.id) }
            return result.copy(updateLink = source.updateLink, supplement = OfficialSupplement(original.revision,
                original.archive, official.manifest.requestedVersion, added))
        } finally { Files.deleteIfExists(archive) }
    }

    data class Result(val source: TranslationSource, val changed: Boolean)
    fun update(source: TranslationSource, downloader: LocalSourceDownload = LocalSourceDownload(),
        supplement: (TranslationSource) -> TranslationSource = { error("补齐资源尚未就绪，已保留原包") },
        progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val link = requireNotNull(source.updateLink) { "请先添加更新链接" }
        require(source.kind == "local")
        // A 304 is only useful if our existing payload is still usable.
        sources.prepare(source); sources.prepare(source.original)
        val download = sources.cache.resolve("sources/.update.download")
        try {
            val response = downloader.fetch(link, source.original.revision, download, progress)
            val raw = response.file?.let { file -> Files.newInputStream(file).use { sources.stage(it, source.name, source.id) } }
            val changed = raw != null && raw.revision != source.original.revision
            var next = if (changed) requireNotNull(raw).copy(updateLink = response.link) else source.copy(updateLink = response.link)
            if (changed && source.supplement != null) next = supplement(next)
            sources.commitLocal(next, source)
            return Result(next, changed)
        } finally { Files.deleteIfExists(download) }
    }
}
