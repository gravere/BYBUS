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
    @Test fun runningBuildAndNetworkSummaryMatchInstalledHost() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val installed = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        assertEquals(installed, RunningBuild.code(context))
        assertEquals(installed, context.resources.getInteger(R.integer.app_version_code).toLong())
        val network = gameNetworkSnapshot(context)
        assertTrue(network, network.startsWith("networkSnapshot=available"))
        assertTrue(network, network.contains("vpnVisibleForHost="))
        assertFalse(network.contains("ssid", ignoreCase = true))
        assertFalse(network.contains("192.168."))
    }

    @Test fun hostCannotRequestAnotherProcessThroughGuestCollector() {
        // Actual Binder call to the existing server: the host is not a registered guest.
        assertNull(requestGameFaultSnapshot(0))
    }

    @Test fun runtimeFaultsRemainInPersistentExportAfterTheCollectorAdvances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PersistentDiagnosticLog.install(context)
        // The test is not a registered guest PID. Exercise the same collector directly
        // in this unhooked process; provider identity rejection is checked separately.
        val sampler = GameFaultDiagnostics(context) { collectGameFaultMarkers(Process.myPid(), it) }
        val privateMessage = "PRIVATE_FAULT_TEST_SENTINEL_ACCOUNT"
        Log.e("Unity", "NullReferenceException: $privateMessage")
        Log.e("Unity", "at StageScript_Cromer.CheckTrigger (System.String $privateMessage)")
        Log.w("Unity", "InvalidCastException: $privateMessage")
        Log.e("Unity", "at SkillButton.OnPointerEnter (PointerEventData $privateMessage)")
        Log.w("LimbusIntegrity", "report code=50025 callerOffset=75940 forwarded=1")
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
                assertTrue(persistent.contains("event=managed_exception type=InvalidCastException"))
                assertTrue(persistent.contains("event=managed_frame method=SkillButton.OnPointerEnter"))
                assertTrue(persistent.contains("event=game_report_site code=50025 callerOffset=75940 forwarded=1"))
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
        Log.e("chromium", "[ERROR:jni_android.cc(306)] java.lang.SecurityException: PRIVATE_JAVA_SENTINEL_ACCOUNT does not belong to uid 123")
        Log.w("System.err", "    at android.app.IActivityManager\$Stub\$Proxy.bindServiceInstance(IActivityManager.java:5219)")
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
                // A bounded read can close the pipe before logcat exits. Required events
                // still must be present, but the archive must report that as incomplete.
                val expected = if (timeline.contains("\ncollection=incomplete\n"))
                    "collected_incomplete" else "collected_bounded"
                assertTrue(status, status.contains("gameEvents=$expected"))
                assertTrue(status.contains("signalSender=not_exposed_by_ApplicationExitInfo"))
                assertTrue(status.contains("missingEvidence=not_a_success_result"))
                val runtime = zip.getInputStream(zip.getEntry("runtime.txt")).bufferedReader().use { it.readText() }
                assertTrue(runtime.contains("runtimeLaunchAppVersionCode="))
                assertTrue(runtime.contains("runtimeLaunchRelation="))
                assertTrue(runtime.contains("runningAppVersionCode=${RunningBuild.code(context)}"))
                val javaFaults = zip.getInputStream(zip.getEntry("java-faults.txt")).bufferedReader().use { it.readText() }
                // EOF and process exit can race on a cold emulator. Required
                // markers must survive in either case; never relabel a partial read.
                val javaCollection = javaFaults.lineSequence().first { it.startsWith("collection=") }
                assertTrue(javaFaults, javaCollection in setOf("collection=available", "collection=incomplete"))
                if (javaCollection == "collection=incomplete")
                    assertTrue(javaFaults.contains("exitCode="))
                assertTrue(javaFaults.contains("type=java.lang.SecurityException category=package_uid_mismatch"))
                assertTrue(javaFaults.contains("method=android.app.IActivityManager\$Stub\$Proxy.bindServiceInstance"))
                assertFalse(javaFaults.contains("PRIVATE_JAVA_SENTINEL_ACCOUNT"))
            }
            assertTrue("Export must not wait indefinitely for logcat", SystemClock.elapsedRealtime() - started < 20_000)
        } finally { archive.delete() }
    }
}
