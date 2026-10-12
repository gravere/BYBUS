package com.example.limbuszhcn.storage

import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

internal object AtomicFiles {
    fun move(source: Path, target: Path, replace: Boolean = false) {
        val options = if (replace) arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else arrayOf(StandardCopyOption.ATOMIC_MOVE)
        try { Files.move(source, target, *options) }
        catch (_: AtomicMoveNotSupportedException) {
            if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) else Files.move(source, target)
        }
    }

    fun properties(target: Path, values: Properties) {
        Files.createDirectories(target.parent)
        val pending = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(pending.toFile()).use { values.store(it, null); it.fd.sync() }
            move(pending, target, true)
        } finally { Files.deleteIfExists(pending) }
    }

    fun readProperties(path: Path): Properties = Properties().apply {
        Files.newInputStream(path).use(::load)
    }

    /** Only disposable, explicitly owned descendants; never follow directory symlinks. */
    fun deleteOwnedTree(owner: Path, path: Path) {
        val root = owner.toAbsolutePath().normalize()
        val target = path.toAbsolutePath().normalize()
        require(target != root && target.startsWith(root)) { "Refusing deletion outside owned directory" }
        if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.isSymbolicLink(root) && !Files.isSymbolicLink(target)) { "Refusing linked cache directory" }
        require(target.parent.toRealPath().startsWith(root.toRealPath())) { "Refusing linked parent directory" }
        Files.walk(target).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    /** Recover a replacement interrupted between renames, then commit a fully staged directory. */
    fun replaceDirectory(staged: Path, target: Path) {
        val parent = target.parent
        require(staged.parent == parent && staged != target)
        val previous = target.resolveSibling(".${target.fileName}.previous")
        if (Files.exists(previous)) {
            if (!Files.exists(target)) move(previous, target) else deleteOwnedTree(parent, previous)
        }
        if (Files.exists(target)) move(target, previous)
        try { move(staged, target) }
        catch (error: Exception) {
            if (!Files.exists(target) && Files.exists(previous)) move(previous, target)
            throw error
        }
        // Leave the recovery copy until the next successful replacement; at most one copy.
    }
}
