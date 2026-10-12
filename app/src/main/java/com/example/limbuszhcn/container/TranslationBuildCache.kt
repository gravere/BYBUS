package com.example.limbuszhcn.container

import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.update.toLowerHex
import com.example.limbuszhcn.update.sha256
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.Properties

/** Cache of expensive JSON pairing, bound to content and a separately verified active index. */
internal class TranslationBuildCache(private val receipt: Path) {
    data class Input(val translation: Path, val originalPath: String)

    fun fingerprint(identity: String, inputs: List<Input>, storage: GameStorage,
                    fileDigest: (Path) -> String = { it.sha256() }): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0) }
        // Bump when JSON pairing/text policy changes, independently of the native file format.
        field(TRANSLATION_COMPILER_REVISION); field(identity)
        inputs.sortedBy { it.originalPath }.forEach { input ->
            field(input.originalPath)
            field(fileDigest(input.translation))
            val original = storage.read(input.originalPath)
            field(if (original == null) "missing" else MessageDigest.getInstance("SHA-256").digest(original).toLowerHex())
        }
        return digest.digest().toLowerHex()
    }

    fun matches(fingerprint: String, active: Properties?): Boolean {
        if (active == null) return false
        return runCatching {
            val saved = Properties().apply { Files.newInputStream(receipt).use(::load) }
            saved.getProperty("fingerprint") == fingerprint &&
                INDEX_KEYS.all { key -> active.getProperty(key) != null && saved.getProperty(key) == active.getProperty(key) }
        }.getOrDefault(false)
    }

    fun save(fingerprint: String, active: Properties) {
        Files.createDirectories(receipt.parent)
        val temp = receipt.resolveSibling(receipt.fileName.toString() + ".tmp")
        val values = Properties().apply {
            setProperty("fingerprint", fingerprint)
            INDEX_KEYS.forEach { key -> setProperty(key, requireNotNull(active.getProperty(key))) }
        }
        Files.newOutputStream(temp).use { values.store(it, "Verified translation build inputs") }
        try {
            Files.move(temp, receipt, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, receipt, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private val INDEX_KEYS = listOf("schema", "version", "indexSha256", "inputSha256")
    }
}
