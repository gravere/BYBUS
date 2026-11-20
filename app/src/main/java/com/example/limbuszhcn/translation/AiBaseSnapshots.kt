package com.example.limbuszhcn.translation

import com.example.limbuszhcn.update.PatchManifest
import com.example.limbuszhcn.update.TranslationRepository
import com.example.limbuszhcn.update.sha256
import java.nio.file.Files
import java.nio.file.Path

internal class AiBaseSnapshots(private val cache: Path) {
    private val root = cache.resolve("ai-bases")
    private fun link(id: String, revision: String): Path {
        require(TranslationSources.validId(id) && revision.matches(Regex("[a-f0-9]{64}")))
        return root.resolve("links/$id-$revision")
    }
    fun remember(id: String, revision: String, base: PreparedTranslation) {
        TranslationSources.writeAtomic(link(id, revision), store(base))
    }
    fun store(base: PreparedTranslation): String {
        val hash = base.manifest.archiveSha256
        require(hash.matches(Regex("[a-f0-9]{64}")))
        val directory = root.resolve(hash)
        if (Files.exists(directory) && runCatching { read(hash) }.isFailure)
            com.example.limbuszhcn.storage.AtomicFiles.deleteOwnedTree(root, directory)
        if (!Files.isRegularFile(directory.resolve("manifest.json"))) {
            Files.createDirectories(directory)
            var total = 0L
            base.manifest.entries.forEach { entry ->
                validateRelative(entry.cachePath); val source = ownedFile(base.directory, entry.cachePath)
                total += entry.size; require(total <= 128L*1024*1024 && Files.size(source) == entry.size && source.sha256() == entry.sha256)
                val target = directory.resolve(entry.cachePath); Files.createDirectories(target.parent)
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            TranslationSources.writeAtomic(directory.resolve("manifest.json"), base.manifest.toJson())
        }
        return hash
    }
    fun previous(source: TranslationSource): PreparedTranslation? {
        val file = link(source.id, source.revision)
        if (Files.isRegularFile(file) && Files.size(file) == 64L) {
            val hash = String(Files.readAllBytes(file), Charsets.US_ASCII)
            if (hash.matches(Regex("[a-f0-9]{64}"))) runCatching { read(hash) }.getOrNull()?.let { return it }
        }
        return source.officialVersion?.takeIf { source.baseId == null || source.baseId == "zero" }?.let { version ->
            runCatching { TranslationRepository(cache).verifiedCached(version.toString()).let { PreparedTranslation(it.outputDirectory, it.manifest) } }.getOrNull()
        }
    }
    fun read(hash: String): PreparedTranslation {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        val directory = root.resolve(hash); val marker = directory.resolve("manifest.json")
        require(Files.size(marker) <= 2*1024*1024)
        val manifest = PatchManifest.fromJson(String(Files.readAllBytes(marker), Charsets.UTF_8))
        require(manifest.archiveSha256 == hash)
        manifest.entries.forEach { entry ->
            val file = ownedFile(directory, entry.cachePath)
            require(Files.size(file) == entry.size && file.sha256() == entry.sha256) { "底包快照已变化，请重新检查" }
        }
        return PreparedTranslation(directory, manifest)
    }
    private fun validateRelative(path: String) { LocalTranslationArchive.safePath(path) }
    private fun ownedFile(root: Path, path: String): Path {
        validateRelative(path)
        return root.resolve(path).toRealPath().also { require(it.startsWith(root.toRealPath()) && Files.isRegularFile(it)) }
    }
    fun prune(state: SourceState, reviewing: String? = null) {
        val keep = (state.sources + listOfNotNull(state.undo?.previous)).filter { it.kind == "ai" }
            .map { "${it.id}-${it.revision}" }.toSet()
        val hashes = hashSetOf<String>().apply { reviewing?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let(::add) }
        val links = root.resolve("links")
        if (Files.isDirectory(links)) Files.newDirectoryStream(links).use { paths -> paths.forEach { path ->
            if (path.fileName.toString() in keep && Files.size(path) == 64L) {
                val hash = String(Files.readAllBytes(path), Charsets.US_ASCII)
                if (hash.matches(Regex("[a-f0-9]{64}"))) hashes += hash
            } else if (path.fileName.toString().matches(Regex("ai-[a-f0-9]{32}-[a-f0-9]{64}"))) Files.deleteIfExists(path)
        } }
        if (Files.isDirectory(root)) Files.newDirectoryStream(root).use { paths -> paths.forEach { path ->
            if (path.fileName.toString().matches(Regex("[a-f0-9]{64}")) && path.fileName.toString() !in hashes)
                com.example.limbuszhcn.storage.AtomicFiles.deleteOwnedTree(root, path)
        } }
        val provenance = cache.resolve("ai-provenance")
        if (Files.isDirectory(provenance)) Files.newDirectoryStream(provenance).use { paths -> paths.forEach { path ->
            if (path.fileName.toString().matches(Regex("ai-[a-f0-9]{32}-[a-f0-9]{64}\\.txt")) && path.fileName.toString().removeSuffix(".txt") !in keep)
                Files.deleteIfExists(path)
        } }
    }
}

/** Upstream-owned entries must not be counted as AI even when their wording equals an old response. */
internal object AiProvenanceExclusions {
    private fun file(cache: Path, id: String, revision: String): Path {
        require(TranslationSources.validId(id) && revision.matches(Regex("[a-f0-9]{64}")))
        return cache.resolve("ai-provenance/$id-$revision.txt")
    }
    fun read(cache: Path, source: TranslationSource): Set<String> {
        val path = file(cache, source.id, source.revision)
        if (!Files.isRegularFile(path)) return emptySet()
        require(Files.size(path) <= 34L*1024*1024)
        return Files.readAllLines(path, Charsets.US_ASCII).onEach { require(it.matches(Regex("[a-f0-9]{64}"))) }.toSet()
    }
    fun write(cache: Path, id: String, revision: String, ids: Set<String>) {
        require(ids.size <= MAX_AI_TASK_ENTRIES && ids.all { it.matches(Regex("[a-f0-9]{64}")) })
        TranslationSources.writeAtomic(file(cache,id,revision), ids.sorted().joinToString("\n"))
    }
}
