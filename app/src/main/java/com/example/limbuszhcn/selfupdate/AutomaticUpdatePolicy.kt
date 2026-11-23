package com.example.limbuszhcn.selfupdate

/** One attempt per published version and opening; manual actions never depend on this policy. */
internal class AutomaticUpdatePolicy {
    enum class Action { None, Check, Download }
    private var checkedVersion = 0L
    private var downloadedVersion = 0L
    private var paused = false

    fun opened() { checkedVersion = 0; downloadedVersion = 0; paused = false }
    fun pause() { paused = true }
    fun defer() { checkedVersion = 0; downloadedVersion = 0 }

    fun next(enabled: Boolean, eligible: Boolean, installed: Long, published: Long,
        metadata: Long?, busy: Boolean, ready: Boolean, failed: Boolean, publicationVerified: Boolean): Action {
        if (!enabled || !eligible || !publicationVerified || paused || busy || ready || failed || published <= installed) return Action.None
        if (metadata != published) {
            if (checkedVersion == published) return Action.None
            checkedVersion = published
            return Action.Check
        }
        if (downloadedVersion == published) return Action.None
        downloadedVersion = published
        return Action.Download
    }
}
