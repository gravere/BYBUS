package com.example.limbuszhcn

import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.limbuszhcn.translation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Framework jobs and local HTTP only; no phone, paid provider or game resource is involved. */
@RunWith(AndroidJUnit4::class)
class AiTransferJobTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scheduler get() = context.getSystemService(JobScheduler::class.java)
    private fun shell(command: String): String {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 34)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }
    private fun waitFor(check: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!check() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue(check())
    }
    private fun count(name: String) = java.net.URL("http://10.0.2.2:18765/count/$name").readText().trim().toInt()
    private fun screen() = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(AiTaskService.OPEN_TASK))

    @Test fun currentAndNextRequestCompleteAfterHomeAndScreenOff() {
        val name = "uidt-${System.nanoTime()}"
        val executor = Executors.newSingleThreadExecutor()
        val result = CompletableFuture<List<String>>()
        val control = AiRequestControl()
        screen().use { screen ->
            lateinit var session: AiTaskService.Session
            screen.onActivity { activity ->
                session = AiTaskService.start(activity) { control.stop() }
                session.execute(executor, Runnable {
                    try {
                        session.awaitStarted()
                        AiNetworkGuard(context).use { network ->
                            val client = AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _, key, body ->
                                network.post("http://10.0.2.2:18765/stream/$name", key, body, control) {}
                            })
                            result.complete((1..2).map { client.translate(listOf(AiText("x", "接続中"))).translations.getValue("x") })
                        }
                    } catch (error: Throwable) { result.completeExceptionally(error) }
                })
            }
            try {
                waitFor { count(name) == 1 }
                assertTrue(scheduler.getPendingJob(session.jobId)!!.isUserInitiated)
                assertNotNull(session.jobNetwork)
                assertNull(session.service) // No extra foreground service or dummy keepalive.
                screen.moveToState(Lifecycle.State.CREATED)
                shell("input keyevent 223")
                assertEquals(listOf("连接中", "连接中"), result.get(35, TimeUnit.SECONDS))
                assertFalse(context.getSystemService(PowerManager::class.java).isInteractive)
                assertEquals(2, count(name))
            } finally { control.stop(); session.close(); executor.shutdownNow(); shell("input keyevent 224") }
            waitFor { scheduler.getPendingJob(session.jobId) == null }
            waitFor { context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 4107 } }
        }
    }

    @Test fun systemStopPausesRequestAndDoesNotRescheduleOrRetry() {
        val name = "uidt-stop-${System.nanoTime()}"
        val executor = Executors.newSingleThreadExecutor()
        val result = CompletableFuture<Boolean>()
        val control = AiRequestControl()
        val pauses = AtomicInteger()
        screen().use { screen ->
            lateinit var session: AiTaskService.Session
            screen.onActivity { activity ->
                session = AiTaskService.start(activity) { pauses.incrementAndGet(); control.stop() }
                session.execute(executor, Runnable {
                    try {
                        session.awaitStarted()
                        AiNetworkGuard(context).use { it.post("http://10.0.2.2:18765/stream/$name", "fixture", "{\"stream\":true}", control) {} }
                        result.complete(false)
                    } catch (error: AiRequestCancelled) { result.complete(error.sent) }
                    catch (error: Throwable) { result.completeExceptionally(error) }
                    finally { session.close() }
                })
            }
            try {
                waitFor { count(name) == 1 }
                val stopped = shell("cmd jobscheduler timeout ${context.packageName} ${session.jobId}")
                assertTrue(stopped, stopped.contains("Stopping"))
                assertTrue(result.get(8, TimeUnit.SECONDS))
                assertEquals(1, pauses.get())
                waitFor { scheduler.getPendingJob(session.jobId) == null }
                assertEquals(1, count(name))
            } finally { control.stop(); session.close(); executor.shutdownNow() }
        }
    }

    @Test fun pauseBeforeSystemStartRunsCleanupWithoutSendingAndStaleCallbackCannotStopNextSession() {
        val executor = Executors.newSingleThreadExecutor()
        val result = CompletableFuture<Boolean>()
        val pauses = AtomicInteger()
        screen().use { screen ->
            lateinit var old: AiTaskService.Session
            lateinit var next: AiTaskService.Session
            screen.onActivity { activity ->
                old = AiTaskService.start(activity) { pauses.incrementAndGet() }
                old.execute(executor, Runnable {
                    try { old.awaitStarted(); result.complete(false) }
                    catch (_: java.util.concurrent.CancellationException) { result.complete(true) }
                    finally { old.close() }
                })
                old.pause() // The main looper has not delivered onStartJob yet.
            }
            try {
                assertTrue(result.get(5, TimeUnit.SECONDS))
                waitFor { old.closed.get() }
                screen.onActivity { next = AiTaskService.start(it) { fail("Stale notification paused a new task") } }
                next.awaitStarted()
                screen.onActivity {
                    it.sendBroadcast(Intent(it, AiTaskService.PauseReceiver::class.java).setAction(AiTaskService.PAUSE).putExtra("session", old.id))
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertFalse(next.paused.get())
                assertNotNull(scheduler.getPendingJob(next.jobId))
                old.close()
                assertNotNull(scheduler.getPendingJob(next.jobId))
                assertEquals(1, pauses.get())
                next.close()
                waitFor { scheduler.getPendingJob(next.jobId) == null }
            } finally { old.close(); AiTaskService.currentSession()?.close(); executor.shutdownNow() }
        }
    }

    @Test fun restoredSystemJobWithoutAnExplicitSessionDoesNotStartWork() {
        val jobId = 1420001
        screen().use { screen ->
            screen.onActivity {
                assertNull(AiTaskService.currentSession())
                val job = JobInfo.Builder(jobId, ComponentName(it, AiTransferJobService::class.java))
                    .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setExtras(PersistableBundle().apply { putString("session", "expired-session") }).build()
                assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(job))
            }
            try {
                waitFor { scheduler.getPendingJob(jobId) == null }
                assertNull(AiTaskService.currentSession())
                assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 4107 })
            } finally { scheduler.cancel(jobId) }
        }
    }

    @Test fun delayedPauseFromOldSessionCannotInterruptItsReplacement() {
        val pauses = AtomicInteger()
        screen().use { screen ->
            lateinit var current: AiTaskService.Session
            screen.onActivity { activity ->
                val old = AiTaskService.start(activity) { pauses.incrementAndGet() }
                Thread { old.pause() }.apply { start(); join(2000); assertFalse(isAlive) }
                // The old pause callback is queued on this main thread, behind this closure.
                old.close()
                current = AiTaskService.start(activity) { pauses.incrementAndGet() }
            }
            try {
                current.awaitStarted()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(0, pauses.get())
                assertFalse(current.paused.get())
            } finally { current.close() }
        }
    }
}
