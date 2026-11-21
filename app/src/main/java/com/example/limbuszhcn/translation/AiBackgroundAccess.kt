package com.example.limbuszhcn.translation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** User-granted access for long, user-started requests to arbitrary AI providers (no FCM endpoint). */
internal object AiBackgroundAccess {
    fun allowed(context: Context): Boolean = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)

    fun open(context: Context): Boolean {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"))
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return launch(context, listOf(request, fallback) + settingsIntents(context.packageName))
    }

    // No private OEM activities: autostart permission is unrelated to continuing an active task.
    internal fun settingsIntents(packageName: String) = listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))

    fun openSettings(context: Context): Boolean = launch(context, settingsIntents(context.packageName))

    private fun launch(context: Context, candidates: List<Intent>): Boolean {
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }
}
