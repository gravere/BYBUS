package com.example.limbuszhcn.selfupdate

/** Only validated public release metadata is persisted; opening a page never clears a notice. */
internal data class AppUpdateNotice(val versionCode: Long = 0, val checkedAt: Long = 0,
    val attemptedAt: Long = 0) {
    fun hasNewer(installedCode: Long) = versionCode > installedCode

    fun shouldCheck(now: Long): Boolean {
        fun recent(at: Long, interval: Long) = at > 0 && now >= at && now - at < interval
        return !recent(checkedAt, 6 * 60 * 60_000L) && !recent(attemptedAt, 15 * 60_000L)
    }

    fun attempted(now: Long) = copy(attemptedAt = now)
    fun checked(code: Long, now: Long) = AppUpdateNotice(code.coerceAtLeast(0), now, now)
}
