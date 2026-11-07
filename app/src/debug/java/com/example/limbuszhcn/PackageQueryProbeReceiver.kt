package com.example.limbuszhcn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lody.virtual.client.hook.proxies.pm.PackageQueryProbe
import org.json.JSONObject
import java.io.File

/** Disposable emulator only; absent from release builds. */
class PackageQueryProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val stage = intent.getStringExtra("stage")
        Thread {
            try {
                val result = runCatching {
                    when (stage) {
                        "framework-prepare" -> PackageQueryProbe.frameworkRegistry(context, true)
                        "framework-cleanup" -> PackageQueryProbe.frameworkRegistry(context, false)
                        else -> PackageQueryProbe.inspect(context)
                    }
                }.getOrElse {
                    JSONObject().put("passed", false).put("error", it.stackTraceToString().take(8000))
                }
                val stem = when (stage) {
                    "framework-prepare", "framework-cleanup" -> stage
                    else -> "package-query"
                }
                File(context.filesDir, "$stem-result.json").writeText(result.toString())
            } finally { pending.finish() }
        }.start()
    }
}
