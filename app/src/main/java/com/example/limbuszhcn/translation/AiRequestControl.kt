package com.example.limbuszhcn.translation

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal class AiRequestCancelled(val sent: Boolean) : CancellationException("翻译已停止，已保存的进度保留")

/** Cancels only this run's connection. Never retries or clears a saved response. */
internal class AiRequestControl {
    private val stopped = AtomicBoolean(false)
    private val lock = Any()
    private var disconnect: (() -> Unit)? = null
    val isStopped get() = stopped.get()

    fun check() { if (isStopped || Thread.currentThread().isInterrupted) throw AiRequestCancelled(false) }
    fun attach(close: () -> Unit): AutoCloseable {
        synchronized(lock) { check(); disconnect = close }
        return AutoCloseable { synchronized(lock) { if (disconnect === close) disconnect = null } }
    }
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        val close = synchronized(lock) { disconnect }
        if (close != null) Thread({ runCatching(close) }, "bybus-ai-stop").apply { isDaemon = true; start() }
    }
}

/** The elapsed-realtime anchor is deliberately not persisted in task JSON. */
internal fun AiProgress.elapsedAt(now: Long, running: Boolean = true): Long = elapsedMillis +
    if (running && sampledAt > 0) (now - sampledAt).coerceAtLeast(0) else 0

internal fun AiProgress.freezeAt(now: Long): AiProgress = copy(elapsedMillis = elapsedAt(now), sampledAt = 0)
