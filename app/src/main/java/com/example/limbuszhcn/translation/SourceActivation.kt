package com.example.limbuszhcn.translation

import com.example.limbuszhcn.storage.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Small durable rollback journal; immutable index generations and game data are never copied. */
internal class SourceActivation(private val cache: Path) {
    private val root = cache.resolve("translation-index")
    private val backup = cache.resolve(".source-switch")
    private val owner = root.resolve("source.id")
    fun owner(): String = if (Files.isRegularFile(owner)) owner.readText().trim() else "zero"

    fun recover() {
        if (!Files.isRegularFile(backup.resolve("ready"))) {
            if (Files.exists(backup)) AtomicFiles.deleteOwnedTree(cache, backup)
            return
        }
        Files.createDirectories(root)
        for (name in FILES) {
            val saved = backup.resolve(name); val target = root.resolve(name)
            if (Files.exists(saved)) Files.copy(saved, target, REPLACE_EXISTING) else Files.deleteIfExists(target)
        }
        AtomicFiles.deleteOwnedTree(cache, backup)
    }

    /** Game must be stopped and runtime deactivated first; source packages remain recoverable. */
    fun discardDerived() {
        recover()
        for (name in FILES + "previous-index.path") Files.deleteIfExists(root.resolve(name))
        AtomicFiles.deleteOwnedTree(cache, root.resolve("versions"))
    }

    fun <T> run(id: String, revision: String? = null, action: () -> T): T {
        recover()
        val revisionFile = root.resolve("source.revision")
        if (id == owner() && (revision == null ||
                (Files.isRegularFile(revisionFile) && revisionFile.readText() == revision))) return action()
        require(id == "zero" || TranslationSources.validId(id))
        Files.createDirectories(root); Files.createDirectories(backup)
        for (name in FILES) {
            val source = root.resolve(name)
            if (Files.isRegularFile(source)) {
                require(Files.size(source) <= 128L * 1024 * 1024) { "旧汉化状态超出大小限制" }
                Files.copy(source, backup.resolve(name), REPLACE_EXISTING)
            }
        }
        backup.resolve("ready").writeText("rollback")
        try {
            for (name in FILES) Files.deleteIfExists(root.resolve(name))
            val result = action()
            TranslationSources.writeAtomic(owner, id)
            if (revision != null) TranslationSources.writeAtomic(revisionFile, revision)
            Files.delete(backup.resolve("ready")) // Commit; leftovers can now be discarded.
            runCatching { AtomicFiles.deleteOwnedTree(cache, backup) }
            return result
        } catch (e: Exception) { recover(); throw e }
    }

    companion object { private val FILES = listOf("source.id", "source.revision", "active-index.path", "bootstrap-index.path",
        "runtime-index.path", "build-receipt.properties", "live-resources.json", "live-fonts.bin") }
}
