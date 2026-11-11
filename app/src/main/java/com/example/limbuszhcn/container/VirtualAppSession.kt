package com.example.limbuszhcn.container

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.lody.virtual.client.ipc.VActivityManager

internal data class SessionTask(val id: Int, val activities: Int, val type: String?, val base: String?, val top: String?)

internal data class SessionSnapshot(val packageName: String, val pids: Set<Int>, val tasks: List<SessionTask>) {
    val resumableTasks: List<SessionTask> get() = if (pids.isEmpty()) emptyList() else tasks.filter {
        it.id >= 0 && it.activities > 0 && isVirtualGameTask(packageName, it.type, it.base, it.top)
    }

    fun diagnostic(): String = buildString {
        appendLine("scope=own-uid; live snapshot; not historical launch receipt")
        appendLine("liveGuestPids=${pids.sorted().joinToString(",")}")
        tasks.filter { isVirtualGameTask(packageName, it.type, it.base, it.top) }.take(16).forEach {
            appendLine("taskId=${it.id} activities=${it.activities} resumable=${it in resumableTasks}")
        }
        appendLine("resumableTasks=${resumableTasks.size}")
    }
}

internal interface SessionTaskAccess {
    fun snapshot(): SessionSnapshot
    fun moveToFront(taskId: Int)
}

/** Uses the system task route; never invokes a frozen guest or kills an existing session. */
internal class VirtualAppSession(private val access: SessionTaskAccess) {
    constructor(context: Context, packageName: String = LocalContainerRuntime.GAME_PACKAGE) :
        this(AndroidSessionTaskAccess(context, packageName))

    fun snapshot(): SessionSnapshot = access.snapshot()

    fun resume(): Boolean {
        val task = snapshot().resumableTasks.firstOrNull() ?: return false
        // Failure must propagate. Falling through to a cold launch would kill the saved session.
        access.moveToFront(task.id)
        return true
    }
}

private class AndroidSessionTaskAccess(context: Context, private val packageName: String) : SessionTaskAccess {
    private val manager = requireNotNull(context.getSystemService(ActivityManager::class.java))

    override fun snapshot(): SessionSnapshot {
        val ownPids = requireNotNull(manager.runningAppProcesses) { "无法读取当前游戏进程" }
            .filter { it.uid == Process.myUid() && it.pid > 0 }.map { it.pid }.toSet()
        val pids = VActivityManager.get().getRunningAppProcesses(packageName, 0)
            .filter { it.packageName == packageName && it.pid in ownPids }.map { it.pid }.toSet()
        val tasks = manager.appTasks.map { task ->
            val info = task.taskInfo
            SessionTask(if (Build.VERSION.SDK_INT >= 29) info.taskId else info.id, info.numActivities,
                info.baseIntent?.type, info.baseActivity?.className, info.topActivity?.className)
        }
        return SessionSnapshot(packageName, pids, tasks)
    }

    override fun moveToFront(taskId: Int) {
        val task = manager.appTasks.firstOrNull {
            val info = it.taskInfo
            (if (Build.VERSION.SDK_INT >= 29) info.taskId else info.id) == taskId &&
                isVirtualGameTask(packageName, info.baseIntent?.type, info.baseActivity?.className, info.topActivity?.className)
        } ?: error("游戏任务已变化，请重新点击返回游戏")
        task.moveToFront()
    }
}
