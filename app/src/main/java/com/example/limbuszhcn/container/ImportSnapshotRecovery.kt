package com.example.limbuszhcn.container

import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.update.sha256
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/** Only stopped, unreferenced host snapshots may be reclaimed before another copy. */
internal class ImportSnapshotRecovery(private val workspace: Path) {
    private val root = workspace.resolve("imports").toAbsolutePath().normalize()
    private val generation = Regex("[0-9a-f-]{36}")

    fun candidate(request: GameImportRequest, sources: List<Path>): Pair<Path, Properties>? = runCatching {
        val repair = AtomicFiles.readProperties(workspace.resolve("import-repair.properties"))
        val id = repair.getProperty("candidate") ?: return null
        if (!generation.matches(id) || repair.getProperty("phase") != "installing") return null
        val directory = root.resolve(id)
        val splits = directory.resolve("splits")
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || !Files.isDirectory(splits, NOFOLLOW_LINKS) ||
            !splits.toRealPath().startsWith(root.toRealPath())) return null
        val saved = AtomicFiles.readProperties(directory.resolve("snapshot.properties"))
        if (saved.getProperty("generation") != id || saved.getProperty("packageName") != LocalContainerRuntime.GAME_PACKAGE ||
            saved.getProperty("source") != request.source || saved.getProperty("versionCode") != request.versionCode?.toString() ||
            saved.getProperty("importedSplits")?.toIntOrNull() != sources.size) return null
        val names = sources.map { it.fileName.toString() }.toSet()
        Files.list(splits).use { if (it.iterator().asSequence().map { path -> path.fileName.toString() }.toSet() != names) return null }
        sources.forEachIndexed { index, source ->
            val file = splits.resolve(source.fileName)
            val hash = saved.getProperty("sha256.$index") ?: return null
            if (saved.getProperty("apk.$index") != source.fileName.toString() ||
                !Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) != Files.size(source) ||
                hash != source.sha256() || hash != file.sha256()) return null
        }
        directory to saved
    }.getOrNull()

    fun prune(committed: Properties?, referencedApks: List<String>, candidate: String? = null) {
        val keep = mutableSetOf<String>()
        listOfNotNull(committed?.getProperty("generation"), committed?.getProperty("previousGeneration"), candidate)
            .forEach { require(generation.matches(it)) { "导入快照记录无效，未清理已有文件" }; keep += it }
        referencedApks.forEach { raw ->
            val path = Paths.get(raw).toAbsolutePath().normalize()
            if (path.startsWith(root)) {
                val relative = root.relativize(path)
                require(relative.nameCount >= 2 && generation.matches(relative.getName(0).toString())) {
                    "容器引用路径无效，未清理已有文件"
                }
                keep += relative.getName(0).toString()
            }
        }
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return
        Files.list(root).use { paths -> paths.filter {
            Files.isDirectory(it, NOFOLLOW_LINKS) && generation.matches(it.fileName.toString()) && it.fileName.toString() !in keep
        }.forEach { AtomicFiles.deleteOwnedTree(root, it) } }
    }
}
