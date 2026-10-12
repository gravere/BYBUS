package com.example.limbuszhcn.update

import org.json.JSONObject
import com.example.limbuszhcn.storage.AtomicFiles
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream
import kotlin.io.path.readText

class TranslationArchiveExtractor(
    private val limits: ExtractionLimits = ExtractionLimits(),
    private val fileDigest: (Path) -> String = { it.sha256() }
) {
    fun extract(request: ExtractionRequest): ExtractionResult {
        require(request.version.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*"))) { "Unsafe version path" }
        require(request.archive.isRegularFile()) { "Archive does not exist: ${request.archive}" }
        request.cacheRoot.createDirectories()

        val archiveSha256 = sha256(request.archive)
        if (request.expectedSha256 != null && archiveSha256 != request.expectedSha256) {
            throw ArchiveValidationException(
                "Archive SHA-256 mismatch: expected ${request.expectedSha256}, actual $archiveSha256"
            )
        }

        val tempDir = request.cacheRoot.resolve(".${request.version}.${java.util.UUID.randomUUID()}.tmp")
        val finalDir = request.cacheRoot.resolve(request.version)
        verifiedCache(finalDir, request.version, archiveSha256)?.let { return it }
        resetDirectory(tempDir)

        val filesDir = tempDir.resolve("files").resolve("en")
        filesDir.createDirectories()

        val entries = mutableListOf<PatchFileEntry>()
        var archiveFileCount = 0
        var visitedEntries = 0
        var totalArchiveBytes = 0L
        val mappedPaths = mutableSetOf<String>()
        var totalUncompressedBytes = 0L
        var versionFromArchive: Long? = null

        try {
            SevenZFile.builder().setPath(request.archive).setMaxMemoryLimitKiB(limits.maxDecoderMemoryKiB).get().use { archive ->
                var entry = archive.nextEntry
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (entry != null) {
                    if (++visitedEntries > limits.maxArchiveFiles) throw ArchiveValidationException("Too many archive entries")
                    if (!entry.isDirectory) {
                        archiveFileCount += 1
                        if (archiveFileCount > limits.maxArchiveFiles) throw ArchiveValidationException("Too many archive entries")
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Extraction cancelled")
                        validateEntryMethods(entry)
                        if (entry.size < 0 || entry.size > limits.maxTotalArchiveBytes - totalArchiveBytes) {
                            throw ArchiveValidationException("Archive exceeds total expansion budget")
                        }
                        totalArchiveBytes += entry.size

                        val sourcePath = entry.name ?: throw ArchiveValidationException("Archive entry has no name")
                        val mapped = mapEntry(sourcePath)
                        if (mapped != null) {
                            if (!mappedPaths.add(mapped.cachePath)) throw ArchiveValidationException("Duplicate archive target")
                            val entrySize = entry.size
                            validateSize(sourcePath, entrySize)
                            totalUncompressedBytes += entrySize
                            if (totalUncompressedBytes > limits.maxTotalUncompressedBytes) {
                                throw ArchiveValidationException(
                                    "Archive uncompressed size exceeds ${limits.maxTotalUncompressedBytes} bytes"
                                )
                            }

                            val outputPath = tempDir.resolve(mapped.cachePath)
                            outputPath.parent.createDirectories()
                            copyEntry(archive, outputPath, buffer)

                            val fileSha256 = sha256(outputPath)
                            if (mapped.isVersionFile) {
                                versionFromArchive = readVersion(outputPath)
                            } else {
                                entries += PatchFileEntry(
                                    sourcePath = sourcePath,
                                    cachePath = mapped.cachePath,
                                    targetPath = mapped.targetPath,
                                    size = Files.size(outputPath),
                                    sha256 = fileSha256
                                )
                            }
                        }
                    }
                    entry = archive.nextEntry
                }
            }

            if (archiveFileCount > limits.maxArchiveFiles) {
                throw ArchiveValidationException("Archive file count exceeds ${limits.maxArchiveFiles}")
            }
            if (entries.isEmpty()) {
                throw ArchiveValidationException("Archive contains no patchable JSON files")
            }

            val manifest = PatchManifest(
                requestedVersion = request.version,
                archiveVersion = versionFromArchive,
                archiveSha256 = archiveSha256,
                archiveFileCount = archiveFileCount,
                extractedFileCount = entries.size,
                entries = entries.sortedBy { it.targetPath }
            )
            writeManifest(tempDir.resolve(MANIFEST_FILE), manifest)

            AtomicFiles.replaceDirectory(tempDir, finalDir)

            return ExtractionResult(
                outputDirectory = finalDir,
                manifest = manifest
            )
        } catch (error: Throwable) {
            deleteDirectory(tempDir)
            throw error
        }
    }

    /** A cache hit still checks every extracted byte; corrupt/incomplete generations are rebuilt. */
    private fun verifiedCache(directory: Path, version: String, archiveHash: String): ExtractionResult? =
        runCatching {
            val manifestPath = directory.resolve(MANIFEST_FILE)
            if (!manifestPath.isRegularFile() || Files.size(manifestPath) > 4L * 1024 * 1024) return null
            val manifest = PatchManifest.fromJson(manifestPath.readText())
            if (manifest.requestedVersion != version || manifest.archiveSha256 != archiveHash ||
                manifest.entries.isEmpty() || manifest.extractedFileCount != manifest.entries.size ||
                manifest.archiveFileCount > limits.maxArchiveFiles ||
                manifest.entries.size > limits.maxArchiveFiles ||
                manifest.entries.map { it.cachePath }.distinct().size != manifest.entries.size) return null
            val root = directory.toRealPath()
            var total = 0L
            for (entry in manifest.entries) {
                val mapped = mapEntry(entry.sourcePath) ?: return null
                if (mapped.isVersionFile || mapped.cachePath != entry.cachePath ||
                    mapped.targetPath != entry.targetPath) return null
                validateSize(entry.sourcePath, entry.size)
                total += entry.size
                if (total > limits.maxTotalUncompressedBytes) return null
                val file = root.resolve(entry.cachePath).normalize()
                if (!file.startsWith(root) || !file.isRegularFile() || !file.toRealPath().startsWith(root) ||
                    Files.size(file) != entry.size || sha256(file) != entry.sha256) return null
            }
            ExtractionResult(directory, manifest, fromCache = true)
        }.getOrNull()

    private fun validateEntryMethods(entry: SevenZArchiveEntry) {
        val methods = entry.contentMethods?.map { it.method } ?: emptyList()
        if (methods.isEmpty()) {
            return
        }
        val unsupported = methods.filterNot { it in ALLOWED_METHODS }
        if (unsupported.isNotEmpty()) {
            throw ArchiveValidationException(
                "Unsupported 7z method for ${entry.name}: ${unsupported.joinToString()}"
            )
        }
        if (SevenZMethod.LZMA2 !in methods && SevenZMethod.COPY !in methods) {
            throw ArchiveValidationException("Entry ${entry.name} does not use an allowed content compressor")
        }
    }

    private fun validateSize(sourcePath: String, entrySize: Long) {
        if (entrySize < 0) {
            throw ArchiveValidationException("Unknown entry size: $sourcePath")
        }
        if (entrySize > limits.maxSingleFileBytes) {
            throw ArchiveValidationException("Entry is too large: $sourcePath ($entrySize bytes)")
        }
    }

    private fun mapEntry(sourcePath: String): MappedEntry? {
        if (!sourcePath.startsWith(UPSTREAM_PREFIX)) {
            return null
        }

        val relative = sourcePath.removePrefix(UPSTREAM_PREFIX)
        if (relative.isBlank()) {
            return null
        }

        val normalizedText = normalizeArchiveRelativePath(relative)
        if (normalizedText == null) {
            throw ArchiveValidationException("Unsafe archive path: $sourcePath")
        }

        if (normalizedText == VERSION_ENTRY) {
            return MappedEntry(
                cachePath = "info/version.json",
                targetPath = "",
                isVersionFile = true
            )
        }

        if (!normalizedText.endsWith(".json") || normalizedText.startsWith("Info/")) {
            return null
        }

        val gameRelative = withGameEnglishPrefix(normalizedText)
        return MappedEntry(
            cachePath = "files/en/$gameRelative",
            targetPath = "en/$gameRelative",
            isVersionFile = false
        )
    }

    private fun withGameEnglishPrefix(path: String): String {
        val slash = path.lastIndexOf('/')
        val directory = if (slash >= 0) path.substring(0, slash + 1) else ""
        val fileName = if (slash >= 0) path.substring(slash + 1) else path
        return directory + if (fileName.startsWith("EN_")) fileName else "EN_$fileName"
    }

    private fun normalizeArchiveRelativePath(path: String): String? {
        if (path.startsWith("/") || path.startsWith("\\") || path.contains('\u0000')) {
            return null
        }
        val parts = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (parts.isEmpty()) {
                        return null
                    }
                    parts.removeAt(parts.lastIndex)
                }
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun copyEntry(archive: SevenZFile, outputPath: Path, buffer: ByteArray) {
        BufferedOutputStream(outputPath.outputStream()).use { output ->
            var written = 0L
            while (true) {
                val read = archive.read(buffer)
                if (read < 0) {
                    break
                }
                written += read
                if (written > limits.maxSingleFileBytes) throw ArchiveValidationException("Expanded file exceeds size limit")
                output.write(buffer, 0, read)
            }
        }
    }

    private fun readVersion(path: Path): Long? {
        val text = String(Files.readAllBytes(path), Charsets.UTF_8)
        return VERSION_REGEX.find(text)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun writeManifest(path: Path, manifest: PatchManifest) {
        path.parent.createDirectories()
        Files.write(path, manifest.toJson().toByteArray(Charsets.UTF_8))
    }

    private fun sha256(path: Path): String = fileDigest(path)

    private fun resetDirectory(path: Path) {
        if (path.exists()) {
            deleteDirectory(path)
        }
        path.createDirectories()
    }

    @OptIn(ExperimentalPathApi::class)
    private fun deleteDirectory(path: Path) {
        if (path.exists()) {
            path.deleteRecursively()
        }
    }

    private data class MappedEntry(
        val cachePath: String,
        val targetPath: String,
        val isVersionFile: Boolean
    )

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        private const val UPSTREAM_PREFIX = "LimbusCompany_Data/Lang/LLC_zh-CN/"
        private const val VERSION_ENTRY = "Info/version.json"
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private val VERSION_REGEX = Regex(""""version"\s*:\s*(\d+)""")
        private val ALLOWED_METHODS = setOf(
            SevenZMethod.COPY,
            SevenZMethod.LZMA2,
            SevenZMethod.BCJ_X86_FILTER
        )
    }
}

data class ExtractionRequest(
    val archive: Path,
    val cacheRoot: Path,
    val version: String,
    val expectedSha256: String? = null
)

data class ExtractionLimits(
    val maxArchiveFiles: Int = 5_000,
    val maxSingleFileBytes: Long = 2L * 1024L * 1024L,
    val maxTotalUncompressedBytes: Long = 128L * 1024L * 1024L,
    val maxDecoderMemoryKiB: Int = 128 * 1024,
    val maxTotalArchiveBytes: Long = 256L * 1024 * 1024
)

data class ExtractionResult(
    val outputDirectory: Path,
    val manifest: PatchManifest,
    val fromCache: Boolean = false
)

data class PatchManifest(
    val requestedVersion: String,
    val archiveVersion: Long?,
    val archiveSha256: String,
    val archiveFileCount: Int,
    val extractedFileCount: Int,
    val entries: List<PatchFileEntry>
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"requestedVersion\": ${requestedVersion.jsonValue()},")
        appendLine("  \"archiveVersion\": ${archiveVersion?.toString() ?: "null"},")
        appendLine("  \"archiveSha256\": ${archiveSha256.jsonValue()},")
        appendLine("  \"archiveFileCount\": $archiveFileCount,")
        appendLine("  \"extractedFileCount\": $extractedFileCount,")
        appendLine("  \"entries\": [")
        entries.forEachIndexed { index, entry ->
            append(entry.toJson("    "))
            if (index != entries.lastIndex) {
                appendLine(",")
            } else {
                appendLine()
            }
        }
        appendLine("  ]")
        appendLine("}")
    }

    companion object {
        fun fromJson(json: String): PatchManifest {
            try {
                val root = JSONObject(json)
                val items = root.getJSONArray("entries")
                require(items.length() <= 5000) { "Too many manifest entries" }
                return PatchManifest(
                    root.getString("requestedVersion"),
                    if (root.isNull("archiveVersion")) null else root.getLong("archiveVersion"),
                    root.getString("archiveSha256"), root.getInt("archiveFileCount"), root.getInt("extractedFileCount"),
                    (0 until items.length()).map { index ->
                        val item = items.getJSONObject(index)
                        PatchFileEntry(item.getString("sourcePath"), item.getString("cachePath"),
                            item.getString("targetPath"), item.getLong("size"), item.getString("sha256"))
                    }
                )
            } catch (error: org.json.JSONException) {
                throw ArchiveValidationException("Invalid patch manifest JSON")
            }
        }
    }

}

data class PatchFileEntry(
    val sourcePath: String,
    val cachePath: String,
    val targetPath: String,
    val size: Long,
    val sha256: String
) {
    fun toJson(indent: String): String = buildString {
        appendLine("$indent{")
        appendLine("$indent  \"sourcePath\": ${sourcePath.jsonValue()},")
        appendLine("$indent  \"cachePath\": ${cachePath.jsonValue()},")
        appendLine("$indent  \"targetPath\": ${targetPath.jsonValue()},")
        appendLine("$indent  \"size\": $size,")
        appendLine("$indent  \"sha256\": ${sha256.jsonValue()}")
        append("$indent}")
    }
}

class ArchiveValidationException(message: String) : IOException(message)
