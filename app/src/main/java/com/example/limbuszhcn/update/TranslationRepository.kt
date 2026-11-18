package com.example.limbuszhcn.update

import com.example.limbuszhcn.storage.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/** Owns downloaded releases and verified persistent cache, independently of activation. */
internal class TranslationRepository(
    private val root: Path,
    private val releaseMetadata: (() -> String)? = null,
    private val fileDigest: (Path) -> String = { it.sha256() },
    private val onProgress: (String) -> Unit = {}
) {
    fun checkLatest(releaseUrl: String, installedVersion: Long?): ExtractionResult =
        TranslationUpdateManager(releaseMetadata = releaseMetadata,
            extractor = TranslationArchiveExtractor(fileDigest = fileDigest), onProgress = onProgress)
            .installFromGitHub(InstallGitHubReleaseRequest(
            releaseUrl, root.resolve("downloads"), root.resolve("patch-cache"), installedVersion))

    /** A waiting-budget hint only. This must never authorize a launch without full validation. */
    fun hasLaunchCandidate(): Boolean = runCatching {
        val downloads = root.resolve("downloads")
        if (!Files.isDirectory(downloads)) return false
        Files.list(downloads).use { paths -> paths.anyMatch { downloadedVersion(it) != null } }
    }.getOrDefault(false)

    /** Local save times by release. Reading or revalidating an archive leaves its time unchanged. */
    fun downloadTimes(): Map<Long, Long> {
        val downloads = root.resolve("downloads")
        if (!Files.isDirectory(downloads)) return emptyMap()
        return try {
            Files.newDirectoryStream(downloads).use { paths ->
                buildMap {
                    for (path in paths) {
                        val version = downloadedVersion(path) ?: continue
                        val time = try { Files.getLastModifiedTime(path).toMillis() }
                            catch (_: java.io.IOException) { 0L }
                        put(version, time)
                    }
                }
            }
        } catch (_: java.io.IOException) { emptyMap() }
    }

    private fun downloadedVersion(file: Path): Long? {
        val version = ARCHIVE_NAME.matchEntire(file.fileName.toString())?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return try { version.takeIf { it > 0 && Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.size(file) > 0 } }
        catch (_: java.io.IOException) { null }
    }

    fun cachedManifest(version: String): PatchManifest {
        require(version.matches(Regex("[0-9]+"))) { "Invalid cached release version" }
        val patches = root.resolve("patch-cache").toRealPath()
        val manifestPath = patches.resolve("$version/manifest.json").toRealPath()
        require(manifestPath.startsWith(patches) && Files.size(manifestPath) <= 4L * 1024 * 1024) {
            "Invalid cached release manifest"
        }
        val manifest = PatchManifest.fromJson(manifestPath.readText())
        require(manifest.requestedVersion == version) { "Cached release version mismatch" }
        return manifest
    }

    fun verifiedCached(version: String): ExtractionResult {
        val manifest = cachedManifest(version)
        return TranslationArchiveExtractor(fileDigest = fileDigest).extract(ExtractionRequest(
            root.resolve("downloads/LimbusLocalize_$version.7z"), root.resolve("patch-cache"),
            version, manifest.archiveSha256))
    }

    fun cachedVersions(): List<String> {
        val patches = root.resolve("patch-cache")
        return if (!Files.isDirectory(patches)) emptyList() else Files.list(patches).use { paths ->
            paths.filter { Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                .map { it.fileName.toString() }
                .filter { it.matches(Regex("[0-9]{1,18}")) && it.toLong() > 0 }
                .sorted { a, b -> b.toLong().compareTo(a.toLong()) }.iterator().asSequence().toList()
        }
    }

    /** A downloaded release is recoverable even before its first runtime index exists. */
    fun verifiedForLaunch(preferredVersion: String?): ExtractionResult {
        var lastError: Exception? = null
        for (version in (listOfNotNull(preferredVersion) + cachedVersions()).distinct()) {
            try { return verifiedCached(version) }
            catch (error: Exception) {
                if (Thread.currentThread().isInterrupted || error is java.util.concurrent.CancellationException) throw error
                lastError = error
            }
        }
        throw IllegalStateException("没有通过完整校验的本地汉化包，请联网后重试", lastError)
    }

    private companion object { val ARCHIVE_NAME = Regex("LimbusLocalize_([0-9]{1,18})\\.7z") }

    /** Called under the operation lock, after activation or a complete cache preparation. */
    fun prune(retainedVersions: Set<String>) {
        val patches = root.resolve("patch-cache")
        if (Files.isDirectory(patches)) Files.list(patches).use { paths ->
            paths.filter { Files.isDirectory(it) && !Files.exists(it.resolve("baseline")) }.forEach { path ->
                val name = path.fileName.toString()
                val previousVersion = Regex("\\.([0-9]+)\\.previous").matchEntire(name)?.groupValues?.get(1)
                val obsolete = (name.matches(Regex("[0-9]+")) && name !in retainedVersions) ||
                    (previousVersion != null && previousVersion !in retainedVersions) ||
                    name.matches(Regex("\\.[0-9]+\\.[a-f0-9-]{36}\\.tmp"))
                if (obsolete) AtomicFiles.deleteOwnedTree(patches, path)
            }
        }
        val downloads = root.resolve("downloads")
        if (Files.isDirectory(downloads)) Files.list(downloads).use { paths ->
            val pattern = Regex("LimbusLocalize_([0-9]+)\\.7z")
            paths.filter { Files.isRegularFile(it) }.forEach { file ->
                val version = pattern.matchEntire(file.fileName.toString())?.groupValues?.get(1)
                val abandoned = file.fileName.toString().matches(Regex("LimbusLocalize_[0-9]+\\.7z\\.[a-f0-9-]{36}\\.tmp"))
                if (abandoned || (version != null && version !in retainedVersions && !Files.exists(patches.resolve("$version/baseline")))) {
                    Files.deleteIfExists(file)
                }
            }
        }
    }
}
