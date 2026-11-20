package com.example.limbuszhcn.container

import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.update.toLowerHex
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Cache only deterministic paired records; final conflict resolution still sees the complete corpus. */
internal class TranslationPairCache(private val root: Path, private val enabled: Boolean = true) {
    private val used = hashSetOf<String>()
    fun records(path: String, original: String, translated: String, build: () -> List<TranslationIndexRecord>): List<TranslationIndexRecord> {
        val hash = MessageDigest.getInstance("SHA-256")
        listOf(TRANSLATION_COMPILER_REVISION, path, original, translated).forEach { hash.update(it.toByteArray(Charsets.UTF_8)); hash.update(0) }
        val key = hash.digest().toLowerHex(); used += key
        val file = root.resolve("$key.bin")
        if (enabled && Files.isRegularFile(file) && Files.size(file) <= 16 * 1024 * 1024) runCatching {
            val bytes = Files.readAllBytes(file)
            require(bytes.size >= 32)
            val payload = bytes.copyOfRange(32, bytes.size)
            require(MessageDigest.isEqual(bytes.copyOfRange(0,32), MessageDigest.getInstance("SHA-256").digest(payload)))
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == 2)
                fun text(): String { val length = input.readInt(); require(length in 0..1024*1024)
                    val bytes = ByteArray(length); input.readFully(bytes); return String(bytes, Charsets.UTF_8) }
                require(text() == key)
                val count = input.readInt(); require(count in 0..100_000)
                val rows = List(count) { TranslationIndexRecord(text(), text(), text(), text(), text(), text().takeIf(String::isNotEmpty)) }
                require(input.read() == -1); rows
            }
        }.getOrNull()?.let { return it }
        val rows = build()
        // Rebuildable cache writes cannot make an otherwise valid translation unavailable.
        runCatching {
            Files.createDirectories(root)
            val temporary = root.resolve("$key.tmp")
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); require(bytes.size <= 1024*1024)
                    output.writeInt(bytes.size); output.write(bytes); require(buffer.size() <= 16 * 1024 * 1024) }
                output.writeInt(2); text(key); output.writeInt(rows.size)
                rows.forEach { text(it.path); text(it.id); text(it.field); text(it.source); text(it.translation); text(it.stableId.orEmpty()) }
            }
            val payload = buffer.toByteArray()
            if (payload.size + 32 <= 16*1024*1024) {
                Files.newOutputStream(temporary).use { it.write(MessageDigest.getInstance("SHA-256").digest(payload)); it.write(payload) }
                AtomicFiles.move(temporary, file, true)
            }
        }
        return rows
    }
    fun prune() = runCatching {
        if (Files.isDirectory(root)) Files.newDirectoryStream(root).use { paths -> paths.forEach { path ->
            val name = path.fileName.toString()
            if (name.matches(Regex("[a-f0-9]{64}\\.(bin|tmp)")) && name.substringBefore('.') !in used) Files.deleteIfExists(path)
        } }
        if (Files.isDirectory(root)) Files.list(root).use { paths ->
            var bytes = 0L
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.bin")) }
                .sorted(compareByDescending<Path> { Files.getLastModifiedTime(it).toMillis() }).forEach {
                    bytes += Files.size(it)
                    if (bytes > 96L * 1024 * 1024) Files.deleteIfExists(it)
                }
        }
    }
}
