package com.example.limbuszhcn.container

import com.example.limbuszhcn.storage.AtomicFiles
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ImportSnapshotReuseTest {
    @Test fun reimportSharesOnlyOwnedSnapshotAndPruningDoesNotRemoveCurrentBytes() {
        val root = Files.createTempDirectory("snapshot-reuse")
        val source = root.resolve("base.apk")
        Files.write(source, "unchanged official bytes".toByteArray())
        val runtime = LocalContainerRuntime(root.resolve("workspace"))
        val request = GameImportRequest(listOf(source.toString()), 475, "installed")
        runtime.importGame(request)
        val first = Path.of(runtime.importedSplitApkPaths().single())
        runtime.importGame(request)
        val second = Path.of(runtime.importedSplitApkPaths().single())
        assertTrue(Files.isSameFile(first, second))
        assertFalse(Files.isSameFile(source, second))
        Files.write(source, "new version".toByteArray())
        runtime.importGame(request.copy(versionCode = 476))
        assertFalse(Files.exists(first))
        assertEquals("unchanged official bytes", String(Files.readAllBytes(second)))
        assertEquals("new version", String(Files.readAllBytes(Path.of(runtime.importedSplitApkPaths().single()))))
    }

    @Test fun failedInstallKeepsOldSnapshotAndCompactionSkipsRepairTransaction() {
        val root = Files.createTempDirectory("snapshot-repair")
        val source = root.resolve("base.apk")
        Files.write(source, "old".toByteArray())
        val workspace = root.resolve("workspace")
        val runtime = LocalContainerRuntime(workspace)
        val request = GameImportRequest(listOf(source.toString()), 475, "installed")
        runtime.importGame(request)
        val first = Path.of(runtime.importedSplitApkPaths().single())
        assertTrue(runCatching { runtime.importGame(request) { error("installer failed") } }.isFailure)
        assertEquals(first.toString(), runtime.importedSplitApkPaths().single())
        assertEquals("old", String(Files.readAllBytes(first)))
        assertEquals(0L, runtime.compactSnapshots())
        assertTrue(runtime.status().requiresInstalledGameResync)
    }

    @Test fun migrationVerifiesBytesBeforeReplacingOldIndependentCopy() {
        val root = Files.createTempDirectory("snapshot-migration")
        val source = root.resolve("base.apk")
        Files.write(source, "same".toByteArray())
        val workspace = root.resolve("workspace")
        val runtime = LocalContainerRuntime(workspace)
        val request = GameImportRequest(listOf(source.toString()), 475, "installed")
        runtime.importGame(request)
        val first = Path.of(runtime.importedSplitApkPaths().single())
        runtime.importGame(request)
        val second = Path.of(runtime.importedSplitApkPaths().single())
        // Simulate snapshots created before this version, with two independent files.
        Files.delete(first); Files.copy(source, first)
        assertFalse(Files.isSameFile(first, second))
        assertEquals(4L, runtime.compactSnapshots())
        assertTrue(Files.isSameFile(first, second))
        assertEquals(0L, runtime.compactSnapshots())
        assertFalse(Files.isSameFile(source, second))
    }
}
