package com.example.limbuszhcn

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IInterface
import android.os.Process
import com.lody.virtual.client.hook.base.MethodProxy
import com.lody.virtual.client.hook.proxies.notification.NotificationManagerStub
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.remote.IntentSenderData
import mirror.android.app.ActivityManagerNative
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException

/** Runs in the initialized container server, not in the launcher's main process. Debug only. */
internal object FrameworkProfileProbe {
    fun inspect(context: Context): JSONObject {
        val user = Process.myUid() / 100000
        require(user > 0 && VActivityManager.get().systemPid == Process.myPid()) { "Requires a secondary-user container server" }
        var checks = 0
        fun verify(value: Boolean, message: String) { check(value) { message }; checks++ }
        val base = ActivityManagerNative.getDefault.call()
        val api = Class.forName("android.app.IActivityManager")
        for ((name, proxyName) in listOf("getIntentSender" to "GetIntentSender", "getIntentSenderWithFeature" to "GetIntentSenderWithFeature")) {
            val method = api.methods.single { it.name == name }
            val feature = name.endsWith("WithFeature")
            val proxy = Class.forName("com.lody.virtual.client.hook.proxies.am.MethodProxies\$$proxyName")
                .getDeclaredConstructor().apply { isAccessible = true }.newInstance() as MethodProxy
            for (type in listOf(4, 1, 2)) {
                val intent = Intent("bybus.profile.test.$name.$type").setComponent(ComponentName(context.packageName,
                    "com.lody.virtual.client.stub.ShadowPendingService"))
                val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                fun args(): Array<Any?> = if (feature) arrayOf(type, context.packageName, null, null, null, 0,
                    arrayOf(intent), null, flags, null, 0) else arrayOf(type, context.packageName, null, null, 0,
                    arrayOf(intent), null, flags, null, 0)
                val legacy = runCatching { method.invoke(base, *args()) }.exceptionOrNull()
                verify(legacy is InvocationTargetException && legacy.cause is SecurityException, "legacy $name must reproduce user0 denial")
                val sender = proxy.call(base, method, *args()) as IInterface
                try {
                    val pending = IntentSenderData.readPendingIntent(sender.asBinder())!!
                    verify(pending.creatorUid == Process.myUid(), "$name real creator UID")
                    verify(pending.creatorPackage == context.packageName, "$name real creator package")
                    verify(pending.isImmutable, "$name immutable preserved")
                    val record = VActivityManager.get().getIntentSender(sender.asBinder())!!
                    verify(record.userId == 0, "$name registry stays virtual")
                    verify(record.flags == flags, "$name flags preserved")
                    verify(record.intent.action == intent.action, "$name action preserved")
                    verify(record.intent.component == intent.component, "$name destination preserved")
                } finally {
                    IntentSenderData.readPendingIntent(sender.asBinder())!!.cancel()
                    VActivityManager.get().removeIntentSender(sender.asBinder())
                }
            }
        }
        val notification = NotificationManagerStub().invocationStub.proxyInterface
        val notificationApi = Class.forName("android.app.INotificationManager")
        verify(notificationApi.methods.single { it.name == "getAppActiveNotifications" }
            .invoke(notification, context.packageName, 0) != null, "notification query uses real profile")
        verify(notificationApi.methods.single { it.name == "getConversationNotificationChannel" }
            .invoke(notification, context.packageName, 0, context.packageName, "bybus-profile-test-absent", true, null) == null,
            "channel query uses real profile")
        return JSONObject().put("passed", true).put("checks", checks).put("physicalUser", user).put("uid", Process.myUid())
    }
}
