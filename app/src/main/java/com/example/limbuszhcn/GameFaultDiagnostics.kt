package com.example.limbuszhcn

import android.content.Context
import android.os.Process
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Own-process, fixed categories only. Never retains Unity's message payload. */
internal class GameFaultDiagnostics(context: Context) {
    private var since = System.currentTimeMillis() - 1_000
    private var collectionStatus = ""
    private val seen = linkedSetOf<String>()
    private var modules = ""
    private var nextModulesAt = 0L

    init { pruneNativeTerminationTraces(context) }

    fun sample() {
        val now = System.currentTimeMillis()
        val events = readGameLog(since, listOf("--pid=${Process.myPid()}", "Unity:I", "AppSealing:E"),
            onStatus = { status ->
                if (status != collectionStatus) {
                    collectionStatus = status
                    PersistentDiagnosticLog.info("GameFault", "capture=$status intervalSeconds=5 scope=current_process")
                }
            }) { lines -> lines.take(2048).mapNotNull(::safeGameFaultMarker).take(128).toList() }
        // A failed read must not discard its time window. Bound recovery to a
        // minute, so an unavailable log service cannot grow the next read forever.
        since = maxOf(since, now - if (collectionStatus == "available") 1_000 else 60_000)
        for (event in events) if (seen.add(event)) {
            while (seen.size > 256) seen.remove(seen.first())
            PersistentDiagnosticLog.info("GameFault", event)
        }
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (elapsed < nextModulesAt) return
        nextModulesAt = elapsed + 60_000
        val current = nativeModules()
        if (current.isNotEmpty() && current != modules) {
            modules = current
            PersistentDiagnosticLog.info("GameFault", "nativeModules=$current")
        }
    }

    private fun nativeModules(): String = runCatching {
        val allowed = setOf("libil2cpp.so", "libunity.so", "libcovault-appsec.so", "libv++_64.so", "libc.so")
        File("/proc/self/maps").bufferedReader().useLines { lines ->
            lines.take(32768).mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 6)
                if (parts.size != 6 || 'x' !in parts[1]) return@mapNotNull null
                val name = parts[5].substringAfterLast('/')
                if (name !in allowed || !parts[0].matches(Regex("[0-9a-f]+-[0-9a-f]+")) ||
                    !parts[2].matches(Regex("[0-9a-f]+"))) return@mapNotNull null
                "$name@${parts[0]}+${parts[2]}"
            }.take(24).joinToString(",")
        }
    }.getOrDefault("")
}

internal fun pruneNativeTerminationTraces(context: Context) {
    val root = File(context.filesDir, "translation-cache/translation-index")
    root.listFiles().orEmpty().filter {
        it.name.matches(Regex("termination-[0-9]{1,10}\\.log")) &&
            Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)
    }.sortedByDescending(File::lastModified).drop(6).forEach { it.delete() }
}

private val UNITY_FAULT = Regex("""(\d{1,11}\.\d{3,9})\s+(\d{1,10})\s+\d+\s+[EF]\s+Unity\s*:\s*(.{0,8192})""")
private val EXCEPTIONS = listOf("NullReferenceException", "IndexOutOfRangeException", "ArgumentOutOfRangeException",
    "OutOfMemoryException", "InvalidOperationException", "MissingReferenceException", "TypeLoadException",
    "FormatException", "StackOverflowException", "DllNotFoundException", "ExecutionEngineException")
private val GAME_FRAME = Regex("""(?:at\s+)?((?:TMPro\.|UnityEngine\.|StorySystem\.|System\.|StageScript_|PatternScript_|AbnormalityAppearance_|Battle|TextData_)[A-Za-z0-9_.$+`<>]{1,160})\s*\(.*""")

internal fun safeGameFaultMarker(line: String): String? {
    safeGameTimelineMarker(line)?.let { return it }
    val match = UNITY_FAULT.matchEntire(line.trim()) ?: return null
    val message = match.groupValues[3].trim()
    val category = EXCEPTIONS.firstOrNull { message.startsWith(it + ":") || message == it }
        ?.let { "managed_exception type=$it" }
        ?: when {
            message.contains("Failed to load AssetBundle", true) -> "asset_bundle_load_failed"
            message.contains("Could not allocate memory", true) || message.contains("out of memory", true) -> "allocation_failed"
            message.contains("VK_ERROR_DEVICE_LOST") -> "graphics_device_lost"
            message.contains("VK_ERROR_OUT_OF_DEVICE_MEMORY") -> "graphics_allocation_failed"
            else -> GAME_FRAME.matchEntire(message)?.groupValues?.get(1)?.let { "managed_frame method=$it" }
        } ?: return null
    return "time=${match.groupValues[1]} pid=${match.groupValues[2]} event=$category"
}
