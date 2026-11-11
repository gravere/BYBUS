package com.example.limbuszhcn.container

import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
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

    /** Local, idempotent defaults after verified component installation; no network wait. */
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
        if (!state.registered) {
            VActivityManager.get().sendBroadcast(Intent().setComponent(ComponentName("com.google.android.gms",
                "org.microg.gms.checkin.TriggerReceiver")), 0)
        }
        return state
    }

    private companion object {
        const val AUTHORITY = "com.google.android.gms.microg.settings"
        const val BILLING = "vending_billing"
        const val ENABLED = "checkin_enable_service"
        val VENDING: Uri = Uri.parse("content://$AUTHORITY/vending")
        val CHECKIN: Uri = Uri.parse("content://$AUTHORITY/check-in")
    }
}
