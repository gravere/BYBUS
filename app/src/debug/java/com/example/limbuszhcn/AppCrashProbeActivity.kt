package com.example.limbuszhcn

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONObject
import java.io.File

/** Explicit crash in a disposable emulator only; neither component is present in Release. */
open class AppCrashProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = if (this is ServerCrashProbeActivity) "server" else "host"
        File(filesDir, "crash-probe-$name.json").writeText(JSONObject()
            .put("pid", Process.myPid()).put("owned_handler", PersistentDiagnosticLog.ownsExceptionHandler())
            .put("expected_crash", true).toString())
        Handler(Looper.getMainLooper()).postDelayed({
            throw IllegalStateException("BYBUS_EXPECTED_DIAGNOSTIC_CRASH")
        }, 300)
    }
}

class ServerCrashProbeActivity : AppCrashProbeActivity()
