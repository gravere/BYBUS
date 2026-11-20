package com.example.limbuszhcn

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.limbuszhcn.translation.AiTaskService
import com.example.limbuszhcn.translation.ProtectedTranslation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AiTaskServiceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(check: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        while (!check() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(check())
    }
    @Suppress("DEPRECATION")
    private fun running() = context.getSystemService(ActivityManager::class.java).getRunningServices(50)
        .find { it.service.className == AiTaskService::class.java.name }

    @Test fun activeTranslationKeepsForegroundServiceInBackgroundAndNotificationCanPauseIt() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val pauses = AtomicInteger()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(AiTaskService.OPEN_TASK)).use { screen ->
            lateinit var session: AiTaskService.Session
            var previousRequest = 0L
            screen.onActivity { previousRequest = LaunchCoordinator.get(it.application).state.aiTaskRequest }
            screen.onActivity { session = AiTaskService.start(it) { pauses.incrementAndGet() } }
            try {
                session.awaitStarted()
                session.update("正在翻译所选文本", 1, 37)
                screen.moveToState(Lifecycle.State.CREATED)
                waitFor { running()?.foreground == true }
                val manager = context.getSystemService(NotificationManager::class.java)
                waitFor { manager.activeNotifications.any { it.notification.channelId == "bybus-ai-translation" &&
                    it.notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX) == 37 } }
                val notice = manager.activeNotifications.first { it.id == 4107 }.notification
                assertEquals(1, notice.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
                // Two updates inside the throttle interval must still deliver the trailing value.
                session.update("正在翻译所选文本", 2, 37)
                session.update("正在翻译所选文本", 3, 37)
                waitFor { manager.activeNotifications.any { it.notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS) == 3 } }
                assertNotNull(notice.contentView)
                screen.onActivity { activity ->
                    val view = notice.contentView.apply(activity, android.widget.FrameLayout(activity))
                    assertEquals("停止", view.findViewById<android.widget.TextView>(R.id.ai_notice_stop).text.toString())
                    assertTrue(view.findViewById<android.widget.Chronometer>(R.id.ai_notice_time).base <= android.os.SystemClock.elapsedRealtime())
                }
                notice.contentIntent.send()
                screen.moveToState(Lifecycle.State.RESUMED)
                screen.onActivity { assertTrue(LaunchCoordinator.get(it.application).state.aiTaskRequest > previousRequest) }
                notice.actions.single { it.title == "停止" }.actionIntent.send()
                waitFor { pauses.get() == 1 }
                assertTrue(session.paused.get())
            } finally { session.close() }
            waitFor { running() == null }
            waitFor { context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 4107 } }
            assertEquals(1, pauses.get())
        }
    }

    @Test fun idleLauncherDoesNotKeepTaskOrContainerNotifications() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(AiTaskService.OPEN_TASK)).use { screen ->
            val manager = context.getSystemService(NotificationManager::class.java)
            waitFor { manager.activeNotifications.none { it.id == 2781 || it.id == 4107 } }
            // An explicit launch gets foreground protection during the handoff, even before a guest exists.
            try {
                screen.onActivity { com.lody.virtual.client.stub.KeepAliveService.requestStart(it) }
                waitFor { manager.activeNotifications.any { it.id == 2781 } }
                screen.onActivity { it.stopService(Intent(it, com.lody.virtual.client.stub.KeepAliveService::class.java)) }
                waitFor { manager.activeNotifications.none { it.id == 2781 } }
                // A stale service restart without a launch or live game must immediately release protection.
                screen.onActivity { it.startForegroundService(Intent(it, com.lody.virtual.client.stub.KeepAliveService::class.java)) }
                Thread.sleep(750)
                waitFor { manager.activeNotifications.none { it.id == 2781 || it.id == 4107 } }
            } finally { context.stopService(Intent(context, com.lody.virtual.client.stub.KeepAliveService::class.java)) }
        }
    }

    @Test fun quickRestartAndSystemTimeoutStopWithoutAutomaticallyStartingAnotherTask() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val pauses = AtomicInteger()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(AiTaskService.OPEN_TASK)).use { screen ->
            lateinit var first: AiTaskService.Session
            lateinit var second: AiTaskService.Session
            screen.onActivity { first = AiTaskService.start(it) { pauses.incrementAndGet() } }
            first.awaitStarted()
            screen.onActivity { first.close(); second = AiTaskService.start(it) { pauses.incrementAndGet() } }
            try {
                second.awaitStarted()
                assertEquals(0, pauses.get())
                screen.onActivity { second.service!!.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
                waitFor { running() == null }
                assertEquals(1, pauses.get()); assertTrue(second.closed.get())
                assertNull(running())
            } finally { first.close(); second.close() }
        }
    }

    @Test fun androidAnnouncementDatesPreserveEquivalentNumbersAndCacheTokens() {
        val p = ProtectedTranslation("Period: Nov. 11th 2026 (KST)\nWALPURGISNACHT-X")
        val translated = p.restore("期间：⟦P1⟧年11月⟦P0⟧日（KST）⟦P2⟧第10届瓦尔普吉斯之夜")
        assertEquals("期间：2026年11月11日（KST）\n第10届瓦尔普吉斯之夜", translated)
        assertEquals(translated, p.restore(p.maskTranslated(translated)))
    }
}
