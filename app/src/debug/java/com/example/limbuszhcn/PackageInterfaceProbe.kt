package com.example.limbuszhcn

import android.content.Context
import android.content.pm.PackageInfo
import android.os.IInterface
import android.os.Process
import com.lody.virtual.client.core.InvocationStubManager
import com.lody.virtual.client.hook.proxies.pm.PackageManagerStub
import org.json.JSONObject
import java.lang.reflect.Proxy

/** Disposable emulator process; no guest Application, account or game resource is opened. */
internal object PackageInterfaceProbe {
    fun inspect(context: Context): JSONObject {
        val client = com.lody.virtual.client.VClient.get()
        val setup = client.javaClass.getDeclaredMethod("setupUncaughtHandler", String::class.java)
            .apply { isAccessible = true }
        val caller = Thread.currentThread()
        val main = android.os.Looper.getMainLooper().thread
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        val parents = generateSequence(caller.threadGroup) { it.parent }.map { it to it.parent }.toList()
        for (pkg in listOf("com.ProjectMoon.LimbusCompany", "com.google.android.gms", "com.android.vending")) {
            setup.invoke(client, pkg)
            val threads = Thread.getAllStackTraces()
            check(threads.containsKey(caller) && threads.containsKey(main)) { "Guest setup hid platform threads" }
            check(parents.all { (group, parent) -> group.parent === parent })
            check(Thread.getDefaultUncaughtExceptionHandler() === handler)
        }
        val before = mirror.android.app.ActivityThread.sPackageManager.get()
        check(Proxy.isProxyClass(before.javaClass)) { "Expected original container dispatch" }
        PackageManagerStub.prepareGameInterface("com.ProjectMoon.LimbusCompany")
        val after = mirror.android.app.ActivityThread.sPackageManager.get()
        check(!Proxy.isProxyClass(after.javaClass)) { "Concrete interface was not installed" }
        val stub = requireNotNull(InvocationStubManager.getInstance().findInjector(PackageManagerStub::class.java))
        check(!stub.isEnvBad)
        stub.inject()
        check(mirror.android.app.ActivityThread.sPackageManager.get() === after)
        val binder = requireNotNull(mirror.android.os.ServiceManager.sCache.get()["package"])
        val binderApi = binder.queryLocalInterface("android.content.pm.IPackageManager")
        check(binderApi != null && !Proxy.isProxyClass(binderApi.javaClass))
        check(binderApi.asBinder() === binder)
        val contract = Class.forName("android.content.pm.IPackageManager")
        val query = contract.methods.single { it.name == "getPackageInfo" && it.parameterTypes.size == 3 }
        val flags: Any = if (query.parameterTypes[1] == java.lang.Long.TYPE) 0L else 0
        for (api in listOf<IInterface>(after, binderApi)) {
            val info = query.invoke(api, context.packageName, flags, Process.myUid() / 100000) as PackageInfo
            check(info.packageName == context.packageName && info.applicationInfo!!.uid == Process.myUid())
        }
        return JSONObject().put("passed", true).put("runtime_methods", contract.methods.size)
            .put("sdk", android.os.Build.VERSION.SDK_INT).put("game_started", false)
            .put("activity_thread_concrete", true).put("binder_concrete", true)
            .put("reinjection_stable", true).put("metadata_query_preserved", true)
            .put("thread_hierarchy_preserved", true).put("crash_handler_preserved", true)
    }
}
