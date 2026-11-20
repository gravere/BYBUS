package com.example.limbuszhcn.translation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.example.limbuszhcn.MainActivity
import com.example.limbuszhcn.PersistentDiagnosticLog
import com.example.limbuszhcn.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Lifetime of one explicitly started operation. Never restores or sends a paid request itself. */
class AiTaskService : Service() {
    private var session: Session? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastNoticeAt = 0L

    override fun onCreate() {
        super.onCreate()
        session = synchronized(lock) { active }
        val current = session
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "AI 翻译任务", NotificationManager.IMPORTANCE_LOW))
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, notification())
            if (current == null || current.closed.get()) { stopSelf(); return }
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BYBUS:AiTranslation")
                .apply { setReferenceCounted(false); acquire(TimeUnit.HOURS.toMillis(6)) }
            current.service = this
            current.ready.countDown()
            PersistentDiagnosticLog.info("AiTaskService", "Foreground translation started")
        } catch (error: Exception) {
            PersistentDiagnosticLog.warn("AiTaskService", "Cannot start foreground translation", error)
            current?.failure = "无法保持后台翻译，请返回 BYBUS 后继续"
            current?.ready?.countDown()
            current?.pause()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = synchronized(lock) { active }
        if (current == null || current.closed.get()) { stopSelf(); return START_NOT_STICKY }
        if (current !== session) {
            try {
                session = current
                current.service = this
                if (wakeLock?.isHeld != true) wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BYBUS:AiTranslation")
                    .apply { setReferenceCounted(false); acquire(TimeUnit.HOURS.toMillis(6)) }
                current.ready.countDown()
                refresh()
            } catch (error: Exception) {
                PersistentDiagnosticLog.warn("AiTaskService", "Cannot renew foreground translation", error)
                current.failure = "无法保持后台翻译，请返回 BYBUS 后继续"
                current.ready.countDown(); current.pause(); current.close(); stopSelf()
                return START_NOT_STICKY
            }
        }
        if (intent?.action == PAUSE && intent.getStringExtra("session") == current.id) {
            current.pause()
            current.update("正在暂停，等待当前请求保存", 0, 0)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 gives only a few seconds to stop the foreground service after its quota expires.
        session?.pause()
        PersistentDiagnosticLog.warn("AiTaskService", "Foreground time limit reached; requesting pause")
        session?.close()
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        session?.let { current ->
            current.service = null
            if (!current.closed.get()) {
                current.failure = "后台翻译已停止，进度已保留，可返回 BYBUS 继续"
                current.pause()
                current.ready.countDown()
                current.close()
            }
        }
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        PersistentDiagnosticLog.info("AiTaskService", "Foreground translation stopped")
        super.onDestroy()
    }

    private fun refresh() {
        main.post {
            if (session?.closed?.get() != false) return@post
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastNoticeAt < 1000) return@post
            lastNoticeAt = now
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): Notification {
        val current = session
        val progress = current?.progress ?: Progress("正在准备翻译", 0, 0)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).setAction(OPEN_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification_bus)
            .setContentTitle("AI 翻译").setContentText(if (progress.total > 0) "${progress.label} · ${progress.done}/${progress.total}" else progress.label)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(progress.total, progress.done, progress.total <= 0)
        if (current != null && !current.paused.get()) {
            val pause = PendingIntent.getService(this, current.id.hashCode(), Intent(this, AiTaskService::class.java)
                .setAction(PAUSE).putExtra("session", current.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "暂停", pause).build())
        }
        return builder.build()
    }

    internal data class Progress(val label: String, val done: Int, val total: Int)

    internal class Session(private val context: Context, private val onPause: () -> Unit) : AutoCloseable {
        val id: String = java.util.UUID.randomUUID().toString()
        val ready = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val paused = AtomicBoolean(false)
        @Volatile var failure: String? = null
        @Volatile internal var service: AiTaskService? = null
        @Volatile private var value = Progress("正在准备翻译", 0, 0)
        private val main = Handler(Looper.getMainLooper())
        internal val progress: Progress get() = value

        fun awaitStarted() {
            check(Looper.myLooper() != Looper.getMainLooper())
            if (!ready.await(15, TimeUnit.SECONDS)) throw AiTranslationException("后台翻译服务未能启动，请返回 BYBUS 后继续")
            failure?.let { throw AiTranslationException(it) }
            if (closed.get() || paused.get()) throw java.util.concurrent.CancellationException()
        }

        fun update(label: String, done: Int, total: Int) {
            if (closed.get()) return
            value = Progress(label, done.coerceIn(0, total.coerceAtLeast(0)), total.coerceAtLeast(0))
            service?.refresh()
        }

        internal fun pause() {
            if (paused.compareAndSet(false, true)) {
                if (Looper.myLooper() == Looper.getMainLooper()) onPause() else main.post(onPause)
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            ready.countDown()
            synchronized(lock) {
                if (active === this) {
                    active = null
                    // Clear/stop under the same lock: an old operation cannot stop a newly started service.
                    context.stopService(Intent(context, AiTaskService::class.java))
                }
            }
        }
    }

    companion object {
        const val OPEN_TASK = "io.github.gravere.bybus.OPEN_AI_TASK"
        private const val PAUSE = "io.github.gravere.bybus.PAUSE_AI_TASK"
        private const val CHANNEL = "bybus-ai-translation"
        private const val NOTIFICATION_ID = 4107
        private val lock = Any()
        @Volatile private var active: Session? = null
        internal fun hasActiveTask(): Boolean = active?.closed?.get() == false

        internal fun start(context: Context, onPause: () -> Unit): Session = synchronized(lock) {
            check(active == null) { "已有翻译任务正在运行" }
            val application = context.applicationContext
            val session = Session(application, onPause)
            active = session
            try { application.startForegroundService(Intent(application, AiTaskService::class.java)) }
            catch (error: Exception) {
                PersistentDiagnosticLog.warn("AiTaskService", "Foreground launch rejected", error)
                active = null
                session.closed.set(true)
                throw AiTranslationException("无法启动后台翻译，请返回 BYBUS 后继续")
            }
            session
        }

        internal fun update(label: String, done: Int = 0, total: Int = 0) { active?.update(label, done, total) }
    }
}
