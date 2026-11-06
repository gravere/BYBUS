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
        Thread {
            try {
                val result = runCatching { PackageQueryProbe.inspect(context) }.getOrElse {
                    JSONObject().put("passed", false).put("error", it.stackTraceToString().take(8000))
                }
                File(context.filesDir, "package-query-result.json").writeText(result.toString())
            } finally { pending.finish() }
        }.start()
    }
}
