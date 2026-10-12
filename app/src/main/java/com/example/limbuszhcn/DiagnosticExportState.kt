package com.example.limbuszhcn

import com.example.limbuszhcn.storage.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal data class PendingDiagnosticExport(val archive: Path, val destination: String? = null,
    val cancelled: Boolean = false)

/** Private durable hand-off to DocumentsUI; never accepts a source path from an Intent. */
internal class DiagnosticExportState(private val directory: Path) {
    private val marker = directory.resolve("pending-export.properties")

    @Synchronized fun read(): PendingDiagnosticExport? {
        if (!Files.exists(marker)) return null
        require(Files.size(marker) in 1..8192) { "诊断导出记录损坏，请重新生成" }
        val values = AtomicFiles.readProperties(marker)
        val name = values.getProperty("archive", "")
        require(name.matches(Regex("limbus-diagnostics-[0-9-]+\\.zip"))) { "诊断导出记录损坏，请重新生成" }
        val archive = directory.resolve(name)
        require(!Files.isSymbolicLink(archive) && Files.isRegularFile(archive) && Files.size(archive) > 0 &&
            archive.toRealPath().parent == directory.toRealPath()) { "诊断包已丢失，请重新生成" }
        val phase = values.getProperty("phase")
        require(phase in setOf("choose", "save", "cancel")) { "诊断导出记录损坏，请重新生成" }
        val destination = values.getProperty("destination")?.takeIf { it.startsWith("content://") && it.length <= 4096 }
        require(phase != "save" || destination != null) { "诊断保存位置失效，请重新选择" }
        return PendingDiagnosticExport(archive, destination.takeIf { phase == "save" }, phase == "cancel")
    }

    @Synchronized fun begin(archive: Path) {
        require(archive.toRealPath().parent == directory.toRealPath()) { "诊断包不在专用目录中" }
        write(PendingDiagnosticExport(archive))
    }

    @Synchronized fun selected(destination: String?) {
        val pending = requireNotNull(read()) { "诊断包已丢失，请重新生成" }
        require(destination == null || (destination.startsWith("content://") && destination.length <= 4096)) {
            "无法使用所选保存位置"
        }
        write(pending.copy(destination = destination, cancelled = destination == null))
    }

    @Synchronized fun retry() { read()?.let { write(PendingDiagnosticExport(it.archive)) } }

    @Synchronized fun complete() {
        val pending = read()
        // Remove the hand-off first. A crash during cleanup cannot leave an invalid pending save.
        discardMarker()
        pending?.let { Files.deleteIfExists(it.archive) }
    }

    @Synchronized fun discardMarker() { Files.deleteIfExists(marker) }

    private fun write(pending: PendingDiagnosticExport) = AtomicFiles.properties(marker, Properties().apply {
        setProperty("archive", pending.archive.fileName.toString())
        setProperty("phase", when { pending.cancelled -> "cancel"; pending.destination != null -> "save"; else -> "choose" })
        pending.destination?.let { setProperty("destination", it) }
    })
}
