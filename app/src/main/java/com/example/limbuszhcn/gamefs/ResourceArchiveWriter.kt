package com.example.limbuszhcn.gamefs

import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Read-only exporter. May run in the root helper; never creates or removes source files. */
internal class ResourceArchiveWriter(
    roots: Map<String, Path>, private val version: Long,
    private val cancelled: () -> Boolean = { false },
    private val progress: (ResourceTransferProgress) -> Unit = {}
) {
    private val roots = roots.mapValues { it.value.toAbsolutePath().normalize() }
    private data class Entry(val name: String, val path: Path, val size: Long, val modified: Long, val key: Any?)

    fun write(output: OutputStream): ResourceTransferResult {
        require(version > 0 && roots.isNotEmpty()) { "游戏资源来源无效" }
        val entries = mutableListOf<Entry>()
        roots.toSortedMap().forEach { (alias, root) ->
            require(alias.matches(Regex("[a-z][a-z0-9-]{0,63}")))
            if (!Files.exists(root, NOFOLLOW_LINKS)) return@forEach
            checkPath(root)
            require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "资源目录无效" }
            Files.walk(root, 64).use { paths -> paths.forEach { path ->
                checkCancelled()
                require(!Files.isSymbolicLink(path)) { "资源目录包含符号链接，不能导入" }
                if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                    val name = "$alias/${root.relativize(path).toString().replace('\\', '/')}"
                    if (ResourceArchivePaths.allowed(name, roots.keys)) {
                        val attr = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        require(attr.size() in 0..16L * 1024 * 1024 * 1024) { "单个资源过大" }
                        entries += Entry(name, path, attr.size(), attr.lastModifiedTime().toMillis(), attr.fileKey())
                        require(entries.size <= 50_000) { "资源文件数量超过迁移上限" }
                    }
                }
            } }
        }
        entries.sortBy { it.name }
        val total = entries.sumOf { it.size }
        require(entries.isNotEmpty() && total in 1..64L * 1024 * 1024 * 1024) { "原版尚无可导入的资源，或资源超过迁移上限" }
        val hashes = mutableListOf<String>()
        var copied = 0L
        var lastReport = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry("migration.properties"))
            Properties().apply {
                setProperty("format", "limbus-resources-v1")
                setProperty("package", "com.ProjectMoon.LimbusCompany")
                setProperty("versionCode", version.toString())
                setProperty("files", entries.size.toString())
                setProperty("bytes", total.toString())
            }.store(zip, "Limbus resources only")
            zip.closeEntry()
            val buffer = ByteArray(128 * 1024)
            for (entry in entries) {
                checkCancelled()
                checkPath(entry.path)
                require(unchanged(entry)) { "复制期间原版资源发生变化，请退出游戏后重试" }
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry("data/${entry.name}"))
                var actual = 0L
                Files.newInputStream(entry.path, NOFOLLOW_LINKS).use { input ->
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        actual += count
                        require(actual <= entry.size) { "复制期间原版资源发生变化" }
                        zip.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                        val now = System.nanoTime()
                        if (now - lastReport >= 200_000_000L) {
                            progress(ResourceTransferProgress("正在迁出资源", copied, total)); lastReport = now
                        }
                    }
                }
                require(actual == entry.size && unchanged(entry)) { "复制期间原版资源发生变化，请重试" }
                hashes += digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                zip.closeEntry()
            }
            // Check the entire snapshot again before producing the final commit manifest.
            require(entries.all { unchanged(it) }) { "复制期间原版资源发生变化，请重试" }
            zip.putNextEntry(ZipEntry("checksums.bin"))
            val index = DataOutputStream(zip)
            index.writeInt(entries.size)
            entries.forEachIndexed { i, entry -> index.writeUTF(entry.name); index.writeLong(entry.size); index.writeUTF(hashes[i]) }
            zip.closeEntry()
        }
        progress(ResourceTransferProgress("迁出完成", copied, total))
        return ResourceTransferResult(entries.size, copied, sourceVersion = version)
    }

    private fun unchanged(entry: Entry): Boolean {
        checkPath(entry.path)
        val a = Files.readAttributes(entry.path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        return a.isRegularFile && a.size() == entry.size && a.lastModifiedTime().toMillis() == entry.modified && a.fileKey() == entry.key
    }

    private fun checkCancelled() { if (cancelled()) throw CancellationException("资源迁移已取消") }

    private fun checkPath(path: Path) {
        val root = roots.values.firstOrNull { path.startsWith(it) } ?: error("资源路径越界")
        var part: Path? = path
        while (part != null && part.startsWith(root)) {
            require(!Files.isSymbolicLink(part)) { "资源路径包含符号链接" }
            part = part.parent
        }
    }
}

internal object ResourceArchivePaths {
    fun allowed(name: String, aliases: Set<String>): Boolean {
        if (name.toByteArray(Charsets.UTF_8).size > 512 || name.contains('\\') || name.contains(':') ||
            name.any { it.code < 32 } || name.startsWith('/')) return false
        val parts = name.split('/')
        if (parts.size < 2 || parts.any { it.isEmpty() || it == "." || it == ".." } || parts.first() !in aliases) return false
        val denied = setOf("shared_prefs", "databases", "playerprefs", "accounts", "cookies", "temp")
        if (parts.any { it.lowercase() in denied || "playerprefs" in it.lowercase() }) return false
        return !parts.last().lowercase().let { it.endsWith(".apk") || it.endsWith(".dex") || it.endsWith(".so") || it.endsWith(".tmp") }
    }
}
