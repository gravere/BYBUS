package com.example.limbuszhcn.gamefs

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

data class ResourceClearResult(val files: Long, val bytes: Long)

/** Only known resource roots supplied by GameStorage; never an application data directory. */
internal object DownloadedResourceCleaner {
    fun clear(localizeRoot: Path, roots: Map<String, Path>): ResourceClearResult {
        val assets = localizeRoot.toAbsolutePath().normalize().parent.parent
        val expected = mapOf(
            "assets" to "Assets",
            "external-unity-cache" to "cache/UnityCache/Shared",
            "external-files-unity-cache" to "files/UnityCache/Shared",
            "internal-unity-cache" to "cache/UnityCache/Shared",
            "internal-files-unity-cache" to "files/UnityCache/Shared")
        require(roots.isNotEmpty() && roots.keys.all { it in expected }) { "游戏资源目录无效" }
        val targets = roots.map { (alias, path) ->
            val root = path.toAbsolutePath().normalize()
            val suffix = root.fileSystem.getPath(expected.getValue(alias))
            require(root.endsWith(suffix) && (alias != "assets" || root == assets)) { "游戏资源目录不匹配" }
            // Reject links in the relative resource path, including UnityCache parents.
            var part = root
            repeat(suffix.nameCount) {
                require(!Files.isSymbolicLink(part)) { "资源目录是链接，已取消清理" }
                if (Files.exists(part, NOFOLLOW_LINKS))
                    require(Files.isDirectory(part, NOFOLLOW_LINKS)) { "资源目录类型异常，已取消清理" }
                part = part.parent
            }
            root
        }
        require(targets.distinct().size == targets.size && targets.none { a -> targets.any { b -> a != b && a.startsWith(b) } }) {
            "游戏资源目录重叠，已取消清理"
        }
        var count = 0L
        var bytes = 0L
        // All roots are validated before the first deletion. walkFileTree does not follow links.
        targets.forEach { root ->
            if (!Files.exists(root, NOFOLLOW_LINKS)) return@forEach
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    count++
                    if (attrs.isRegularFile) bytes += attrs.size()
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    if (dir != root) Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return ResourceClearResult(count, bytes)
    }
}
