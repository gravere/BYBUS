package com.example.limbuszhcn.migration

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.gamefs.GameResourceTransfer
import com.example.limbuszhcn.gamefs.ResourceArchiveWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import org.junit.Assert.*
import org.junit.Test

class RootResourceImportTest {
    @Test fun sourceIsRestrictedToTheSameUserAndOfficialGame() {
        RootResourcePaths.validateDataDirectory(0, "/data/data/${RootResourcePaths.GAME}")
        RootResourcePaths.validateDataDirectory(10, "/data/user/10/${RootResourcePaths.GAME}")
        RootResourcePaths.validateDataDirectory(10, "/mnt/expand/abcd-1234/user/10/${RootResourcePaths.GAME}")
        for (bad in listOf("/data/user/0/${RootResourcePaths.GAME}", "/data/user/10/com.google.android.gms",
            "/data/user/10/${RootResourcePaths.GAME}/../accounts", "/data/data/${RootResourcePaths.GAME}")) {
            assertThrows(IllegalArgumentException::class.java) { RootResourcePaths.validateDataDirectory(10, bad) }
        }
    }

    @Test fun commandQuotesApkPathAndRejectsCommandInjectionInSource() {
        val command = RootResourcePaths.command("/data/app/a'b/base.apk", 0, 500, "/data/user/0/${RootResourcePaths.GAME}")
        assertTrue(command.startsWith("CLASSPATH='/data/app/a'\\''b/base.apk' "))
        assertThrows(IllegalArgumentException::class.java) {
            RootResourcePaths.command("/data/app/base.apk", 0, 500, "/data/user/0/${RootResourcePaths.GAME}; id")
        }
        assertThrows(IllegalArgumentException::class.java) { RootResourcePaths.quote("bad\ncommand") }
    }

    @Test fun reminderIsOnlyForUnacknowledgedRootLaunches() {
        assertTrue(RootImportReminder.shouldOffer(false, true, false))
        assertFalse(RootImportReminder.shouldOffer(true, true, false))
        assertFalse(RootImportReminder.shouldOffer(false, false, false))
        assertFalse(RootImportReminder.shouldOffer(false, true, true))
    }

    @Test fun rootArchiveReusesMigrationFormatAndDoesNotOverwriteExistingResources() {
        val source = Files.createTempDirectory("root-import-source")
        val bundle = source.resolve("bundle").also { Files.write(it, ByteArray(300_000) { 7 }) }
        Files.write(source.resolve("PlayerPrefs"), "secret".toByteArray())
        val out = ByteArrayOutputStream()
        ResourceArchiveWriter(mapOf("assets" to source), 500).write(out)
        val target = Files.createTempDirectory("root-import-target")
        val assets = Files.createDirectory(target.resolve("assets"))
        val existing = assets.resolve("bundle").also { Files.write(it, byteArrayOf(3)) }
        val transfer = GameResourceTransfer(ContainerWorkspaceStorage(assets, mapOf("assets" to assets)), target.resolve("stage"), 500)
        var sourceVerified = false
        val result = transfer.importResources(ByteArrayInputStream(out.toByteArray())) { sourceVerified = true }
        assertTrue(sourceVerified)
        assertEquals(1, result.keptExisting)
        assertArrayEquals(byteArrayOf(3), Files.readAllBytes(existing))
        assertEquals(300_000L, Files.size(bundle))
        assertFalse(Files.exists(assets.resolve("PlayerPrefs")))
    }

    @Test fun failedRootProcessCannotCommitEvenAValidArchive() {
        val source = Files.createTempDirectory("root-import-source")
        Files.write(source.resolve("bundle"), byteArrayOf(1, 2, 3))
        val out = ByteArrayOutputStream()
        ResourceArchiveWriter(mapOf("assets" to source), 500).write(out)
        val target = Files.createTempDirectory("root-import-target")
        val assets = target.resolve("assets")
        val transfer = GameResourceTransfer(ContainerWorkspaceStorage(assets, mapOf("assets" to assets)), target.resolve("stage"), 500)
        assertThrows(IllegalStateException::class.java) {
            transfer.importResources(ByteArrayInputStream(out.toByteArray())) { error("root process failed") }
        }
        assertFalse(Files.exists(assets.resolve("bundle")))
    }

    @Test fun processPipeIsDrainedBeforeExitValidationAndClose() {
        val source = Files.createTempDirectory("root-import-pipe")
        Files.write(source.resolve("bundle"), byteArrayOf(1, 2, 3))
        val out = ByteArrayOutputStream()
        ResourceArchiveWriter(mapOf("assets" to source), 500).write(out)
        var eof = false
        var closed = false
        val pipe = object : java.io.FilterInputStream(ByteArrayInputStream(out.toByteArray())) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                `in`.read(b, off, minOf(17, len)).also { if (it == -1) eof = true }
            override fun close() { closed = true; super.close() }
        }
        val target = Files.createTempDirectory("root-import-pipe-target")
        val assets = target.resolve("assets")
        val transfer = GameResourceTransfer(ContainerWorkspaceStorage(assets, mapOf("assets" to assets)), target.resolve("stage"), 500)
        transfer.importResources(pipe) {
            assertTrue("Producer must finish its central directory before waiting for its exit", eof)
            assertFalse("Closing stdout early can terminate the root helper with SIGPIPE", closed)
        }
        assertTrue(closed)
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(assets.resolve("bundle")))
    }

    @Test fun sourceMutationWhileCopyingCannotProduceACommitManifest() {
        val source = Files.createTempDirectory("root-import-changing")
        val bundle = source.resolve("bundle")
        Files.write(bundle, ByteArray(500_000))
        assertThrows(IllegalArgumentException::class.java) {
            ResourceArchiveWriter(mapOf("assets" to source), 500, progress = {
                Files.setLastModifiedTime(bundle, FileTime.fromMillis(1000))
            }).write(ByteArrayOutputStream())
        }
    }
}
