package com.example.limbuszhcn.update

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** Reuses reads only within one locked operation. Never persist or reuse between launches. */
internal class FileDigestSession(private val hash: (Path) -> String = { it.sha256() }) {
    private data class Identity(val key: Any?, val size: Long, val modified: FileTime, val created: FileTime)
    private data class Verified(val identity: Identity, val digest: String)
    private val verified = mutableMapOf<Path, Verified>()

    fun sha256(path: Path): String {
        val real = path.toRealPath()
        val before = identity(real)
        verified[real]?.takeIf { it.identity == before }?.let { return it.digest }
        val digest = hash(real)
        if (identity(real) != before) throw IOException("校验期间汉化文件发生变化，请重试")
        verified[real] = Verified(before, digest)
        return digest
    }

    private fun identity(path: Path): Identity {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        require(attributes.isRegularFile) { "汉化文件不可读" }
        return Identity(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), attributes.creationTime())
    }
}
