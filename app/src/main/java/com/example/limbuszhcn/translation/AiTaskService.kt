package com.example.limbuszhcn.translation

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.example.limbuszhcn.PersistentDiagnosticLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Lifetime of one explicitly started operation. Never restores or sends a paid request itself. */
class AiTaskService : Service() {
    private var session: Session? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastNoticeAt = 0L
    private val flushNotice = Runnable {
        if (session?.closed?.get() == false) {
            lastNoticeAt = SystemClock.elapsedRealtime()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    override fun onCreate() {
        super.onCreate()
        session = synchronized(lock) { active }
        val current = session
        try {
            AiTaskNotification.channel(this)
            enterForeground()
            if (current == null || current.closed.get()) { stopSelf(); return }
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BYBUS:AiTranslation")
                .apply { setReferenceCounted(false); acquire(TimeUnit.HOURS.toMillis(6)) }
            current.service = this
            current.started()
            PersistentDiagnosticLog.info("AiTaskService", "Foreground translation started")
        } catch (error: Exception) {
            PersistentDiagnosticLog.warn("AiTaskService", "Cannot start foreground translation", error)
            current?.failure = "无法保持后台翻译，请返回 BYBUS 后继续"
            current?.started()
            current?.pause()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = synchronized(lock) { active }
        // A fresh start can reach the existing instance after stop/start raced. Each
        // startForegroundService request must be acknowledged, even if already cancelled.
        if (intent?.action != PAUSE) try { enterForeground() } catch (error: Exception) {
            PersistentDiagnosticLog.warn("AiTaskService", "Cannot renew foreground translation", error)
            current?.failure = "无法保持后台翻译，请返回 BYBUS 后继续"
            current?.started(); current?.pause(); current?.close(); stopSelf()
            return START_NOT_STICKY
        }
        if (current == null || current.closed.get()) { stopSelf(); return START_NOT_STICKY }
        if (current !== session) {
            try {
                session = current
                current.service = this
                if (wakeLock?.isHeld != true) wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BYBUS:AiTranslation")
                    .apply { setReferenceCounted(false); acquire(TimeUnit.HOURS.toMillis(6)) }
                current.started()
                refresh()
            } catch (error: Exception) {
                PersistentDiagnosticLog.warn("AiTaskService", "Cannot renew foreground translation", error)
                current.failure = "无法保持后台翻译，请返回 BYBUS 后继续"
                current.started(); current.pause(); current.close(); stopSelf()
                return START_NOT_STICKY
            }
        }
        if (intent?.action == PAUSE && intent.getStringExtra("session") == current.id) {
            current.pause()
            current.update("正在停止，保留已完成进度", current.progress.done, current.progress.total)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground() {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, notification())
    }

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
                current.started()
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
            // Coalesce bursts, but always deliver the final update even if no next batch arrives.
            main.removeCallbacks(flushNotice)
            main.postDelayed(flushNotice, (1000 - (SystemClock.elapsedRealtime() - lastNoticeAt)).coerceAtLeast(0))
        }
    }

    private fun notification(): Notification = AiTaskNotification.build(this, session)

    internal data class Progress(val label: String, val done: Int, val total: Int,
        val clockBase: Long = SystemClock.elapsedRealtime())

    internal class Session(private val context: Context, internal val usesJob: Boolean, private val onPause: () -> Unit) : AutoCloseable {
        val id: String = java.util.UUID.randomUUID().toString()
        internal val jobId: Int = nextJobId.incrementAndGet()
        val ready = CountDownLatch(1)
        private val dispatch = AiTaskDispatch()
        val closed = AtomicBoolean(false)
        val paused = AtomicBoolean(false)
        @Volatile var failure: String? = null
        @Volatile internal var service: AiTaskService? = null
        @Volatile internal var jobService: AiTransferJobService? = null
        @Volatile internal var jobNetwork: android.net.Network? = null
        @Volatile private var value = Progress("正在准备翻译", 0, 0)
        private val main = Handler(Looper.getMainLooper())
        internal val progress: Progress get() = value

        init {
            if (!usesJob) main.postDelayed({
                if (ready.count > 0 && !closed.get()) {
                    failure = "后台翻译服务未能启动，请返回 BYBUS 后继续"
                    pause()
                }
            }, 15_000)
        }

        fun execute(executor: java.util.concurrent.Executor, operation: Runnable) = dispatch.execute(executor, operation)

        internal fun started() { ready.countDown(); dispatch.release() }

        fun awaitStarted() {
            check(Looper.myLooper() != Looper.getMainLooper())
            if (!ready.await(15, TimeUnit.SECONDS)) throw AiTranslationException("后台翻译服务未能启动，请返回 BYBUS 后继续")
            failure?.let { throw AiTranslationException(it) }
            if (closed.get() || paused.get()) throw java.util.concurrent.CancellationException()
        }

        fun update(label: String, done: Int, total: Int) {
            if (closed.get()) return
            value = value.copy(label = label, done = done.coerceIn(0, total.coerceAtLeast(0)), total = total.coerceAtLeast(0))
            service?.refresh()
            if (Build.VERSION.SDK_INT >= 34) jobService?.refresh()
        }

        fun elapsed(elapsedMillis: Long) {
            value = value.copy(clockBase = SystemClock.elapsedRealtime() - elapsedMillis.coerceAtLeast(0))
            service?.refresh()
            if (Build.VERSION.SDK_INT >= 34) jobService?.refresh()
        }

        internal fun pause() {
            if (!closed.get() && paused.compareAndSet(false, true)) {
                val stop = Runnable {
                    // A delayed main-thread callback from the old run must not cancel a new run.
                    if (!closed.get() && currentSession() === this) onPause()
                    started()
                }
                if (Looper.myLooper() == Looper.getMainLooper()) stop.run() else main.post(stop)
            }
        }

        internal fun finish(task: AiTaskSnapshot?) {
            close()
            restorePaused(context, task)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            started()
            synchronized(lock) {
                if (active === this) {
                    active = null
                    // Clear/stop under the same lock: an old operation cannot stop a newly started service.
                    // If creation is still queued, onCreate must first satisfy the
                    // foreground contract before stopping, otherwise Android kills the app.
                    if (usesJob && Build.VERSION.SDK_INT >= 34) {
                        val running = jobService
                        if (running != null) running.finish(this) else AiTransferJobService.cancel(context, this)
                    } else if (service != null) context.stopService(Intent(context, AiTaskService::class.java))
                }
            }
        }
    }

    class PauseReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != PAUSE) return
            currentSession()?.takeIf { it.id == intent.getStringExtra("session") }?.let {
                it.pause()
                it.update("正在停止，保留已完成进度", it.progress.done, it.progress.total)
            }
        }
    }

    companion object {
        const val OPEN_TASK = "io.github.gravere.bybus.OPEN_AI_TASK"
        const val CANCEL_SESSION = "confirm_cancel_session"
        internal const val PAUSE = "io.github.gravere.bybus.PAUSE_AI_TASK"
        internal const val CHANNEL = "bybus-ai-translation"
        private const val NOTIFICATION_ID = AiTaskNotification.ID
        private val lock = Any()
        private val nextJobId = java.util.concurrent.atomic.AtomicInteger(14200)
        @Volatile private var active: Session? = null
        internal fun currentSession(): Session? = active?.takeUnless { it.closed.get() }
        internal fun pause() { currentSession()?.pause() }
        internal fun hasActiveTask(): Boolean = active?.closed?.get() == false
        internal fun activeSessionId(): String? = active?.takeUnless { it.closed.get() }?.id

        internal fun restorePaused(context: Context, task: AiTaskSnapshot?) = synchronized(lock) {
            if (active?.closed?.get() == false) return@synchronized
            runCatching { AiPausedTaskNotification.update(context, task) }.onFailure {
                PersistentDiagnosticLog.warn("AiTaskService", "Cannot update saved task notification", it)
            }
        }

        internal fun start(context: Context, useJob: Boolean = Build.VERSION.SDK_INT >= 34, onPause: () -> Unit): Session = synchronized(lock) {
            check(active == null) { "已有翻译任务正在运行" }
            val application = context.applicationContext
            val session = Session(application, useJob, onPause)
            active = session
            runCatching { AiPausedTaskNotification.update(application, null) }
            try {
                if (useJob && Build.VERSION.SDK_INT >= 34) AiTransferJobService.schedule(application, session)
                else application.startForegroundService(Intent(application, AiTaskService::class.java))
            }
            catch (error: Exception) {
                PersistentDiagnosticLog.warn("AiTaskService", "Background task launch rejected", error)
                active = null
                session.closed.set(true)
                throw AiTranslationException("无法启动后台翻译，请返回 BYBUS 后继续")
            }
            session
        }

        internal fun update(label: String, done: Int = 0, total: Int = 0) { active?.update(label, done, total) }
        internal fun elapsed(elapsedMillis: Long) { active?.elapsed(elapsedMillis) }
    }
}
