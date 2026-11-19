package com.example.limbuszhcn.container

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.lody.virtual.client.ipc.VActivityManager

internal data class SessionTask(val id: Int, val activities: Int, val type: String?, val base: String?, val top: String?) {
    fun canResume(packageName: String): Boolean =
        id >= 0 && activities > 0 && isVirtualGameTask(packageName, type, base, top)
}

internal data class SessionSnapshot(val packageName: String, val pids: Set<Int>, val tasks: List<SessionTask>,
    val servicePids: Set<Int> = emptySet()) {
    val resumableTasks: List<SessionTask> get() =
        if (pids.none { it !in servicePids }) emptyList() else tasks.filter { it.canResume(packageName) }

    fun diagnostic(): String = buildString {
        appendLine("scope=own-uid; live snapshot; not historical launch receipt")
        appendLine("liveGuestPids=${pids.sorted().joinToString(",")}")
        appendLine("mainGamePids=${(pids - servicePids).sorted().joinToString(",")}")
        tasks.filter { isVirtualGameTask(packageName, it.type, it.base, it.top) }.take(16).forEach {
            appendLine("taskId=${it.id} activities=${it.activities} resumable=${it in resumableTasks}")
        }
        appendLine("resumableTasks=${resumableTasks.size}")
    }
}

internal interface SessionTaskAccess {
    fun snapshot(): SessionSnapshot
    /** False when Android removed the game task. Other platform failures propagate. */
    fun moveToFront(taskId: Int): Boolean
}

/** Uses the system task route; never invokes a frozen guest or kills an existing session. */
internal class VirtualAppSession(private val access: SessionTaskAccess) {
    constructor(context: Context, packageName: String = LocalContainerRuntime.GAME_PACKAGE) :
        this(AndroidSessionTaskAccess(context, packageName))

    fun snapshot(): SessionSnapshot = access.snapshot()

    fun resume(allowMove: () -> Boolean = { true }): Boolean {
        if (snapshot().resumableTasks.isEmpty() || !allowMove()) return false
        // A process can exit or Android can replace its task during the entry decision.
        val task = snapshot().resumableTasks.firstOrNull() ?: return false
        if (!allowMove()) return false
        return access.moveToFront(task.id)
    }
}

private class AndroidSessionTaskAccess(context: Context, private val packageName: String) : SessionTaskAccess {
    private val manager = requireNotNull(context.getSystemService(ActivityManager::class.java))

    override fun snapshot(): SessionSnapshot {
        val ownPids = requireNotNull(manager.runningAppProcesses) { "无法读取当前游戏进程" }
            .filter { it.uid == Process.myUid() && it.pid > 0 }.map { it.pid }.toSet()
        val guests = VActivityManager.get().getRunningAppProcesses(packageName, 0)
            .filter { it.packageName == packageName && it.pid in ownPids }
        val pids = guests.map { it.pid }.toSet()
        val servicePids = guests.filter { it.processName != packageName }.map { it.pid }.toSet()
        val tasks = manager.appTasks.mapNotNull(::readTask)
        return SessionSnapshot(packageName, pids, tasks, servicePids)
    }

    private fun readTask(task: ActivityManager.AppTask): SessionTask? {
        // getTaskInfo throws when this recent task disappears after getAppTasks.
        val info = try { task.taskInfo } catch (_: IllegalArgumentException) { return null } ?: return null
        return SessionTask(if (Build.VERSION.SDK_INT >= 29) info.taskId else info.id, info.numActivities,
            info.baseIntent?.type, info.baseActivity?.className, info.topActivity?.className)
    }

    override fun moveToFront(taskId: Int): Boolean {
        val candidates = manager.appTasks.mapNotNull { task ->
            val entry = readTask(task) ?: return@mapNotNull null
            if (entry.canResume(packageName)) entry.id to task else null
        }
        val (id, task) = candidates.firstOrNull { it.first == taskId } ?: candidates.firstOrNull() ?: return false
        try {
            task.moveToFront()
        } catch (error: IllegalArgumentException) {
            val current = readTask(task)
            if (current?.id == id && current.canResume(packageName)) throw error
            return false
        }
        return true
    }
}
