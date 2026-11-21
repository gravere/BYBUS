package com.example.limbuszhcn.translation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.example.limbuszhcn.MainActivity
import com.example.limbuszhcn.R

/** A saved task is a normal notification, not a reason to retain a service or wake lock. */
internal object AiPausedTaskNotification {
    const val ID = 4108
    const val RESUME_TASK = "resume_saved_ai_task"
    const val CANCEL_TASK = "cancel_saved_ai_task"

    /** Does not wait for the HTTP worker and does not suggest continuing before it has stopped. */
    fun interrupted(context: Context, session: AiTaskService.Session) {
        channel(context)
        val progress = session.progress
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setAction(AiTaskService.OPEN_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(NotificationManager::class.java).notify(ID,
            Notification.Builder(context, AiTaskService.CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_bus).setContentTitle("后台翻译已中断")
                .setContentText("已完成 ${progress.done} 条 · 点击查看任务")
                .setContentIntent(open).setOnlyAlertOnce(true).setShowWhen(false).setOngoing(false)
                .build())
    }

    private fun channel(context: Context) = context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(AiTaskService.CHANNEL, "AI 翻译任务", NotificationManager.IMPORTANCE_LOW))

    fun update(context: Context, task: AiTaskSnapshot?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (task == null || task.phase != AiTaskPhase.Paused) { manager.cancel(ID); return }
        channel(context)
        val total = task.plan?.total ?: task.progress.limit
        val done = (task.progress.completed + task.progress.reused).coerceIn(0, total.coerceAtLeast(0))
        val status = if (task.backgroundStopReason != null) "系统已暂停" else "已暂停"
        val caption = if (task.plan == null) status else "$status · $done/$total"
        fun action(kind: String, extra: String? = null) = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(AiTaskService.OPEN_TASK)
                .setData(Uri.Builder().scheme("bybus-task").authority(kind).appendPath(task.id).build())
                .apply { if (extra != null) putExtra(extra, task.id) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val resume = action("resume", RESUME_TASK)
        val cancel = action("cancel-saved", CANCEL_TASK)
        fun layout(expanded: Boolean) = RemoteViews(context.packageName, R.layout.ai_task_notification).apply {
            val count = "$done/$total"
            val compact = if (count.length <= 7) count else "${done * 100L / total.coerceAtLeast(1)}%"
            setTextViewText(R.id.ai_notice_label, if (expanded || task.plan == null) caption else compact)
            setContentDescription(R.id.ai_notice_label, caption)
            setChronometer(R.id.ai_notice_time, 0, null, false)
            setTextViewText(R.id.ai_notice_time, formatTaskDuration(task.progress.elapsedMillis))
            setProgressBar(R.id.ai_notice_progress, total.coerceAtLeast(1), done, false)
            setTextViewText(R.id.ai_notice_stop, "继续")
            setContentDescription(R.id.ai_notice_stop, "继续已保存的翻译任务")
            setOnClickPendingIntent(R.id.ai_notice_stop, resume)
            setOnClickPendingIntent(R.id.ai_notice_cancel, cancel)
        }
        manager.notify(ID, Notification.Builder(context, AiTaskService.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_bus).setContentTitle("翻译已暂停")
            .setContentText(caption).setContentIntent(action("paused")).setOnlyAlertOnce(true)
            .setShowWhen(false).setOngoing(false).setCategory(Notification.CATEGORY_PROGRESS)
            .setStyle(Notification.DecoratedCustomViewStyle()).setCustomContentView(layout(false)).setCustomBigContentView(layout(true))
            .build())
    }
}
