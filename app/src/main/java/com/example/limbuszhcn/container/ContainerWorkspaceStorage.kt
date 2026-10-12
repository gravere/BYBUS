package com.example.limbuszhcn.container

import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.update.sha256
import java.util.UUID
import com.example.limbuszhcn.gamefs.GameStorageProbe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import java.util.stream.Collectors
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.outputStream

class ContainerWorkspaceStorage(
    private val localizeRoot: Path
) : GameStorage {
    override fun probe(): GameStorageProbe {
        val en = localizeRoot.resolve("en")
        if (!Files.isDirectory(en)) {
            return GameStorageProbe(exists = false, jsonFileCount = 0, remoteListPresent = false)
        }
        val jsonCount = Files.walk(en).use { paths ->
            paths.filter { it.isRegularFile() && it.name.endsWith(".json") }.count().toInt()
        }
        return GameStorageProbe(
            exists = true,
            jsonFileCount = jsonCount,
            remoteListPresent = localizeRoot.resolve("RemoteLocalizeFileList.json").isRegularFile()
        )
    }

    override fun exists(path: String): Boolean =
        resolveAbsolute(path).exists()

    override fun read(path: String): ByteArray? {
        val target = resolveAbsolute(path)
        return if (target.isRegularFile()) {
            Files.readAllBytes(target)
        } else {
            null
        }
    }

    override fun write(path: String, source: Path) {
        val target = resolveAbsolute(path)
        target.parent.createDirectories()
        val temp = target.parent.resolve("${target.fileName}.tmp-${System.currentTimeMillis()}")
        source.inputStream().use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        Files.move(
            temp,
            target,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
    }

    override fun delete(path: String): Boolean =
        Files.deleteIfExists(resolveAbsolute(path))

    override fun deleteResource(relativePath: String): Boolean =
        Files.deleteIfExists(resolveResourceAbsolute(relativePath))

    override fun resolvePatchTarget(targetPath: String): String {
        val relative = targetPath.removePrefix("en/")
        val slash = relative.lastIndexOf('/')
        val prefixedRelative = if (slash >= 0) {
            relative.substring(0, slash + 1) + "EN_" + relative.substring(slash + 1)
        } else {
            "EN_$relative"
        }
        val prefixed = rootPath().trimEnd('/') + "/en/" + prefixedRelative
        return if (exists(prefixed)) {
            prefixed
        } else {
            rootPath().trimEnd('/') + "/" + targetPath
        }
    }

    override fun rootPath(): String =
        localizeRoot.toAbsolutePath().normalize().toString().replace('\\', '/')

    private fun resolveAbsolute(path: String): Path {
        val root = localizeRoot.toAbsolutePath().normalize()
        val absolute = if (path.startsWith(rootPath())) {
            root.resolve(path.removePrefix(rootPath()).removePrefix("/"))
        } else {
            root.resolve(path.removePrefix("/"))
        }.toAbsolutePath().normalize()
        if (!absolute.startsWith(root)) {
            throw IOException("Rejected path outside container Localize root: $path")
        }
        return absolute
    }

    private fun resolveResourceAbsolute(path: String): Path {
        val root = localizeRoot.parent.toAbsolutePath().normalize()
        val absolute = root.resolve(path.removePrefix("/")).toAbsolutePath().normalize()
        if (!absolute.startsWith(root)) {
            throw IOException("Rejected path outside container resource root: $path")
        }
        return absolute
    }
}

class LocalContainerRuntime(
    private val workspaceRoot: Path
) : ContainerRuntime {
    private val metadataFile = workspaceRoot.resolve("container-state.properties")
    private val repairFile = workspaceRoot.resolve("import-repair.properties")
    private val localizeRoot = workspaceRoot
        .resolve("virtual-data")
        .resolve(GAME_PACKAGE)
        .resolve("files")
        .resolve("Assets")
        .resolve("Resources_moved")
        .resolve("Localize")

    override fun status(): ContainerStatus {
        val metadata = readMetadata()
        val imported = metadata != null && importedSplitApkPaths().let { paths ->
            paths.isNotEmpty() && paths.size == metadata.getProperty("importedSplits")?.toIntOrNull() && paths.all { Files.isRegularFile(Paths.get(it)) }
        }
        val importedSplits = metadata?.getProperty("importedSplits")?.toIntOrNull() ?: 0
        val versionCode = metadata?.getProperty("versionCode")?.toLongOrNull()
        val source = metadata?.getProperty("source")
        return ContainerStatus(
            available = true,
            gameImported = imported,
            message = if (imported) {
                buildString {
                    append("容器工作区已导入游戏")
                    if (versionCode != null) append(" v").append(versionCode)
                    if (importedSplits > 0) append(", ").append(importedSplits).append(" 个 split")
                }
            } else {
                "容器工作区待导入游戏"
            },
            importedSplits = importedSplits,
            importedVersionCode = versionCode,
            importSource = source,
            requiresInstalledGameResync = Files.exists(repairFile)
        )
    }

    override fun importGame(request: GameImportRequest): GameImportResult = importGame(request) { }

    /** Metadata is the commit point. An external failure leaves old snapshots and a repair marker. */
    internal fun importGame(request: GameImportRequest, install: (GameImportRequest) -> Unit): GameImportResult {
        require(request.splitApkPaths.isNotEmpty()) { "需要至少一个 APK" }
        val sources = request.splitApkPaths.map { Paths.get(it).toAbsolutePath().normalize() }
        require(sources.all { Files.isRegularFile(it) }) { "游戏 APK 集合不完整，请重试同步" }
        require(sources.map { it.fileName.toString() }.distinct().size == sources.size) { "APK 文件名重复" }
        val generations = workspaceRoot.resolve("imports").also { it.createDirectories() }
        val id = UUID.randomUUID().toString()
        val candidate = generations.resolve(id)
        val splits = candidate.resolve("splits").also { it.createDirectories() }
        val metadata = Properties().apply {
            setProperty("packageName", GAME_PACKAGE)
            setProperty("importedSplits", sources.size.toString())
            setProperty("source", request.source)
            setProperty("generation", id)
            request.versionCode?.let { setProperty("versionCode", it.toString()) }
        }
        val committed = readMetadata()
        val reuse = ImportSnapshotReuse(workspaceRoot)
        try {
            sources.forEachIndexed { index, source ->
                val before = source.sha256()
                val target = splits.resolve(source.fileName)
                reuse.copyOrLink(source, target, before, committed)
                require(target.sha256() == before && source.sha256() == before) { "复制期间游戏包已变化，请重试同步" }
                metadata.setProperty("apk.$index", target.fileName.toString())
                metadata.setProperty("sha256.$index", before)
            }
            AtomicFiles.properties(candidate.resolve("snapshot.properties"), metadata)
        } catch (error: Exception) {
            AtomicFiles.deleteOwnedTree(generations, candidate)
            throw error
        }
        val previous = readMetadata()?.getProperty("generation")
        val prepared = request.copy(splitApkPaths = sources.map { splits.resolve(it.fileName).toString() })
        AtomicFiles.properties(repairFile, Properties().apply {
            setProperty("candidate", id)
            setProperty("phase", "installing")
        })
        install(prepared)
        localizeRoot.resolve("en").createDirectories()
        previous?.let { metadata.setProperty("previousGeneration", it) }
        AtomicFiles.properties(metadataFile, metadata)
        Files.deleteIfExists(repairFile)
        // Only prune after a complete commit. Never touch legacy splits or game data here.
        runCatching {
            Files.list(generations).use { paths ->
                paths.filter { it.fileName.toString() !in setOf(id, previous) }
                    .forEach { AtomicFiles.deleteOwnedTree(generations, it) }
            }
        }
        return GameImportResult(sources.size, GAME_PACKAGE, request.versionCode, request.source)
    }

    override fun storage(): GameStorage =
        ContainerWorkspaceStorage(localizeRoot)

    internal fun compactSnapshots(): Long = ImportSnapshotReuse(workspaceRoot).compact(readMetadata())

    fun importedSplitApkPaths(): List<String> {
        val generation = readMetadata()?.getProperty("generation")
        val splitDir = if (generation == null) workspaceRoot.resolve("splits") else {
            require(generation.matches(Regex("[0-9a-f-]{36}"))) { "Invalid import generation" }
            workspaceRoot.resolve("imports").resolve(generation).resolve("splits")
        }
        if (!splitDir.exists()) {
            return emptyList()
        }
        return Files.list(splitDir).use { paths ->
            paths
                .filter { it.isRegularFile() && it.name.endsWith(".apk", ignoreCase = true) }
                .sorted { left, right ->
                    fun rank(path: Path): Int =
                        if (path.name.equals("base.apk", ignoreCase = true)) 0 else 1
                    compareBy<Path> { rank(it) }.thenBy { it.name }.compare(left, right)
                }
                .map { it.toAbsolutePath().normalize().toString() }
                .collect(Collectors.toList())
        }
    }

    override fun launchGame() {
        throw ContainerRuntimeUnavailableException("真实容器运行时尚未接入;当前仅完成工作区与文件系统 Spike")
    }

    private fun readMetadata(): Properties? {
        if (!metadataFile.isRegularFile()) {
            return null
        }
        return runCatching { AtomicFiles.readProperties(metadataFile) }.getOrNull()
    }

    companion object {
        const val GAME_PACKAGE = "com.ProjectMoon.LimbusCompany"
    }
}
