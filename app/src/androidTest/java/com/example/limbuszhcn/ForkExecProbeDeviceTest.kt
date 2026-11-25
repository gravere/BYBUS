package com.example.limbuszhcn

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lody.virtual.client.NativeEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Disposable emulator process only; no game or account is used. */
@RunWith(AndroidJUnit4::class)
class ForkExecProbeDeviceTest {
    @Test fun observesOnlyTheLaunchingThreadsDirectChild() {
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tid = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread({
            var child: java.lang.Process? = null
            try {
                tid.set(Process.myTid())
                child = ProcessBuilder("/system/bin/sleep", "10").start()
                ready.countDown()
                release.await(10, TimeUnit.SECONDS)
            } catch (error: Throwable) { failure.set(error); ready.countDown() }
            finally { child?.destroyForcibly()?.waitFor() }
        }, "UnityMain")
        worker.start()
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError(it) }
            val report = forkExecKernelSnapshot(Process.myPid(), listOf(tid.get())).joinToString("\n")
            assertTrue(report, report.contains("thread=unity tid=${tid.get()}"))
            val kernelChildren = runCatching {
                java.io.File("/proc/${Process.myPid()}/task/${tid.get()}/children").readText()
            }.getOrNull()
            if (kernelChildren == null) {
                assertTrue(report, report.contains("thread=unity children=unavailable"))
                assertFalse(report.contains("children=0"))
                println("Kernel denies children/syscall inspection: unavailable is preserved; own-thread wait channel remains visible")
            } else {
                assertTrue(report, report.contains("thread=unity children=1"))
                assertTrue(report, report.contains("childPid="))
            }
            assertFalse(report.contains("/system/bin/sleep"))
        } finally { release.countDown(); worker.join(12_000) }
        assertFalse(worker.isAlive)
    }

    @Test fun nativeSnapshotIsNumericAndBounded() {
        val result = NativeEngine.nativeReadExecLaunchTrace()
        assertTrue(result.size <= 48 && result.size % 3 == 0)
    }
}
