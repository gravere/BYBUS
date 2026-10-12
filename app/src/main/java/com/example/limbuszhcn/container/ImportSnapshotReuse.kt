package com.example.limbuszhcn.container

import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.update.sha256
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

/** Only committed host-owned APK snapshots may share an inode. Never link system APKs or writable game data. */
internal class ImportSnapshotReuse(private val workspace: Path) {
    private val root = workspace.resolve("imports")
    private val marker = workspace.resolve("snapshot-reuse.properties")
    private data class Entry(val path: Path, val hash: String)

    private fun entries(metadata: Properties?): List<Entry> {
        if (metadata == null || Files.exists(workspace.resolve("import-repair.properties"))) return emptyList()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return emptyList()
        return listOfNotNull(metadata.getProperty("generation"), metadata.getProperty("previousGeneration")).distinct().flatMap { id ->
            if (!id.matches(Regex("[0-9a-f-]{36}"))) return@flatMap emptyList()
            val generation = root.resolve(id)
            val splits = generation.resolve("splits")
            if (!Files.isDirectory(generation, NOFOLLOW_LINKS) || !Files.isDirectory(splits, NOFOLLOW_LINKS) ||
                !splits.toRealPath().startsWith(root.toRealPath())) return@flatMap emptyList()
            val props = runCatching { AtomicFiles.readProperties(generation.resolve("snapshot.properties")) }.getOrNull()
                ?: return@flatMap emptyList()
            val count = props.getProperty("importedSplits")?.toIntOrNull() ?: return@flatMap emptyList()
            if (count !in 1..32 || props.getProperty("generation") != id) return@flatMap emptyList()
            (0 until count).mapNotNull { index ->
                val name = props.getProperty("apk.$index") ?: return@mapNotNull null
                val hash = props.getProperty("sha256.$index") ?: return@mapNotNull null
                if (!name.matches(Regex("[A-Za-z0-9_.-]+\\.apk")) || !hash.matches(Regex("[0-9a-f]{64}"))) return@mapNotNull null
                splits.resolve(name).takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }?.let { Entry(it, hash) }
            }
        }
    }

    fun copyOrLink(source: Path, target: Path, hash: String, metadata: Properties?) {
        val old = entries(metadata).firstOrNull { it.hash == hash && Files.size(it.path) == Files.size(source) && it.path.sha256() == hash }
        if (old != null && runCatching { Files.createLink(target, old.path) }.isSuccess) return
        Files.copy(source, target)
    }

    /** One migration per committed pair; unchanged launch paths do not rehash gigabytes. */
    fun compact(metadata: Properties?): Long {
        if (metadata == null || Files.exists(workspace.resolve("import-repair.properties"))) return 0
        val key = listOf(metadata.getProperty("generation"), metadata.getProperty("previousGeneration")).joinToString("/")
        if (runCatching { AtomicFiles.readProperties(marker).getProperty("pair") }.getOrNull() == key) return 0
        var saved = 0L
        entries(metadata).groupBy { it.hash }.values.filter { it.size > 1 }.forEach { matches ->
            val first = matches.first()
            if (first.path.sha256() != first.hash) return@forEach
            matches.drop(1).forEach { duplicate ->
                if (Files.isSameFile(first.path, duplicate.path) || Files.size(first.path) != Files.size(duplicate.path) ||
                    duplicate.path.sha256() != first.hash) return@forEach
                val temporary = duplicate.path.resolveSibling(".${UUID.randomUUID()}.link")
                try {
                    Files.createLink(temporary, first.path)
                    AtomicFiles.move(temporary, duplicate.path, replace = true)
                    saved += Files.size(first.path)
                } catch (_: java.io.IOException) {
                    // Unsupported filesystem: retain the complete, verified independent copies.
                } catch (_: UnsupportedOperationException) {
                } finally { Files.deleteIfExists(temporary) }
            }
        }
        AtomicFiles.properties(marker, Properties().apply { setProperty("pair", key); setProperty("savedBytes", saved.toString()) })
        return saved
    }
}
