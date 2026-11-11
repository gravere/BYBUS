package com.example.limbuszhcn

internal const val MANAGE_BYBUS_ACTION = "io.github.gravere.bybus.MANAGE"

internal fun shouldResumeLauncherEntry(action: String?, hasLauncherCategory: Boolean,
    fromHistory: Boolean, restored: Boolean): Boolean =
    action == "android.intent.action.MAIN" && hasLauncherCategory && !fromHistory && !restored
