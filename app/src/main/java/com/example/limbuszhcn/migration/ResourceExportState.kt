package com.example.limbuszhcn.migration

import com.example.limbuszhcn.storage.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Tracks only a user-created output document, never a source resource path. */
internal class ResourceExportState(private val directory: Path) {
    private val marker = directory.resolve("pending-export.properties")
    fun begin(destination: String) {
        require(destination.startsWith("content://") && destination.length <= 4096)
        check(!Files.exists(marker)) { "上次迁出的文件尚未清理，请重新检测" }
        Files.createDirectories(directory)
        AtomicFiles.properties(marker, Properties().apply { setProperty("destination", destination) })
    }
    fun complete() { Files.deleteIfExists(marker) }
    fun recover(deleteDocument: (String) -> Unit) {
        if (!Files.exists(marker)) return
        require(Files.size(marker) in 1..8192) { "迁出记录损坏，请重新检测" }
        val destination = AtomicFiles.readProperties(marker).getProperty("destination", "")
        require(destination.startsWith("content://") && destination.length <= 4096) { "迁出记录无效" }
        deleteDocument(destination) // Keep the marker if the provider cannot finish cleanup.
        complete()
    }
}
