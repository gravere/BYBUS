package com.example.limbuszhcn

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Three startup-only observations. Never exports thread names, values or exception messages. */
internal class GameStartupThreads {
    private val started = SystemClock.elapsedRealtime()
    private var next = 0
    private val points = longArrayOf(10_000, 30_000, 60_000)

    fun sample() {
        val age = SystemClock.elapsedRealtime() - started
        if (next >= points.size || age < points[next]) return
        next++
        val requested = SystemClock.elapsedRealtime()
        Handler(Looper.getMainLooper()).post {
            PersistentDiagnosticLog.info("GameStartup", "mainHeartbeat ageMs=$age delayMs=${SystemClock.elapsedRealtime() - requested}")
        }
        runCatching {
            val snapshot = Thread.getAllStackTraces()
            val threads = snapshot.mapNotNull { (thread, frames) ->
                startupThreadSummary(thread.name, thread.state, frames)
            }.sorted().take(8)
            PersistentDiagnosticLog.info("GameStartup", "ageMs=$age enumerated=${snapshot.size} summarized=${threads.size}")
            threads.forEach { PersistentDiagnosticLog.info("GameStartup", "ageMs=$age $it") }
        }.onFailure {
            PersistentDiagnosticLog.warn("GameStartup", "ageMs=$age enumeration=unavailable")
        }
    }
}

internal fun startupThreadSummary(name: String, state: Thread.State, frames: Array<StackTraceElement>): String? {
    val role = when {
        name == "main" -> "main"
        name == "UnityMain" -> "unity"
        name == "bybus-log-read" -> "diagnostic_reader"
        name == "bybus-log-cleanup" -> "diagnostic_cleanup"
        name == "bybus-fault" -> "diagnostic_sampler"
        frames.any { it.className.startsWith("com.google.firebase.") } -> "firebase"
        else -> return null
    }
    val stack = frames.take(12).map { frame ->
        val allowed = listOf("java.", "javax.", "android.", "com.android.internal.", "dalvik.",
            "com.unity3d.", "com.google.firebase.", "com.google.android.gms.", "com.lody.virtual.",
            "com.example.limbuszhcn.")
        if (allowed.none(frame.className::startsWith) ||
            !frame.className.matches(Regex("[A-Za-z0-9_.$]{1,200}")) ||
            !frame.methodName.matches(Regex("[A-Za-z0-9_$<>-]{1,120}"))) "external"
        else "${frame.className}.${frame.methodName}:${if (frame.isNativeMethod) "native" else frame.lineNumber.coerceAtLeast(0)}"
    }.joinToString(",")
    return "thread=$role state=${state.name} frames=$stack"
}
