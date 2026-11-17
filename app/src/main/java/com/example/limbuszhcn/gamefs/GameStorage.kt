package com.example.limbuszhcn.gamefs

import com.example.limbuszhcn.update.PatchManifest
import java.nio.file.Path
import kotlin.io.path.inputStream

interface GameStorage {
    fun probe(): GameStorageProbe
    fun exists(path: String): Boolean
    fun read(path: String): ByteArray?
    fun write(path: String, source: Path)
    fun delete(path: String): Boolean
    fun resolvePatchTarget(targetPath: String): String
    fun rootPath(): String
    /** Relative, bounded static Japanese resources only; never account or preference files. */
    fun japaneseResources(): List<String> = emptyList()
    fun japaneseResourceMetadata(): Map<String, String>? = null
    /** Freshness only; never authorizes a patch or replaces content validation. */
    fun japaneseResourceStamp(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var total = 0L
        japaneseResources().sorted().forEach { key ->
            val bytes = read(rootPath().trimEnd('/') + "/$key") ?: error("游戏文本正在变化")
            total += bytes.size; require(total <= 128L * 1024 * 1024)
            digest.update(key.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun deleteResource(relativePath: String): Boolean = false
    fun resourceMigrationRoots(): Map<String, Path> = emptyMap()
    fun clearDownloadedResources(): ResourceClearResult = error("当前存储不支持清理游戏资源")
}

data class GameStorageProbe(
    val exists: Boolean,
    val jsonFileCount: Int,
    val remoteListPresent: Boolean,
    val japaneseExists: Boolean = false,
    val japaneseJsonFileCount: Int = 0
)

data class PatchInstallSummary(
    val version: String,
    val writtenFiles: Int,
    val indexActivated: Boolean = true,
    val candidateSourceFiles: Int = writtenFiles,
    val availableSourceFiles: Int = writtenFiles,
    val sourceRecordCount: Int = 0,
    val previousIndexPreserved: Boolean = false,
    val reusedIndex: Boolean = false,
    val startupIndexAvailable: Boolean = false,
    val liveResourcesReady: Boolean = false,
    val pendingReason: String? = null
)

data class PatchUninstallSummary(
    val version: String,
    val deletedFiles: Int
)

class GameStorageInstaller(
    private val storage: GameStorage
) {
    fun install(patchDirectory: Path, manifest: PatchManifest): PatchInstallSummary {
        var written = 0
        manifest.entries.forEach { entry ->
            val source = patchDirectory.resolve(entry.cachePath)
            storage.write(storage.resolvePatchTarget(entry.targetPath), source)
            written += 1
        }
        return PatchInstallSummary(version = manifest.requestedVersion, writtenFiles = written)
    }

    fun uninstall(manifest: PatchManifest): PatchUninstallSummary {
        val targets = linkedSetOf<String>()
        manifest.entries.forEach { entry ->
            targets += storage.resolvePatchTarget(entry.targetPath)
            targets += storage.rootPath().trimEnd('/') + "/" + entry.targetPath
        }

        var deleted = 0
        targets.forEach { target ->
            if (storage.exists(target) && storage.delete(target)) {
                deleted += 1
            }
        }
        return PatchUninstallSummary(version = manifest.requestedVersion, deletedFiles = deleted)
    }
}

fun readFileBytes(path: Path): ByteArray =
    path.inputStream().use { it.readBytes() }
