package com.example.limbuszhcn.container

import com.example.limbuszhcn.storage.AtomicFiles
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** APK identity alone does not prove a completed native-library deployment. */
internal class NativeLibraryDeployment(private val target: Path, private val supportedAbis: List<String>,
                                       private val requiredLibrary: String? = null) {
    private data class Library(val apk: Path, val entry: String, val name: String, val size: Long, val crc: Long)

    private fun libraries(apks: List<Path>): List<Library> {
        require(apks.isNotEmpty() && apks.all { Files.isRegularFile(it) }) { "原生库来源 APK 不完整" }
        val files = apks.flatMap { apk -> ZipFile(apk.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }.toList()
            val abi = supportedAbis.firstOrNull { abi -> entries.any { it.name.startsWith("lib/$abi/") } }
            require(entries.isEmpty() || abi != null) { "游戏组件没有设备支持的原生 ABI" }
            entries.filter { abi != null && it.name.startsWith("lib/$abi/") }.map {
                val name = it.name.substringAfterLast('/')
                require(name.matches(Regex("[A-Za-z0-9_.+-]+\\.so")) && it.size > 0 && it.crc >= 0) { "原生库清单无效" }
                Library(apk, it.name, name, it.size, it.crc)
            }
        } }
        require(files.map { it.name }.distinct().size == files.size) { "不同 APK 包含重复原生库，拒绝覆盖" }
        require(requiredLibrary == null || files.any { it.name == requiredLibrary }) { "缺少游戏核心原生库" }
        return files
    }

    private fun complete(directory: Path, libraries: List<Library>): Boolean {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return false
        val names = Files.list(directory).use { it.iterator().asSequence().map { path -> path.fileName.toString() }.toSet() }
        if (names != libraries.map { it.name }.toSet()) return false
        return libraries.all { library ->
            val path = directory.resolve(library.name)
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) != library.size) false
            else {
                val crc = CRC32()
                Files.newInputStream(path).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; crc.update(buffer, 0, count) }
                }
                crc.value == library.crc
            }
        }
    }

    fun isComplete(apks: List<Path>): Boolean = complete(target, libraries(apks))

    /** Caller stops the owning processes and holds the host operation lock. */
    fun install(apks: List<Path>): Int {
        val files = libraries(apks)
        Files.createDirectories(target.parent)
        // Only this deployment's orphan staging directories; never APKs or account/game data.
        val prefix = ".${target.fileName}.staging-"
        Files.list(target.parent).use { paths -> paths.filter {
            Files.isDirectory(it, NOFOLLOW_LINKS) && it.fileName.toString().startsWith(prefix) &&
                it.fileName.toString().removePrefix(prefix).matches(Regex("[0-9a-f-]{36}"))
        }.forEach { AtomicFiles.deleteOwnedTree(target.parent, it) } }
        if (complete(target, files)) return files.size
        val previous = target.resolveSibling(".${target.fileName}.previous")
        if (complete(previous, files)) {
            if (Files.exists(target, NOFOLLOW_LINKS)) AtomicFiles.deleteOwnedTree(target.parent, target)
            AtomicFiles.move(previous, target)
            return files.size
        }
        val staged = target.resolveSibling(prefix + UUID.randomUUID())
        Files.createDirectories(staged)
        try {
            files.groupBy { it.apk }.forEach { (apk, libraries) -> ZipFile(apk.toFile()).use { zip ->
                libraries.forEach { library ->
                    val outputFile = staged.resolve(library.name).toFile()
                    zip.getInputStream(zip.getEntry(library.entry)).use { input ->
                        FileOutputStream(outputFile).use { output ->
                            require(input.copyTo(output) == library.size) { "原生库复制不完整" }
                            output.fd.sync()
                        }
                    }
                    check(outputFile.setReadable(true, false) && outputFile.setExecutable(true, false)) { "无法设置原生库访问权限" }
                }
            } }
            check(complete(staged, files)) { "原生库内容校验失败" }
            AtomicFiles.replaceDirectory(staged, target)
            return files.size
        } finally {
            if (Files.exists(staged, NOFOLLOW_LINKS)) AtomicFiles.deleteOwnedTree(target.parent, staged)
        }
    }
}
