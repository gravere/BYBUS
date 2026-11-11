package com.example.limbuszhcn.container

import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import com.example.limbuszhcn.PersistentDiagnosticLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.lody.virtual.client.ipc.VAccountManager
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.client.stub.StubManifest
import com.lody.virtual.helper.compat.MicrogBillingIdentityPolicy

data class GooglePaymentState(val billingEnabled: Boolean, val registrationEnabled: Boolean,
    val registered: Boolean, val hasAccount: Boolean)

/** Manage only the installed container's public settings, through its single owning provider. */
internal class GooglePaymentSetup(private val context: Context) {
    private fun <T> withClient(block: (ContentProviderClient) -> T): T {
        val info = requireNotNull(VPackageManager.get().resolveContentProvider(AUTHORITY, 0, 0)) {
            "容器 Google 设置暂不可用"
        }
        require(MicrogBillingIdentityPolicy.isGoogleSettingsProvider(info.packageName, info.name, info.authority))
        val process = requireNotNull(VActivityManager.get().initProcess(info.packageName, info.processName, 0,
            VActivityManager.PROCESS_TYPE_PROVIDER)) { "无法准备容器 Google 设置" }
        check(process.packageName == info.packageName && process.processName == info.processName &&
            process.vpid in 0 until StubManifest.STUB_COUNT) { "容器 Google 进程信息不一致" }
        // A raw virtual Binder does not tell Android that its owner is in use. Hold the real
        // stub provider for the whole operation so a cached/frozen owner is resumed by AMS.
        return requireNotNull(context.contentResolver.acquireContentProviderClient(
            StubManifest.getStubAuthority(process.vpid, process.is64Bit))) {
            "无法唤醒容器 Google 设置"
        }.use {
            val provider = requireNotNull(VActivityManager.get().acquireProviderClient(0, info)) {
                "无法连接容器 Google 设置"
            }
            requireNotNull(if (Build.VERSION.SDK_INT >= 29)
                mirror.android.content.ContentProviderClientQ.ctor.newInstance(context.contentResolver, provider, AUTHORITY, false)
            else mirror.android.content.ContentProviderClientJB.ctor.newInstance(context.contentResolver, provider, false)) {
                "无法读取容器 Google 设置"
            }.use(block)
        }
    }

    fun read(): GooglePaymentState = withClient { read(it) }

    private fun read(client: ContentProviderClient): GooglePaymentState {
        val billing = requireNotNull(client.query(VENDING, arrayOf(BILLING), null, null, null)).use {
            check(it.count == 1 && it.columnCount == 1 && it.moveToFirst())
            it.getInt(0) != 0
        }
        val registration = requireNotNull(client.query(CHECKIN,
            arrayOf(ENABLED, "androidId", "lastCheckin"), null, null, null)).use {
            check(it.count == 1 && it.columnCount == 3 && it.moveToFirst())
            val enabled = it.getInt(0) != 0
            enabled to (enabled && it.getLong(1) != 0L && it.getLong(2) > 0L)
        }
        // Keep only account presence. Names and credentials never enter host state or logs.
        val account = VAccountManager.get().getAccounts(0, "com.google").isNotEmpty()
        return GooglePaymentState(billing, registration.first, registration.second, account)
    }

    /** Local defaults; start a real check-in without waiting on a screen refresh. */
    fun ensureEnabled(): GooglePaymentState {
        val state = withClient { client ->
            val before = read(client)
            if (!before.billingEnabled)
                client.update(VENDING, ContentValues().apply { put(BILLING, true) }, null, null)
            if (!before.registrationEnabled)
                client.update(CHECKIN, ContentValues().apply { put(ENABLED, true) }, null, null)
            val state = read(client)
            check(state.billingEnabled && state.registrationEnabled) { "付款设置未保存，请重试" }
            state
        }
        if (!state.registered) registrationAttempt()
        return state
    }

    /** Called on the launch/settings worker only. Warm registrations have no wait. */
    fun awaitRegistration(retry: Boolean = false): GooglePaymentState = withClient { client ->
        check(Looper.myLooper() != Looper.getMainLooper()) { "设备注册不能在主线程等待" }
        val before = read(client)
        if (before.registered || !before.registrationEnabled) return@withClient before
        val pending = registrationAttempt(retry)
        val remaining = (REGISTRATION_WAIT_MS - (SystemClock.elapsedRealtime() - pending.started)).coerceAtLeast(0)
        // Holding the owning provider here also keeps Android's cached-process freezer
        // from suspending it while the service performs the first check-in.
        pending.completed.await(remaining, TimeUnit.MILLISECONDS)
        read(client).also {
            PersistentDiagnosticLog.info("GooglePayment", "registration registered=${it.registered} callback=${pending.completed.count == 0L}")
        }
    }

    private fun registrationAttempt(retry: Boolean = false): RegistrationAttempt = synchronized(registrationLock) {
        val now = SystemClock.elapsedRealtime()
        attempt?.let {
            val age = now - it.started
            if (age < REGISTRATION_WAIT_MS || (!retry && age < RETRY_INTERVAL_MS)) return@synchronized it
        }
        RegistrationAttempt(now).also { pending ->
            attempt = pending
            val receiver = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    // A callback is a wake-up, never proof of registration. Read the
                    // real ID and success timestamp from the owning provider afterwards.
                    pending.completed.countDown()
                }
            }
            val service = Intent().setComponent(ComponentName("com.google.android.gms",
                "org.microg.gms.checkin.CheckinService"))
                .putExtra("force", true).putExtra("receiver", receiver)
            try {
                check(VActivityManager.get().startService(0, service) != null) { "Google 注册服务未启动" }
                PersistentDiagnosticLog.info("GooglePayment", "registration requested=true")
            } catch (error: Exception) {
                pending.completed.countDown()
                // Do not log service exceptions containing account/device identifiers.
                PersistentDiagnosticLog.info("GooglePayment", "registration service_unavailable=true")
            }
        }
    }

    private class RegistrationAttempt(val started: Long, val completed: CountDownLatch = CountDownLatch(1))

    private companion object {
        const val REGISTRATION_WAIT_MS = 12_000L
        const val RETRY_INTERVAL_MS = 60_000L
        val registrationLock = Any()
        var attempt: RegistrationAttempt? = null
        const val AUTHORITY = "com.google.android.gms.microg.settings"
        const val BILLING = "vending_billing"
        const val ENABLED = "checkin_enable_service"
        val VENDING: Uri = Uri.parse("content://$AUTHORITY/vending")
        val CHECKIN: Uri = Uri.parse("content://$AUTHORITY/check-in")
    }
}
