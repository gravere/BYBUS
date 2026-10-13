package com.example.limbuszhcn.selfupdate

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class UpdateHttpException(val status: Int, val retryAfterMillis: Long = 0) : Exception(when {
    retryAfterMillis > 0 -> "GitHub 暂时限制了请求"
    status == 403 -> "GitHub 拒绝了请求，可重试或查看发行页面（HTTP 403）"
    status == 404 -> "暂未找到正式发行版，请稍后重试"
    else -> "更新服务暂时不可用（HTTP $status）"
}) {
    companion object {
        const val MAX_WAIT = 86_400_000L

        /** A reset header accompanies ordinary errors too; it is not itself evidence of throttling. */
        fun fromResponse(status: Int, remaining: String?, reset: String?, retryAfter: String?,
            serverDate: String?, body: String, now: Long): UpdateHttpException {
            fun date(value: String?): Long? = value?.let {
                runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            }
            val reference = date(serverDate) ?: now
            val after = retryAfter?.trim()?.toLongOrNull()?.takeIf { it in 0..86_400 }?.times(1000)
                ?: date(retryAfter)?.let { (it - reference).takeIf { delay -> delay in 0..MAX_WAIT } }
            val exhausted = remaining?.trim() == "0"
            val rateMessage = body.take(8192).lowercase(java.util.Locale.ROOT).let {
                "rate limit" in it || "abuse detection" in it
            }
            val limited = status == 429 || status == 403 && (exhausted || after != null || rateMessage)
            if (!limited) return UpdateHttpException(status)
            val resetDelay = if (exhausted) reset?.toLongOrNull()?.takeIf { it in 0..Long.MAX_VALUE / 1000 }
                ?.times(1000)?.let { (it - reference).takeIf { delay -> delay in 0..MAX_WAIT } } else null
            return UpdateHttpException(status, maxOf(60_000, after ?: 0, resetDelay ?: 0))
        }
    }
}

internal data class UpdateRateLimitWindow(val recordedAt: Long, val retryAt: Long)
internal enum class UpdateCheckDecision { Check, Cached, Wait }

/** Local result reuse never blocks an explicit refresh. Persist only a confirmed server wait. */
internal class AppUpdateCheckPolicy(private val elapsed: () -> Long, private val wallClock: () -> Long,
    saved: UpdateRateLimitWindow? = null, private val persist: (UpdateRateLimitWindow?) -> Unit = {}) {
    private var succeededAt: Long? = null
    var retryAtElapsed: Long? = null
        private set

    init {
        if (saved != null) {
            val now = wallClock()
            if (saved.recordedAt >= 0 && saved.retryAt > saved.recordedAt &&
                saved.retryAt - saved.recordedAt <= UpdateHttpException.MAX_WAIT &&
                now >= saved.recordedAt && now < saved.retryAt) {
                retryAtElapsed = elapsed() + (saved.retryAt - now)
            } else persist(null)
        }
    }

    fun beforeCheck(force: Boolean): UpdateCheckDecision {
        val now = elapsed()
        retryAtElapsed?.let { if (now < it) return UpdateCheckDecision.Wait else clearWait() }
        return if (!force && succeededAt?.let { now - it in 0 until 60_000L } == true)
            UpdateCheckDecision.Cached else UpdateCheckDecision.Check
    }

    fun succeeded() { succeededAt = elapsed(); clearWait() }

    fun failed(error: Exception) {
        if (error is java.util.concurrent.CancellationException) return
        succeededAt = null
        val wait = (error as? UpdateHttpException)?.retryAfterMillis ?: 0
        if (wait in 1..UpdateHttpException.MAX_WAIT) {
            retryAtElapsed = elapsed() + wait
            val now = wallClock()
            persist(UpdateRateLimitWindow(now, now + wait))
        } else clearWait()
    }

    private fun clearWait() { retryAtElapsed = null; persist(null) }
}
