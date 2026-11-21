package com.example.limbuszhcn.translation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.example.limbuszhcn.MainActivity
import com.example.limbuszhcn.R

internal object AiTaskNotification {
    const val ID = 4107
    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(AiTaskService.CHANNEL, "AI 翻译任务", NotificationManager.IMPORTANCE_LOW))
    }

    fun build(context: Context, current: AiTaskService.Session?): Notification {
        val progress = current?.progress ?: AiTaskService.Progress("正在准备翻译", 0, 0)
        val label = compactAiTaskLabel(progress.label)
        val caption = if (progress.total > 0) "${progress.done}/${progress.total} · $label" else label
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setAction(AiTaskService.OPEN_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(context, AiTaskService.CHANNEL).setSmallIcon(R.drawable.ic_notification_bus)
            .setContentTitle("AI 翻译").setContentText(caption)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (current != null && !current.paused.get()) {
            val pause = PendingIntent.getBroadcast(context, current.id.hashCode(), Intent(context, AiTaskService.PauseReceiver::class.java)
                .setAction(AiTaskService.PAUSE).putExtra("session", current.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val cancel = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setAction(AiTaskService.OPEN_TASK)
                .setData(android.net.Uri.parse("bybus-task://cancel/${current.id}"))
                .putExtra(AiTaskService.CANCEL_SESSION, current.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // The system decorates the custom view. Native progress/clock/actions would be drawn again.
            fun layout(expanded: Boolean) = RemoteViews(context.packageName, R.layout.ai_task_notification).apply {
                val count = "${progress.done}/${progress.total}"
                val compact = if (count.length <= 7) count else "${progress.done * 100L / progress.total.coerceAtLeast(1)}%"
                setTextViewText(R.id.ai_notice_label, if (!expanded && progress.total > 0) compact else caption)
                setContentDescription(R.id.ai_notice_label, caption)
                setChronometer(R.id.ai_notice_time, progress.clockBase, "%s", true)
                setProgressBar(R.id.ai_notice_progress, progress.total, progress.done, progress.total <= 0)
                setOnClickPendingIntent(R.id.ai_notice_stop, pause)
                setOnClickPendingIntent(R.id.ai_notice_cancel, cancel)
            }
            builder.setStyle(Notification.DecoratedCustomViewStyle()).setCustomContentView(layout(false)).setCustomBigContentView(layout(true))
        }
        return builder.build()
    }

}
