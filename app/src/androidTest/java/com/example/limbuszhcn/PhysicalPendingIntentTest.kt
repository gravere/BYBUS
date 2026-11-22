package com.example.limbuszhcn

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhysicalPendingIntentTest {
    @Test fun productionSystemCallsUsePhysicalProfileAndKeepVirtualDestinations() {
        val user = Process.myUid() / 100000
        assumeTrue("Requires a secondary Android user", user > 0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resultFile = File(context.filesDir, "physical-profile-result.json")
        resultFile.delete()
        context.sendBroadcast(Intent().setComponent(ComponentName(context.packageName, PackageQueryProbeReceiver::class.java.name))
            .putExtra("stage", "physical-profile"))
        val until = SystemClock.elapsedRealtime() + 30_000
        var result: JSONObject? = null
        while (SystemClock.elapsedRealtime() < until && result == null) {
            result = runCatching { JSONObject(resultFile.readText()) }.getOrNull()
            if (result == null) SystemClock.sleep(100)
        }
        assertNotNull("container framework probe timed out", result)
        assertTrue(result.toString(), result!!.optBoolean("passed"))
        assertEquals(50, result.getInt("checks"))
        assertEquals(user, result.getInt("physicalUser"))
        assertEquals(Process.myUid(), result.getInt("uid"))
    }
}
