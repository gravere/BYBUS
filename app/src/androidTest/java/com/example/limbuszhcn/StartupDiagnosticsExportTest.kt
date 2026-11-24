package com.example.limbuszhcn

import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.limbuszhcn.container.ContainerStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile

/** Synthetic events on a disposable emulator; never starts or diagnoses a real guest game. */
@RunWith(AndroidJUnit4::class)
class StartupDiagnosticsExportTest {
    @Test fun ownUidEventsAndCollectionStatusReachOneArchiveWithoutRawSdkPayloads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val game = "com.ProjectMoon.LimbusCompany"
        Log.i("LimbusVA", "VClient.bindApplication before makeApplication package=$game process=$game info=diagnostic-fixture")
        Log.e("AppSealing", "!!! Kill Process ........[99001]")
        Log.e("AppSealing", "PRIVATE_TEST_SENTINEL_ACCOUNT")
        val started = SystemClock.elapsedRealtime()
        val archive = DebugLogExporter(context).export(ContainerStatus(true, false, "diagnostic fixture"), null)
        try {
            ZipFile(archive).use { zip ->
                val timeline = zip.getInputStream(zip.getEntry("game-startup.txt")).bufferedReader().use { it.readText() }
                assertTrue(timeline.contains("pid=${Process.myPid()} event=game_process_bound role=main"))
                assertTrue(timeline.contains("pid=${Process.myPid()} event=game_reported_exit code=99001"))
                assertFalse(timeline.contains("PRIVATE_TEST_SENTINEL_ACCOUNT"))
                val status = zip.getInputStream(zip.getEntry("capture-status.txt")).bufferedReader().use { it.readText() }
                assertTrue(status.contains("scope=own_uid_only"))
                assertTrue(status.contains("gameEvents=collected_bounded"))
                assertTrue(status.contains("signalSender=not_exposed_by_ApplicationExitInfo"))
                assertTrue(status.contains("missingEvidence=not_a_success_result"))
            }
            assertTrue("Export must not wait indefinitely for logcat", SystemClock.elapsedRealtime() - started < 20_000)
        } finally { archive.delete() }
    }
}
