package com.example.limbuszhcn.translation

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class AiRequestTimeout(val total: Boolean) : AiTranslationException(
    if (total) "本次翻译等待过久，已暂停。已完成的译文保留，可换 API 后继续。"
    else "模型长时间没有返回内容，已暂停。已完成的译文保留，可换 API 后继续。")

/** Heartbeats do not reset this timer. The watchdog closes this request only; it never retries. */
internal class AiRequestDeadline(private val disconnect: () -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val idleMillis: Long = 120_000, private val totalMillis: Long = 300_000) : AutoCloseable {
    private val started = clock()
    private val lastContent = AtomicLong(started)
    private val characters = AtomicLong(0)
    private val state = AtomicReference<String?>(null)
    private val timer = deadlines.scheduleWithFixedDelay(::tick, 100, 100, TimeUnit.MILLISECONDS)
    fun progress(progress: AiResponseProgress) {
        val prior = characters.getAndUpdate { maxOf(it, progress.characters) }
        if (progress.characters > prior) lastContent.set(clock())
    }
    internal fun tick() {
        val now = clock()
        val cause = when { now - started >= totalMillis -> "total"; now - lastContent.get() >= idleMillis -> "idle"; else -> return }
        if (state.compareAndSet(null, cause)) runCatching(disconnect)
    }
    fun check() { when (state.get()) { "total" -> throw AiRequestTimeout(true); "idle" -> throw AiRequestTimeout(false) } }
    fun complete() { state.compareAndSet(null, "complete"); check() }
    override fun close() { state.compareAndSet(null, "closed"); timer.cancel(false) }
    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(2) { task -> Thread(task, "bybus-ai-deadline").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }
}
