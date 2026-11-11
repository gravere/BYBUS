package com.example.limbuszhcn

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.*
import android.widget.TextView
import com.example.limbuszhcn.container.AndroidContainerRuntimeFactory
import com.example.limbuszhcn.container.VirtualAppContainerRuntime
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.ipc.VPackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Disposable emulator only. Real bundled service; no accounts, SKU queries or purchase intents. */
class GoogleReadinessProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val output = TextView(this).apply { text = "GOOGLE_READINESS_PENDING" }
        setContentView(output)
        // Returning after a guest rotation must not launch a second login task.
        if (state != null) { output.text = "GOOGLE_READINESS_RESTORED"; return }
        Thread {
            val result = runCatching {
                val runtime = AndroidContainerRuntimeFactory.create(applicationContext,
                    filesDir.toPath().resolve("container-workspace")) as VirtualAppContainerRuntime
                when (intent.getStringExtra("stage")) {
                    "session-snapshot", "session-resume" -> {
                        val session = com.example.limbuszhcn.container.VirtualAppSession(applicationContext, "com.google.android.gms")
                        val before = session.snapshot()
                        val resumed = if (intent.getStringExtra("stage") == "session-resume") session.resume() else false
                        JSONObject().put("result", "session_observed").put("resumed", resumed)
                            .put("pids", JSONArray(before.pids.sorted()))
                            .put("tasks", JSONArray(before.resumableTasks.map { it.id }))
                    }
                    "login" -> {
                        runtime.googlePaymentState()
                        val target = Intent().setComponent(ComponentName("com.google.android.gms",
                            "org.microg.gms.auth.login.LoginActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val info = requireNotNull(VirtualCore.get().resolveActivityInfo(target, 0))
                        check(VActivityManager.get().startActivity(target, info, null, null, null, 0, 0) == 0)
                        JSONObject().put("result", "login_opened")
                    }
                    "settings" -> { runtime.openGoogleSettings(); JSONObject().put("result", "settings_opened") }
                    else -> {
                        val started = SystemClock.elapsedRealtime()
                        val first = runtime.googlePaymentState(waitForRegistration = true)
                        check(!first.hasAccount && first.billingEnabled && first.registrationEnabled)
                        check(first.registered) { "real Google device check-in did not complete" }
                        val registrationMs = SystemClock.elapsedRealtime() - started
                        val defaults = capability()
                        check(defaults.all { it == 0 }) { "fresh install defaults unavailable" }
                        disableForUpgradeFixture()
                        val disabled = capability()
                        check(disabled.all { it == 3 }) { "disabled service must return its real error" }
                        val enabled = runtime.googlePaymentState()
                        check(enabled.billingEnabled && enabled.registrationEnabled && !enabled.hasAccount)
                        val available = capability()
                        check(available.all { it == 0 }) { "enabled service capability still unavailable" }
                        val reread = runtime.googlePaymentState()
                        check(reread.billingEnabled && reread.registrationEnabled && !reread.hasAccount)
                        JSONObject().put("result", "passed").put("disabled_codes", JSONArray(disabled))
                            .put("device_registered", first.registered).put("registration_elapsed_ms", registrationMs)
                            .put("default_codes", JSONArray(defaults)).put("automatic_upgrade_repair", true)
                            .put("enabled_codes", JSONArray(available)).put("accounts_accessed", false)
                            .put("purchase_requested", false).put("provider_readback", true)
                    }
                }
            }.getOrElse { JSONObject().put("result", "failed").put("error", it.stackTraceToString()) }
            File(filesDir, "google-readiness.json").writeText(result.toString(2))
            runOnUiThread { output.text = "GOOGLE_READINESS_" + result.getString("result").uppercase() }
        }.start()
    }

    private fun disableForUpgradeFixture() {
        val authority = "com.google.android.gms.microg.settings"
        val info = requireNotNull(VPackageManager.get().resolveContentProvider(authority, 0, 0))
        check(info.packageName == "com.google.android.gms")
        val provider = requireNotNull(VActivityManager.get().acquireProviderClient(0, info))
        val client = requireNotNull(if (Build.VERSION.SDK_INT >= 29)
            mirror.android.content.ContentProviderClientQ.ctor.newInstance(contentResolver, provider, authority, false)
        else mirror.android.content.ContentProviderClientJB.ctor.newInstance(contentResolver, provider, false))
        client.use {
            it.update(Uri.parse("content://$authority/vending"), ContentValues().apply { put("vending_billing", false) }, null, null)
            it.update(Uri.parse("content://$authority/check-in"), ContentValues().apply { put("checkin_enable_service", false) }, null, null)
        }
    }

    private fun capability(): List<Int> {
        val target = Intent("com.android.vending.billing.InAppBillingService.BIND").setPackage("com.android.vending")
        val info = requireNotNull(VPackageManager.get().resolveService(target, null, 0, 0)?.serviceInfo)
        val connectionToken = Binder()
        val proxy = requireNotNull(VActivityManager.get().bindService(0, target, info, connectionToken, Context.BIND_AUTO_CREATE))
        val connected = CountDownLatch(1)
        var remote: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { remote = service; connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
            override fun onNullBinding(name: ComponentName) { connected.countDown() }
        }
        check(bindService(proxy, connection, Context.BIND_AUTO_CREATE))
        try {
            check(connected.await(35, TimeUnit.SECONDS)) { "billing bind timed out" }
            val service = requireNotNull(remote)
            val descriptor = "com.android.vending.billing.IInAppBillingService"
            check(service.interfaceDescriptor == descriptor)
            return listOf(3 to "inapp", 27 to "inapp", 3 to "subs", 27 to "subs").map { (api, type) ->
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(descriptor)
                    data.writeInt(api); data.writeString("com.ProjectMoon.LimbusCompany"); data.writeString(type)
                    check(service.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
                    reply.readException(); reply.readInt()
                } finally { data.recycle(); reply.recycle() }
            }
        } finally {
            unbindService(connection)
            VActivityManager.get().unbindService(0, connectionToken)
        }
    }
}
