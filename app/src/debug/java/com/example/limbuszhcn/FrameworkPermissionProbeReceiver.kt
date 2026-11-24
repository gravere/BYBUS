package com.example.limbuszhcn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lody.virtual.client.hook.proxies.pm.FrameworkPermissionProbe
import com.lody.virtual.client.hook.proxies.pm.NativePackageQueryProbe
import org.json.JSONObject
import java.io.File

/** Emulator fixture only; no guest application, resources, accounts or purchases are opened. */
class FrameworkPermissionProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val result = runCatching {
                    when (intent.getStringExtra("stage")) {
                        "package-interface" -> PackageInterfaceProbe.inspect(context)
                        "package-jni" -> NativePackageQueryProbe.inspect(context)
                        "component-settings" -> com.lody.virtual.client.hook.proxies.pm.ComponentSettingsProbe.inspect(context)
                        else -> FrameworkPermissionProbe.inspect(context)
                    }
                }.getOrElse {
                    JSONObject().put("passed", false).put("error", it.stackTraceToString().take(8000))
                }
                File(context.filesDir, "framework-permission-result.json").writeText(result.toString())
            } finally { pending.finish() }
        }.start()
    }
}
