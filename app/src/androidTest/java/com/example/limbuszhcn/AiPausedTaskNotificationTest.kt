package com.example.limbuszhcn

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.limbuszhcn.translation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only a private emulator task; no endpoint, API key or game data is configured. */
@RunWith(AndroidJUnit4::class)
class AiPausedTaskNotificationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun waitFor(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!check() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(check())
    }
    @Suppress("DEPRECATION")
    private fun running() = context.getSystemService(ActivityManager::class.java).getRunningServices(50)
        .any { it.service.className == AiTaskService::class.java.name }
    private fun fixture() = AiTaskSnapshot("paused-fixture", AiConfig(limit = 49), "zero", "来源",
        "c".repeat(32), "汉化", null, phase = AiTaskPhase.Paused,
        progress = AiProgress(8, 49, reused = 1, elapsedMillis = 171_000),
        plan = AiTaskPlan(mapOf("jp/JP_Test.json" to (0..48).map { it.toString().padStart(64, '0') }.toSet()),
            mapOf("jp/JP_Test.json" to "d".repeat(64)), 49))

    @Test fun pauseKeepsFrozenNotificationWithoutAServiceAndRestartReplacesIt() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(AiTaskService.OPEN_TASK)).use { screen ->
            lateinit var first: AiTaskService.Session
            var second: AiTaskService.Session? = null
            screen.onActivity { first = AiTaskService.start(it) { } }
            try {
                first.awaitStarted()
                first.finish(fixture())
                waitFor { !running() && manager.activeNotifications.none { it.id == 4107 } &&
                    manager.activeNotifications.any { it.id == AiPausedTaskNotification.ID } }
                val notice = manager.activeNotifications.first { it.id == AiPausedTaskNotification.ID }.notification
                assertEquals(0, notice.flags and Notification.FLAG_FOREGROUND_SERVICE)
                assertEquals(0, notice.flags and Notification.FLAG_ONGOING_EVENT)
                assertTrue(notice.actions.isNullOrEmpty())
                assertFalse(notice.extras.containsKey(Notification.EXTRA_PROGRESS_MAX))
                screen.onActivity { activity ->
                    for (layout in listOf(notice.contentView, notice.bigContentView)) {
                        val view = layout.apply(activity, FrameLayout(activity))
                        assertEquals("02:51", view.findViewById<TextView>(R.id.ai_notice_time).text.toString())
                        assertEquals("继续", view.findViewById<TextView>(R.id.ai_notice_stop).text.toString())
                        assertEquals("取消", view.findViewById<TextView>(R.id.ai_notice_cancel).text.toString())
                        assertEquals(9, view.findViewById<android.widget.ProgressBar>(R.id.ai_notice_progress).progress)
                    }
                    second = AiTaskService.start(activity) { }
                }
                requireNotNull(second).awaitStarted()
                waitFor { manager.activeNotifications.none { it.id == AiPausedTaskNotification.ID } }
                // A delayed finish from the previous session cannot cover a new active task.
                first.finish(fixture())
                assertTrue(running())
                assertTrue(manager.activeNotifications.none { it.id == AiPausedTaskNotification.ID })
                requireNotNull(second).finish(null)
                waitFor { !running() && manager.activeNotifications.none { it.id in setOf(4107, 4108) } }
            } finally {
                first.close()
                second?.close()
                AiTaskService.restorePaused(context, null)
            }
        }
    }

    /** Run seed then force-stop the emulator app, then recoveredActions. */
    @Test fun seedRecovery() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val store = AiTaskStore(context.filesDir.toPath().resolve("translation-cache"))
        check(!store.exists())
        val task = store.create(AiConfig(limit = 49), TranslationSources.OFFICIAL, null, null, identityOverride = "c".repeat(32))
        store.write(task.copy(progress = fixture().progress, plan = fixture().plan))
    }

    @Test fun recoveredActionsOpenTheSavedTaskAndCancelOnlyAfterConfirmation() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val store = AiTaskStore(context.filesDir.toPath().resolve("translation-cache"))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(AiTaskService.OPEN_TASK)).use { screen ->
            try {
                waitFor { store.read()?.phase == AiTaskPhase.Paused && manager.activeNotifications.any { it.id == 4108 } }
                assertFalse(running()) // Recovery never starts a paid request or foreground service.
                val task = requireNotNull(store.read())
                val notice = manager.activeNotifications.first { it.id == 4108 }.notification
                assertFalse(AiSettingsStore(context).hasKey())
                // Explicit resume reaches the task, then pauses at the missing-key guard.
                // This exercises cold-start/onResume ordering without sending an API request.
                screen.onActivity { activity ->
                    notice.contentView.apply(activity, FrameLayout(activity)).findViewById<android.view.View>(R.id.ai_notice_stop).performClick()
                }
                waitFor { store.read()?.error != null && !running() && manager.activeNotifications.any { it.id == 4108 } }
                assertEquals(task.id, store.read()!!.id)
                assertEquals(0, store.read()!!.progress.requests)
                screen.onActivity { activity ->
                    val model = LaunchCoordinator.get(activity.application)
                    model.requestAiTask(cancelTaskId = "outdated-task")
                    assertNull(model.state.aiCancelPrompt)
                    notice.contentView.apply(activity, FrameLayout(activity)).findViewById<android.view.View>(R.id.ai_notice_cancel).performClick()
                }
                val model = LaunchCoordinator.get(context.applicationContext as android.app.Application)
                waitFor { model.state.aiCancelPrompt?.taskId == task.id }
                assertTrue(store.exists())
                assertFalse(running())
                screen.onActivity { model.confirmAiCancellation() }
                waitFor { !store.exists() && manager.activeNotifications.none { it.id == 4108 } }
                // Stale continue/cancel controls cannot start a new task or resurrect a deleted task.
                screen.onActivity { activity ->
                    val view = notice.contentView.apply(activity, FrameLayout(activity))
                    view.findViewById<android.view.View>(R.id.ai_notice_stop).performClick()
                }
                Thread.sleep(300)
                assertFalse(running()); assertFalse(store.exists()); assertNull(model.state.aiCancelPrompt)
            } finally {
                store.discard()
                AiTaskService.restorePaused(context, null)
                screen.onActivity { it.intent = Intent(context, MainActivity::class.java).setAction(AiTaskService.OPEN_TASK) }
            }
        }
    }
}
