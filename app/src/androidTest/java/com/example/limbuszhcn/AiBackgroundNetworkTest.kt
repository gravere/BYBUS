package com.example.limbuszhcn

import android.content.Intent
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.limbuszhcn.translation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uses only the isolated emulator and a local delayed-response fixture, never a paid provider. */
@RunWith(AndroidJUnit4::class)
class AiBackgroundNetworkTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String): String {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }
    private fun waitFor(check: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 8000
        while (!check() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
        assertTrue(check())
    }
    private fun received(name: String): Int = java.net.URL("http://10.0.2.2:18765/count/$name").readText().trim().toInt()

    @Test fun responseCompletesWhileScreenOffInDozeWhenBackgroundAccessIsAllowed() {
        val name = "doze-${System.nanoTime()}"
        val executor = Executors.newSingleThreadExecutor()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(AiTaskService.OPEN_TASK)).use { screen ->
            val control = AiRequestControl()
            lateinit var session: AiTaskService.Session
            screen.onActivity { session = AiTaskService.start(it) { control.stop() } }
            session.awaitStarted()
            AiNetworkGuard(context).use { network ->
                try {
                    shell("dumpsys deviceidle whitelist +${context.packageName}")
                    assertTrue(AiBackgroundAccess.allowed(context))
                    screen.moveToState(Lifecycle.State.CREATED)
                    val future = executor.submit<String> { network.post("http://10.0.2.2:18765/delay/$name?seconds=8", "fixture", "{}", control) {} }
                    waitFor { received(name) == 1 }
                    shell("dumpsys battery unplug")
                    shell("input keyevent 223")
                    shell("dumpsys deviceidle force-idle")
                    val power = context.getSystemService(PowerManager::class.java)
                    assertTrue(power.isDeviceIdleMode)
                    assertFalse(power.isInteractive)
                    assertEquals("{\"ok\":true}", future.get(20, TimeUnit.SECONDS))
                    assertFalse(power.isInteractive)
                    assertEquals(1, received(name))
                } finally {
                    control.stop(); session.close(); executor.shutdownNow()
                    shell("dumpsys deviceidle unforce"); shell("dumpsys battery reset")
                    shell("input keyevent 224"); shell("dumpsys deviceidle whitelist -${context.packageName}")
                }
            }
        }
    }

    @Test fun notificationStopInterruptsPendingResponseWithoutRetryingOrDiscardingTask() {
        val name = "stop-${System.nanoTime()}"
        val executor = Executors.newSingleThreadExecutor()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(AiTaskService.OPEN_TASK)).use { screen ->
            val control = AiRequestControl()
            lateinit var session: AiTaskService.Session
            screen.onActivity { session = AiTaskService.start(it) { control.stop() } }
            session.awaitStarted()
            AiNetworkGuard(context).use { network ->
                try {
                    val future = executor.submit<Boolean> {
                        try { network.post("http://10.0.2.2:18765/delay/$name?seconds=30", "fixture", "{}", control) {}; false }
                        catch (error: AiRequestCancelled) { error.sent }
                    }
                    waitFor { received(name) == 1 }
                    val manager = context.getSystemService(android.app.NotificationManager::class.java)
                    // Android may add a group-summary notification on the same channel; it has no controls.
                    waitFor { manager.activeNotifications.any { it.id == 4107 && it.notification.contentView != null } }
                    val notice = manager.activeNotifications.first { it.id == 4107 }.notification
                    screen.onActivity { activity ->
                        val view = notice.contentView.apply(activity, android.widget.FrameLayout(activity))
                        assertTrue(view.findViewById<android.view.View>(R.id.ai_notice_stop).performClick())
                    }
                    assertTrue(future.get(5, TimeUnit.SECONDS))
                    assertEquals(1, received(name))
                    assertTrue(session.paused.get())
                } finally { control.stop(); session.close(); executor.shutdownNow() }
            }
        }
    }

    @Test fun knownDozeRestrictionIsReportedBeforeSendingAndCanBeStopped() {
        shell("dumpsys deviceidle whitelist -${context.packageName}")
        shell("dumpsys battery unplug"); shell("input keyevent 223"); shell("dumpsys deviceidle force-idle")
        try {
            assertFalse(AiBackgroundAccess.allowed(context))
            AiNetworkGuard(context).use { network ->
                assertEquals("系统休眠限制联网，等待恢复", network.waitingReason())
                val control = AiRequestControl()
                var waiting = false
                assertThrows(AiRequestCancelled::class.java) {
                    network.awaitReady(control) { waiting = true; control.stop() }
                }
                assertTrue(waiting)
            }
        } finally { shell("dumpsys deviceidle unforce"); shell("dumpsys battery reset"); shell("input keyevent 224") }
    }
}
