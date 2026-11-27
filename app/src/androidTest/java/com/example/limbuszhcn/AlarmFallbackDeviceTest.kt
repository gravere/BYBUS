package com.example.limbuszhcn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.hook.proxies.alarm.GuestAlarmCompat
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AlarmFallbackDeviceTest {
    class Service(private val manager: AlarmManager) {
        var denied = false
        var scheduled = false
        @Suppress("UNUSED_PARAMETER")
        fun set(pkg: String, type: Int, trigger: Long, window: Long, interval: Long, flags: Int,
                operation: PendingIntent, listener: Any?, tag: String?, work: Any?, clock: Any?) {
            if (window == 0L) {
                try { manager.setExactAndAllowWhileIdle(type, trigger, operation) }
                catch (failure: SecurityException) { denied = true; throw failure }
            } else {
                manager.setAndAllowWhileIdle(type, trigger, operation)
                scheduled = true
            }
        }
    }
    @Test fun systemDeniedExactTimerStillSchedulesTheReconnect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(AlarmManager::class.java)
        assumeTrue(Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms())
        val pending = PendingIntent.getBroadcast(context, 132,
            Intent("io.github.gravere.bybus.ALARM_FIXTURE").setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            val service = Service(manager)
            GuestAlarmCompat.invoke("com.google.android.gms", service,
                Service::class.java.methods.single { it.name == "set" },
                arrayOf(context.packageName, AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 3_600_000L, 0L, 0L, 5, pending, null, null, null, null))
            assertTrue("Actual system rejection must be exercised", service.denied)
            assertTrue("Fallback must reach the platform", service.scheduled)
        } finally { manager.cancel(pending); pending.cancel() }
    }
}
