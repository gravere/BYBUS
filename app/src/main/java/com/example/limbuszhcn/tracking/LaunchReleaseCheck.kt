package com.example.limbuszhcn.tracking

import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One metadata-only request may outlive the launch's network budget. Never queues duplicates. */
internal class LaunchReleaseCheck(
    private val executor: Executor,
    private val fetch: () -> String,
    private val nanos: () -> Long = System::nanoTime
) {
    private var inFlight: FutureTask<String>? = null

    @Synchronized fun start(budgetMillis: Long): Pending {
        require(budgetMillis > 0)
        val deadline = nanos() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        val task = inFlight?.takeUnless { it.isDone } ?: FutureTask(fetch).also {
            inFlight = it
            executor.execute(it)
        }
        return Pending(task, deadline, nanos)
    }

    class Pending internal constructor(
        private val task: FutureTask<String>, private val deadline: Long, private val nanos: () -> Long
    ) {
        fun await(): String = try {
            // Work already completed during local preparation remains usable after the deadline.
            task.get((deadline - nanos()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            // Do not cancel the shared tracker request; only metadata can be published later.
            throw SocketTimeoutException("汉化更新检查超时，改为校验本地汉化")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("启动检查已取消").apply { initCause(error) }
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
}
