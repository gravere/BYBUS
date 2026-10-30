package com.example.limbuszhcn.container

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ImportSnapshotRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun count(root: Path): Long = Files.list(root.resolve("imports")).use { it.count() }

    @Test fun repeatedFailuresReuseOneVerifiedCandidateThenCommitWithoutTouchingData() {
        val root = temporary.newFolder().toPath()
        val source = temporary.newFile("base.apk").toPath().also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val data = root.resolve("game-data-sentinel").also { Files.write(it, byteArrayOf(9)) }
        val runtime = LocalContainerRuntime(root)
        val request = GameImportRequest(listOf(source.toString()), 1, "installed")
        val attempts = mutableListOf<String>()
        repeat(2) {
            assertThrows(IllegalStateException::class.java) { runtime.importGame(request) {
                attempts += it.splitApkPaths.single(); error("injected install failure")
            } }
            assertEquals(1, count(root))
        }
        assertEquals(attempts[0], attempts[1])
        runtime.importGame(request)
        assertEquals(attempts[0], runtime.importedSplitApkPaths().single())
        assertFalse(runtime.status().requiresInstalledGameResync)
        assertArrayEquals(byteArrayOf(9), Files.readAllBytes(data))
    }

    @Test fun differentRetryPreservesLiveReferenceAndReclaimsItOnlyAfterItIsUnused() {
        val root = temporary.newFolder().toPath()
        val source = temporary.newFile("base.apk").toPath().also { Files.write(it, byteArrayOf(1)) }
        val runtime = LocalContainerRuntime(root)
        val request = GameImportRequest(listOf(source.toString()), 1, "installed")
        var live = emptyList<String>()
        assertThrows(IllegalStateException::class.java) { runtime.importGame(request, { live }) {
            live = it.splitApkPaths; error("first failure after package registration")
        } }
        val original = Path.of(live.single())
        Files.write(source, byteArrayOf(2))
        var next = emptyList<String>()
        assertThrows(IllegalStateException::class.java) { runtime.importGame(request.copy(versionCode = 2), { live }) {
            next = it.splitApkPaths; error("second failure")
        } }
        assertEquals(2, count(root)); assertArrayEquals(byteArrayOf(1), Files.readAllBytes(original))
        live = next
        runtime.importGame(request.copy(versionCode = 2), { live }) { }
        assertEquals(1, count(root)); assertFalse(Files.exists(original))
        assertArrayEquals(byteArrayOf(2), Files.readAllBytes(Path.of(runtime.importedSplitApkPaths().single())))
    }

    @Test fun corruptedCandidateIsNotReusedAndFailedReferenceQueryDoesNotCleanAnything() {
        val root = temporary.newFolder().toPath()
        val source = temporary.newFile("base.apk").toPath().also { Files.write(it, byteArrayOf(1)) }
        val runtime = LocalContainerRuntime(root)
        val request = GameImportRequest(listOf(source.toString()), 1, "installed")
        var live = emptyList<String>()
        assertThrows(IllegalStateException::class.java) { runtime.importGame(request) {
            live = it.splitApkPaths; error("injected failure")
        } }
        val broken = Path.of(live.single()); Files.write(broken, byteArrayOf(8))
        assertThrows(IllegalStateException::class.java) { runtime.importGame(request, { error("IPC unavailable") }) { } }
        assertEquals(1, count(root)); assertTrue(Files.exists(broken))
        runtime.importGame(request, { live }) { assertNotEquals(broken.toString(), it.splitApkPaths.single()) }
        assertTrue(Files.exists(broken))
        assertArrayEquals(byteArrayOf(1), Files.readAllBytes(Path.of(runtime.importedSplitApkPaths().single())))
    }
}
