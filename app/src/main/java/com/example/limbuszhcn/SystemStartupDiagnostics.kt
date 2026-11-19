package com.example.limbuszhcn

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.ApplicationStartInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import com.example.limbuszhcn.container.GameProcessExit
import com.example.limbuszhcn.selfupdate.AppUpdatePackage

/** Own-process metadata only. None of these best-effort diagnostics controls startup. */
internal object SystemStartupDiagnostics {
    enum class Phase { ATTACH, MAIN_ENTRY, HOME_FRAME, INSTALLER_HANDOFF }

    @Volatile private var lastPhase: Phase? = null

    fun checkpoint(context: Context, phase: Phase) {
        if (Build.VERSION.SDK_INT < 30 || phase == lastPhase) return
        lastPhase = phase
        runCatching {
            val bytes = "bybus:1:${AppUpdatePackage.installedCode(context)}:${phase.name}".toByteArray(Charsets.US_ASCII)
            context.getSystemService(ActivityManager::class.java)?.setProcessStateSummary(bytes)
        }.onFailure {
            // Android can throttle this API. Never retry on the launch path or replace its handler.
            PersistentDiagnosticLog.info("StartupHistory", "checkpoint unavailable=${it.javaClass.simpleName}")
        }
    }

    fun exitCheckpoint(bytes: ByteArray?): String {
        if (bytes == null) return "unavailable"
        if (bytes.size !in 1..128) return "unrecognized"
        val value = bytes.toString(Charsets.US_ASCII)
        val pattern = Regex("bybus:1:[1-9][0-9]{0,9}:(?:${Phase.entries.joinToString("|") { it.name }})")
        return value.takeIf(pattern::matches) ?: "unrecognized"
    }

    @RequiresApi(30)
    private fun ownedSlotExit(context: Context, pid: Int): ApplicationExitInfo? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = manager.getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull() ?: return null
        val prefix = context.packageName + ":p"
        return info.takeIf { it.realUid == Process.myUid() && it.pid == pid &&
            it.processName.startsWith(prefix) && it.processName.substring(prefix.length).matches(SLOT_NUMBER) }
    }

    private val SLOT_NUMBER = Regex("[0-9]+")

    fun gameSlotExitTime(context: Context, pid: Int): Long? {
        if (Build.VERSION.SDK_INT < 30 || pid <= 0) return null
        return runCatching { ownedSlotExit(context, pid)?.timestamp }.getOrNull()
    }

    fun gameProcessExit(context: Context, pid: Int): GameProcessExit? {
        if (Build.VERSION.SDK_INT < 30 || pid <= 0) return null
        return runCatching {
            val info = ownedSlotExit(context, pid) ?: return null
            val detail = when (info.reason) {
                ApplicationExitInfo.REASON_SIGNALED -> "Android 记录到游戏进程收到了信号 ${info.status}。"
                ApplicationExitInfo.REASON_CRASH -> "Android 记录到游戏进程出现了未处理的异常。"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Android 记录到游戏进程发生了原生崩溃。"
                ApplicationExitInfo.REASON_ANR -> "Android 记录到游戏进程停止响应。"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "Android 因内存不足结束了游戏进程。"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Android 记录到游戏进程初始化失败。"
                else -> return null
            }
            GameProcessExit(pid, info.timestamp, detail)
        }.getOrNull()
    }

    fun collect(context: Context): String {
        if (Build.VERSION.SDK_INT < 35) return "unavailable: startup history requires Android 15 or newer\n"
        return runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
                ?: return "unavailable: ActivityManager\n"
            val starts = manager.getHistoricalProcessStartReasons(16).take(16)
            buildString {
                appendLine("scope=own-uid; bounded system history; absence is inconclusive")
                appendLine("timestamps=clock-monotonic-nanoseconds; not wall-clock time")
                appendLine("count=${starts.size}")
                starts.forEachIndexed { index, info ->
                    appendLine("\n[$index]")
                    appendLine("pid=${info.pid}")
                    val process = info.processName
                    appendLine("processRole=" + when {
                        process == context.packageName -> "host"
                        process == context.packageName + ":x" -> "container-service"
                        process?.startsWith(context.packageName + ":p") == true -> "guest"
                        else -> "other-own-process"
                    })
                    appendLine("state=" + when (info.startupState) {
                        ApplicationStartInfo.STARTUP_STATE_STARTED -> "started"
                        ApplicationStartInfo.STARTUP_STATE_ERROR -> "error"
                        ApplicationStartInfo.STARTUP_STATE_FIRST_FRAME_DRAWN -> "first-frame-drawn"
                        else -> "unknown-${info.startupState}"
                    })
                    appendLine("reason=${info.reason}")
                    appendLine("startType=${info.startType}")
                    // Do not stringify the Intent: extras, URI and third-party class names are private.
                    val intent = info.intent
                    appendLine("entry=" + when {
                        intent == null -> "unavailable"
                        intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) -> "launcher"
                        intent.action == Intent.ACTION_MAIN -> "main"
                        else -> "other"
                    })
                    appendLine("mainComponent=${intent?.component?.packageName == context.packageName &&
                        intent?.component?.className == MainActivity::class.java.name}")
                    appendLine("flags=0x${Integer.toHexString(intent?.flags ?: 0)}")
                    val times = info.startupTimestamps
                    for ((key, label) in listOf(
                        ApplicationStartInfo.START_TIMESTAMP_LAUNCH to "launchNanos",
                        ApplicationStartInfo.START_TIMESTAMP_BIND_APPLICATION to "bindNanos",
                        ApplicationStartInfo.START_TIMESTAMP_APPLICATION_ONCREATE to "applicationNanos",
                        ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME to "firstFrameNanos"
                    )) times[key]?.takeIf { it >= 0 }?.let { appendLine("$label=$it") }
                }
            }.take(16 * 1024)
        }.getOrElse { "startup history unavailable: ${it.javaClass.simpleName}\n" }
    }
}
