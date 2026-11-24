package com.example.limbuszhcn

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameMemoryDiagnosticsTest {
    @Test fun lowMemoryCallbackPersistsAnOwnProcessSampleWithoutNotification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val notifications = context.getSystemService(NotificationManager::class.java)
        val before = notifications.activeNotifications.map { it.id to it.tag }.toSet()
        GameMemoryDiagnostics.start(context, application)
        GameMemoryDiagnostics.start(context, application)
        application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        val deadline = SystemClock.uptimeMillis() + 5_000
        var found = false
        while (!found && SystemClock.uptimeMillis() < deadline) {
            found = PersistentDiagnosticLog.files(context).any { file ->
                file.useLines { lines -> lines.any {
                    it.contains("pid=${Process.myPid()} ") && it.contains("GameMemory: cause=trim_10")
                } }
            }
            if (!found) Thread.sleep(50)
        }
        assertTrue("Application low-memory callback must reach the bounded persistent log", found)
        assertEquals(before, notifications.activeNotifications.map { it.id to it.tag }.toSet())
    }

    @Test fun reportsOwnProcessAndSystemMemoryWithoutAccountOrResourceData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val line = GameMemoryDiagnostics.snapshot(context)
        val values = line.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(Process.myPid().toString(), values["pid"])
        assertTrue(values.getValue("pssKb").toLong() > 0)
        assertTrue(values.getValue("rssKb").toLong() > 0)
        assertTrue(values.getValue("nativeAllocatedBytes").toLong() > 0)
        assertTrue(values.getValue("javaUsedBytes").toLong() > 0)
        val total = values.getValue("systemTotalBytes").toLong()
        assertTrue(total > 0)
        assertTrue(values.getValue("systemAvailableBytes").toLong() in 1..total)
        val buckets = setOf("memoryJavaKb", "memoryNativeKb", "memoryCodeKb", "memoryStackKb",
            "memoryGraphicsKb", "memoryPrivateOtherKb", "memorySystemKb", "memorySwapKb")
        buckets.forEach { assertTrue("Android memory summary $it must be present", values.getValue(it).toLong() >= 0) }
        assertEquals(setOf("pid", "importance", "pssKb", "privateDirtyKb", "rssKb", "nativeAllocatedBytes",
            "javaUsedBytes", "javaLimitBytes", "systemTotalBytes", "systemAvailableBytes", "systemThresholdBytes",
            "systemLowMemory", "lowMemoryKillReportSupported") + buckets, values.keys)
    }
}
