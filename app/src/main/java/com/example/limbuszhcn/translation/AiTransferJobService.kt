package com.example.limbuszhcn.translation

import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.example.limbuszhcn.PersistentDiagnosticLog

/** Owns one explicitly requested upload/translation/download; never recreates paid work. */
@RequiresApi(34)
class AiTransferJobService : JobService() {
    private var owner: AiTaskService.Session? = null
    private var parameters: JobParameters? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastNoticeAt = 0L
    private val flushNotice = Runnable {
        val current = owner
        if (current != null && !current.closed.get()) {
            lastNoticeAt = SystemClock.elapsedRealtime()
            getSystemService(NotificationManager::class.java).notify(AiTaskNotification.ID, AiTaskNotification.build(this, current))
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val current = AiTaskService.currentSession()?.takeIf {
            it.usesJob && it.jobId == params.jobId && it.id == params.extras.getString(SESSION) && !it.closed.get()
        } ?: return false // A stale job or process restart cannot automatically resume billing.
        owner = current
        parameters = params
        try {
            AiTaskNotification.channel(this)
            setNotification(params, AiTaskNotification.ID, AiTaskNotification.build(this, current), JOB_END_NOTIFICATION_POLICY_REMOVE)
            checkNotNull(params.network) { "System transfer network missing" }
            current.jobNetwork = params.network
            current.jobService = this
            PersistentDiagnosticLog.info(TAG, "User transfer started job=${params.jobId} network=true")
            current.started()
            return true
        } catch (error: Exception) {
            PersistentDiagnosticLog.warn(TAG, "Cannot start user transfer", error)
            detach()
            current.failure = "无法启动后台翻译，已保留进度，请返回 BYBUS 后继续"
            current.pause()
            return false
        }
    }

    override fun onNetworkChanged(params: JobParameters) {
        val current = owner ?: return
        if (!matches(params, current)) return
        current.jobNetwork = params.network
        PersistentDiagnosticLog.info(TAG, "Transfer network changed job=${params.jobId} available=${params.network != null}")
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val current = owner ?: return false
        if (!matches(params, current)) return false
        detach()
        PersistentDiagnosticLog.info(TAG, "User transfer stopped job=${params.jobId} reason=${params.stopReason} closed=${current.closed.get()}")
        if (!current.closed.get()) {
            current.failure = "系统已暂停后台翻译，进度已保留，可继续未完成内容"
            current.pause()
        }
        return false // Outcome of an interrupted HTTP request is unknown; no automatic retry.
    }

    override fun onDestroy() {
        val current = owner
        detach()
        if (current != null && !current.closed.get()) {
            PersistentDiagnosticLog.warn(TAG, "Transfer service destroyed before completion")
            current.failure = "后台翻译已停止，进度已保留，可继续未完成内容"
            current.pause()
        }
        super.onDestroy()
    }

    internal fun refresh() {
        main.post {
            if (owner?.closed?.get() != false) return@post
            main.removeCallbacks(flushNotice)
            main.postDelayed(flushNotice, (1000 - (SystemClock.elapsedRealtime() - lastNoticeAt)).coerceAtLeast(0))
        }
    }

    internal fun finish(current: AiTaskService.Session) {
        main.post {
            if (owner !== current) { cancel(this, current); return@post }
            val params = parameters
            detach()
            if (params != null) jobFinished(params, false)
            PersistentDiagnosticLog.info(TAG, "User transfer finished job=${current.jobId}")
        }
    }

    private fun matches(params: JobParameters, current: AiTaskService.Session) =
        params.jobId == current.jobId && params.extras.getString(SESSION) == current.id

    private fun detach() {
        main.removeCallbacksAndMessages(null)
        owner?.let { it.jobService = null; it.jobNetwork = null }
        owner = null
        parameters = null
    }

    internal companion object {
        private const val TAG = "AiTransferJob"
        private const val SESSION = "session"

        fun schedule(context: Context, session: AiTaskService.Session) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(session.jobId, ComponentName(context, AiTransferJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(PersistableBundle().apply { putString(SESSION, session.id) })
                .build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "User transfer scheduling rejected" }
            PersistentDiagnosticLog.info(TAG, "User transfer scheduled job=${session.jobId}")
        }

        fun cancel(context: Context, session: AiTaskService.Session) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(session.jobId)?.extras?.getString(SESSION) == session.id)
                scheduler.cancel(session.jobId)
        }
    }
}
