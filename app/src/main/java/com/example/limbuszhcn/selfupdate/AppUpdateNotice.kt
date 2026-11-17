package com.example.limbuszhcn.selfupdate

/** Only validated public release metadata is persisted; opening a page never clears a notice. */
internal data class AppUpdateNotice(val versionCode: Long = 0, val checkedAt: Long = 0,
    val attemptedAt: Long = 0) {
    fun hasNewer(installedCode: Long) = versionCode > installedCode

    fun shouldCheck(now: Long): Boolean {
        fun recent(at: Long, interval: Long) = at > 0 && now >= at && now - at < interval
        return !recent(checkedAt, INTERVAL) && !recent(attemptedAt, RETRY_INTERVAL)
    }

    fun attempted(now: Long) = copy(attemptedAt = now)
    fun checked(code: Long, now: Long) = AppUpdateNotice(code.coerceAtLeast(0), now, now)

    fun nextCheckDelay(now: Long): Long {
        fun remaining(at: Long, interval: Long) = if (at > 0 && now >= at)
            (interval - (now - at)).coerceAtLeast(0) else 0
        return maxOf(remaining(checkedAt, INTERVAL), remaining(attemptedAt, RETRY_INTERVAL), 1_000L)
    }

    companion object {
        const val INTERVAL = 6 * 60 * 60_000L
        const val RETRY_INTERVAL = 15 * 60_000L
    }
}

/** A new app opening checks once; ordinary resumes and recompositions keep the same session. */
internal class AppUpdateNoticeSession {
    private var openingPending = true
    fun opened() { openingPending = true }
    fun attempted() { openingPending = false }
    fun shouldCheck(notice: AppUpdateNotice, now: Long) = openingPending || notice.shouldCheck(now)
    fun nextDelay(notice: AppUpdateNotice, now: Long) = if (openingPending) 1_000L else notice.nextCheckDelay(now)
}

/** Launcher/recents may resume the existing task without onNewIntent. Ignore our own pickers. */
internal class AppForegroundOpening {
    private var stopped = false
    private var outgoingActivity = false
    fun launchedActivity() { outgoingActivity = true }
    fun stopped(changingConfiguration: Boolean) { stopped = !changingConfiguration }
    fun started(): Boolean {
        val opened = stopped && !outgoingActivity
        stopped = false; outgoingActivity = false
        return opened
    }
}
