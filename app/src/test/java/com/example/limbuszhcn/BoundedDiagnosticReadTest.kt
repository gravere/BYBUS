package com.example.limbuszhcn

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class BoundedDiagnosticReadTest {
    private open class Child(
        private val input: InputStream = ByteArrayInputStream("ready\n".toByteArray()),
        private val exit: Int = 0
    ) : Process() {
        val destroyed = CountDownLatch(1)
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = exit
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = exit
        override fun destroy() { destroyed.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    private fun awaitFree(reader: BoundedDiagnosticRead) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        do {
            if (reader.read({ throw IllegalStateException("intentional") }) { emptyList() }.status != "busy") return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Reader did not release its occupied gate")
    }

    @Test fun includesStalledStartInDeadlineAndCleansLateChild() {
        val reader = BoundedDiagnosticRead(100)
        val release = CountDownLatch(1)
        val child = Child()
        val starts = AtomicInteger()
        try {
            val before = System.nanoTime()
            val result = reader.read({ starts.incrementAndGet(); release.await(); child }) { it.toList() }
            assertEquals("timeout", result.status)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1500)
            repeat(8) {
                assertEquals("busy", reader.read({ starts.incrementAndGet(); Child() }) { it.toList() }.status)
            }
            assertEquals(1, starts.get())
        } finally { release.countDown() }
        assertTrue(child.destroyed.await(2, TimeUnit.SECONDS))
        awaitFree(reader)
    }

    @Test fun blockedReadIsDestroyedOffCallerAndCanRecover() {
        val releaseRead = CountDownLatch(1)
        val child = object : Child(object : InputStream() {
            override fun read(): Int { releaseRead.await(); return -1 }
        }) {
            override fun destroy() { releaseRead.countDown(); super.destroy() }
        }
        val reader = BoundedDiagnosticRead(100)
        try {
            assertEquals("timeout", reader.read({ child }) { it.toList() }.status)
            assertTrue(child.destroyed.await(2, TimeUnit.SECONDS))
            awaitFree(reader)
        } finally { releaseRead.countDown() }
    }

    @Test fun blockedDestroyDoesNotDelayCallerOrSpawnMoreCommands() {
        val allowDestroy = CountDownLatch(1)
        val child = object : Child() {
            override fun destroy() { allowDestroy.await(); super.destroy() }
        }
        val reader = BoundedDiagnosticRead(100)
        try {
            val before = System.nanoTime()
            val result = reader.read({ child }) { it.toList() }
            assertEquals(listOf("ready"), result.lines)
            assertEquals("available", result.status)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1500)
            assertEquals("busy", reader.read({ error("must not start") }) { it.toList() }.status)
        } finally { allowDestroy.countDown() }
        assertTrue(child.destroyed.await(2, TimeUnit.SECONDS))
        awaitFree(reader)
    }

    @Test fun keepsNonzeroExitDistinctFromSuccessfulRead() {
        val reader = BoundedDiagnosticRead()
        val result = reader.read({ Child(exit = 1) }) { it.toList() }
        assertEquals("incomplete", result.status)
        assertEquals(listOf("ready"), result.lines)
        awaitFree(reader)
    }

    @Test fun failedStartReleasesGateWithoutNeedingAProcess() {
        val reader = BoundedDiagnosticRead()
        assertEquals("unavailable", reader.read({ throw java.io.IOException() }) { it.toList() }.status)
        awaitFree(reader)
        assertEquals("available", reader.read({ Child() }) { it.toList() }.status)
    }

    @Test fun sequentialSuccessfulReadsWaitForNormalCleanupWithoutFalseBusy() {
        val reader = BoundedDiagnosticRead(1_000)
        repeat(3) {
            val child = object : Child() {
                override fun destroy() { Thread.sleep(25); super.destroy() }
            }
            assertEquals("available", reader.read({ child }) { it.toList() }.status)
        }
    }

    @Test fun failedTerminationCannotAccumulateUnreapedChildren() {
        val exited = CountDownLatch(1)
        val child = object : Child() {
            override fun destroy() { throw SecurityException("fixture: termination rejected") }
            override fun waitFor(timeout: Long, unit: TimeUnit) = false
            override fun waitFor(): Int { exited.await(); return 9 }
        }
        val reader = BoundedDiagnosticRead(100)
        try {
            assertEquals("incomplete", reader.read({ child }) { it.toList() }.status)
            assertEquals("busy", reader.read({ error("must not spawn another child") }) { it.toList() }.status)
        } finally { exited.countDown() }
        awaitFree(reader)
        assertEquals("available", reader.read({ Child() }) { it.toList() }.status)
    }
}
