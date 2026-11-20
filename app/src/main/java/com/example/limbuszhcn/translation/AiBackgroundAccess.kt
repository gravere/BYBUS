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

    fun open(context: Context) {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"))
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        for (intent in listOf(request, fallback, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
