package com.example.limbuszhcn

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Numeric, own-process observations only. No foreground service or wake lock. */
internal object GameMemoryDiagnostics {
    private var started = false

    @Synchronized
    fun start(context: Context, guest: Application) {
        if (started) return
        val since = SystemClock.elapsedRealtime()
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "bybus-memory").apply { isDaemon = true }
        }
        val pendingTrim = AtomicBoolean()
        val trimLevel = AtomicInteger()
        var lastNetwork = ""
        fun sample(cause: String) {
            runCatching {
                PersistentDiagnosticLog.info("GameMemory",
                    "cause=$cause ageMs=${SystemClock.elapsedRealtime() - since} ${snapshot(context)}")
                val network = gameNetworkSnapshot(context)
                if (network != lastNetwork) {
                    lastNetwork = network
                    PersistentDiagnosticLog.info("GameNetwork", network)
                }
            } // Diagnostic failures must not end the task or affect the game.
        }
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = queueMemorySample(-1)
            override fun onTrimMemory(level: Int) = queueMemorySample(level)

            private fun queueMemorySample(level: Int) {
                trimLevel.set(level)
                if (!pendingTrim.compareAndSet(false, true)) return
                runCatching {
                    executor.execute {
                        try { sample("trim_${trimLevel.get()}") }
                        finally { pendingTrim.set(false) }
                    }
                }.onFailure { pendingTrim.set(false) }
            }
        }
        try {
            guest.registerComponentCallbacks(callbacks)
            executor.scheduleWithFixedDelay({ sample("periodic") }, 0, 30, TimeUnit.SECONDS)
            // A repeatable ~19-second exit used to fall between the first two samples.
            // Add only ten startup samples; retain the original long-running cadence.
            for (seconds in listOf(5L, 10L, 15L, 20L, 25L, 35L, 40L, 45L, 50L, 55L)) {
                executor.schedule({ sample("startup") }, seconds, TimeUnit.SECONDS)
            }
            started = true
        } catch (_: RuntimeException) {
            guest.unregisterComponentCallbacks(callbacks)
            executor.shutdownNow()
        }
        if (!started) return
        // Log-service or file-pruning failures must not stop numeric memory samples.
        val faultExecutor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "bybus-fault").apply { isDaemon = true }
        }
        runCatching {
            val faults = GameFaultDiagnostics(context)
            val startup = GameStartupThreads()
            faultExecutor.scheduleWithFixedDelay({
                runCatching { startup.sample() }
                runCatching { faults.sample() }
            }, 0, 5, TimeUnit.SECONDS)
        }.onFailure { faultExecutor.shutdownNow() }
    }

    internal fun snapshot(context: Context): String {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        val importance = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance
        return "pid=${Process.myPid()} importance=$importance pssKb=${memory.totalPss} " +
            "privateDirtyKb=${memory.totalPrivateDirty} rssKb=${rssKb()} " +
            "${breakdown(memory)} " +
            "nativeAllocatedBytes=${Debug.getNativeHeapAllocatedSize()} " +
            "javaUsedBytes=${runtime.totalMemory() - runtime.freeMemory()} javaLimitBytes=${runtime.maxMemory()} " +
            systemSnapshot(context)
    }

    // Reuse the same Debug snapshot: no extra /proc scan or faster polling.
    // These are Android's summary buckets, not allocator live-byte counters.
    private fun breakdown(memory: Debug.MemoryInfo): String = listOf(
        "java-heap" to "memoryJavaKb", "native-heap" to "memoryNativeKb",
        "code" to "memoryCodeKb", "stack" to "memoryStackKb",
        "graphics" to "memoryGraphicsKb", "private-other" to "memoryPrivateOtherKb",
        "system" to "memorySystemKb", "total-swap" to "memorySwapKb"
    ).joinToString(" ") { (stat, field) ->
        val value = runCatching { memory.getMemoryStat("summary.$stat")?.toLongOrNull() }
            .getOrNull()?.takeIf { it >= 0 } ?: -1L
        "$field=$value"
    }

    internal fun systemSnapshot(context: Context): String = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val reports = if (Build.VERSION.SDK_INT >= 30) ActivityManager.isLowMemoryKillReportSupported().toString()
            else "unavailable"
        "systemTotalBytes=${memory.totalMem} systemAvailableBytes=${memory.availMem} " +
            "systemThresholdBytes=${memory.threshold} systemLowMemory=${memory.lowMemory} lowMemoryKillReportSupported=$reports"
    }.getOrDefault("systemMemory=unavailable")

    private fun rssKb(): Long = runCatching {
        File("/proc/self/status").bufferedReader().use { reader ->
            repeat(128) {
                val line = reader.readLine() ?: return@use -1L
                if (line.startsWith("VmRSS:")) {
                    return@use line.removePrefix("VmRSS:").trim().substringBefore(' ')
                        .toLongOrNull()?.takeIf { it >= 0 } ?: -1L
                }
            }
            -1L
        }
    }.getOrDefault(-1L)
}
