package com.example.limbuszhcn.container

import com.lody.virtual.helper.utils.DurableFile
import com.lody.virtual.helper.utils.PackageInstallGuard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ContainerPersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun interruptedWriteRetainsCommittedAccountBytes() {
        val target = folder.root.resolve("synthetic-accounts")
        DurableFile.write(target, byteArrayOf(1, 2, 3))
        assertThrows(IOException::class.java) {
            DurableFile.write(target, DurableFile.Writer { it.write(byteArrayOf(4)); throw IOException("injected full disk") })
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), DurableFile.read(target, 100))
        assertFalse(folder.root.resolve("synthetic-accounts.new").exists())
        DurableFile.write(target, byteArrayOf(5, 6))
        assertArrayEquals(byteArrayOf(5, 6), DurableFile.read(target, 100))
    }

    @Test fun orphanTransactionNeverReplacesCommittedAccounts() {
        val target = folder.root.resolve("synthetic-accounts")
        DurableFile.write(target, byteArrayOf(1, 2))
        Files.write(folder.root.resolve("synthetic-accounts.new").toPath(), byteArrayOf(9))
        assertArrayEquals(byteArrayOf(1, 2), DurableFile.read(target, 100))
        assertThrows(IOException::class.java) { DurableFile.read(target, 1) }
        Files.write(target.toPath(), byteArrayOf())
        assertThrows(IOException::class.java) { DurableFile.read(target, 100) }
    }

    @Test fun timeoutKeepsFenceUntilRealLateResultAndCompletesOnlyOnce() {
        val request = PackageInstallGuard.Pending<String>()
        try {
            assertThrows(IllegalStateException::class.java) { request.await(1) }
            assertFalse(request.isDone)
            assertThrows(IllegalStateException::class.java) { PackageInstallGuard.assertIdle(folder.root) }
            request.complete("installed")
            request.fail(IllegalStateException("late duplicate"))
            assertEquals("installed", request.await(1))
            PackageInstallGuard.assertIdle(folder.root)
        } finally { request.complete("cleanup") }
    }

    @Test fun binderDeathReleasesWaitAndFenceWithoutSuccess() {
        val request = PackageInstallGuard.Pending<String>()
        request.fail(IllegalStateException("service died"))
        assertEquals("service died", assertThrows(IllegalStateException::class.java) { request.await(100) }.message)
        PackageInstallGuard.assertIdle(folder.root)
    }

    @Test fun interruptedWaitRemainsFencedUntilCompletion() {
        val request = PackageInstallGuard.Pending<String>()
        try {
            Thread.currentThread().interrupt()
            assertThrows(IllegalStateException::class.java) { request.await(100) }
            assertTrue(Thread.interrupted())
            assertThrows(IllegalStateException::class.java) { PackageInstallGuard.assertIdle(folder.root) }
        } finally { Thread.interrupted(); request.complete("done") }
        PackageInstallGuard.assertIdle(folder.root)
    }

    @Test fun serverFileLockProtectsWorkAfterCallerStopsWaiting() {
        val pool = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val worker = pool.submit<String> {
                PackageInstallGuard.runServer(folder.root) {
                    started.countDown(); check(release.await(5, TimeUnit.SECONDS)); "finished"
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { PackageInstallGuard.assertIdle(folder.root) }
            release.countDown()
            assertEquals("finished", worker.get(5, TimeUnit.SECONDS))
            PackageInstallGuard.assertIdle(folder.root)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
