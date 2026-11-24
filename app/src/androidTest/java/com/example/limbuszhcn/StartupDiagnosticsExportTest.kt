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
import java.io.File
import java.util.zip.ZipFile

/** Synthetic events on a disposable emulator; never starts or diagnoses a real guest game. */
@RunWith(AndroidJUnit4::class)
class StartupDiagnosticsExportTest {
    @Test fun runtimeFaultsRemainInPersistentExportAfterTheCollectorAdvances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PersistentDiagnosticLog.install(context)
        val sampler = GameFaultDiagnostics(context)
        val privateMessage = "PRIVATE_FAULT_TEST_SENTINEL_ACCOUNT"
        Log.e("Unity", "NullReferenceException: $privateMessage")
        Log.e("Unity", "at StageScript_Cromer.CheckTrigger (System.String $privateMessage)")
        sampler.sample()
        sampler.sample() // overlapping windows must not duplicate the event
        val native = File(context.filesDir, "translation-cache/translation-index/termination-99999999.log")
        native.parentFile!!.mkdirs()
        native.writeText("seq=1 unixMs=1795512936987 pid=99999999 event=1 value=9 target=99999999 caller=123 disposition=0\n")
        val archive = DebugLogExporter(context).export(ContainerStatus(true, false, "diagnostic fixture"), null)
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val persistent = entries.filter { it.name.startsWith("persistent/") }.joinToString("\n") {
                    zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() }
                }
                assertTrue(persistent.contains("event=managed_exception type=NullReferenceException"))
                assertTrue(persistent.contains("event=managed_frame method=StageScript_Cromer.CheckTrigger"))
                assertFalse(persistent.contains(privateMessage))
                val trace = zip.getInputStream(zip.getEntry("native/${native.name}")).bufferedReader().use { it.readText() }
                assertTrue(trace.contains("event=1 value=9"))
                val manifest = zip.getInputStream(zip.getEntry("capture-files.txt")).bufferedReader().use { it.readText() }
                assertTrue(manifest.contains(native.name))
            }
        } finally { archive.delete(); native.delete() }
    }

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
