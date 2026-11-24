package com.example.limbuszhcn

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounds start/read/exit together; a stuck command cannot accumulate workers or children. */
internal class BoundedDiagnosticRead(private val timeoutMillis: Long = 2_000L) {
    data class Result(val lines: List<String> = emptyList(), val status: String, val exitCode: Int? = null)

    private val occupied = AtomicBoolean()
    private val reader = executor("bybus-log-read")
    private val cleaner = executor("bybus-log-cleanup")

    fun read(start: () -> java.lang.Process, parse: (Sequence<String>) -> List<String>): Result {
        if (!occupied.compareAndSet(false, true)) return Result(status = "busy")
        val startedAt = System.nanoTime()
        val session = Session(start, parse)
        val task = FutureTask { session.run() }
        try {
            reader.execute(task)
        } catch (_: RejectedExecutionException) {
            occupied.set(false)
            return Result(status = "unavailable")
        }
        return try {
            val result = task.get(timeoutMillis, TimeUnit.MILLISECONDS)
            // A successful read is commonly followed by a second query. Wait for ordinary
            // cleanup within the same deadline rather than racing it and returning false busy.
            val remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - startedAt)
            if (remaining > 0) session.awaitCleanup(remaining)
            result
        } catch (_: TimeoutException) {
            session.expire()
            Result(status = "timeout")
        } catch (_: InterruptedException) {
            session.expire()
            Thread.currentThread().interrupt()
            Result(status = "unavailable")
        } catch (_: Exception) {
            session.expire()
            Result(status = "unavailable")
        }
    }

    private inner class Session(
        val start: () -> java.lang.Process,
        val parse: (Sequence<String>) -> List<String>
    ) {
        private val process = AtomicReference<java.lang.Process?>()
        private val expired = AtomicBoolean()
        private val readDone = AtomicBoolean()
        private val cleanDone = AtomicBoolean()
        private val cleanupScheduled = AtomicBoolean()
        private val released = AtomicBoolean()
        private val finished = CountDownLatch(1)

        fun awaitCleanup(nanos: Long) { finished.await(nanos, TimeUnit.NANOSECONDS) }

        fun run(): Result = try {
            if (expired.get()) Result(status = "timeout") else {
                val child = start()
                process.set(child)
                if (expired.get()) Result(status = "timeout") else {
                    val lines = child.inputStream.bufferedReader().useLines(parse)
                    val exitCode = if (child.waitFor(100, TimeUnit.MILLISECONDS)) child.exitValue() else null
                    Result(lines, if (exitCode == 0) "available" else "incomplete", exitCode)
                }
            }
        } catch (_: Exception) {
            Result(status = "unavailable")
        } finally {
            readDone.set(true)
            if (process.get() == null) cleanDone.set(true) else requestCleanup()
            releaseIfFinished()
        }

        fun expire() {
            expired.set(true)
            // Do not cancel a not-yet-started FutureTask: its finally must still release the gate.
            // If start() is stuck, its eventual return will publish and clean up the late child.
            requestCleanup()
        }

        private fun requestCleanup() {
            val child = process.get() ?: return
            if (!cleanupScheduled.compareAndSet(false, true)) return
            try {
                cleaner.execute {
                    try {
                        runCatching { child.destroyForcibly() }
                        runCatching { child.inputStream.close() }
                        runCatching { child.outputStream.close() }
                        runCatching { child.errorStream.close() }
                        // destroyForcibly may fail or only request termination. Keep the gate
                        // until the child is actually reaped; this wait is off the caller thread.
                        if (runCatching { child.waitFor() }.isSuccess) cleanDone.set(true)
                    } finally {
                        releaseIfFinished()
                    }
                }
            } catch (_: RejectedExecutionException) {
                // Never perform potentially blocking cleanup on the caller. Retain the gate:
                // another diagnostic command must not start while this child remains unresolved.
            }
        }

        private fun releaseIfFinished() {
            if (readDone.get() && cleanDone.get() && released.compareAndSet(false, true)) {
                occupied.set(false)
                finished.countDown()
            }
        }
    }

    private fun executor(name: String) = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
}
