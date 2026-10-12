package com.example.limbuszhcn.container

import com.example.limbuszhcn.storage.AtomicFiles
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Call only after every component has been installed and verified, under the operation lock. */
internal fun pruneUnusedComponentBundles(root: Path, referencedApks: List<Path>): Int {
    if (!Files.isDirectory(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return 0
    val realRoot = root.toRealPath()
    val references = referencedApks.map { it.toRealPath() }
    // An external/legacy configuration is not evidence that bundled generations are disposable.
    if (references.none { it.startsWith(realRoot) }) return 0
    var removed = 0
    Files.list(root).use { children ->
        children.filter { Files.isDirectory(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
            .filter { it.fileName.toString().matches(Regex("[0-9]+(?:[._-][A-Za-z0-9]+)*")) }
            .forEach { directory ->
                val real = directory.toRealPath()
                require(real != realRoot && real.startsWith(realRoot))
                if (references.none { it.startsWith(real) }) {
                    AtomicFiles.deleteOwnedTree(root, directory)
                    removed++
                }
            }
    }
    return removed
}
