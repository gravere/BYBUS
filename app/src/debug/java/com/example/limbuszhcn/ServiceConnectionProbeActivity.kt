package com.example.limbuszhcn

import android.app.Activity
import android.app.IBinderSession
import android.app.IServiceConnection
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Parcelable
import android.os.RemoteException
import android.widget.TextView
import com.lody.virtual.client.hook.secondary.ServiceConnectionDelegate
import com.lody.virtual.helper.compat.ServiceConnectionCompat
import java.lang.reflect.Proxy
import org.json.JSONObject

/** Exercises the actual platform AIDL dispatcher; no game, accounts, network or bound service. */
class ServiceConnectionProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val output = TextView(this).apply { text = "SERVICE_CALLBACK_PENDING" }
        setContentView(output)
        Thread {
            val result = runCatching { ServiceConnectionProbe.inspect() }.getOrElse {
                android.util.Log.e("ServiceCallbackProbe", "Platform callback inspection failed", it)
                JSONObject().put("passed", false).put("error", it.stackTraceToString().take(8000))
            }
            runOnUiThread { output.text = "SERVICE_CALLBACK_RESULT\n$result" }
        }.start()
    }

}

/** Read the result independently of Activity navigation and focus. Debug builds only. */
class ServiceConnectionProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val result = runCatching { ServiceConnectionProbe.inspect() }.getOrElse {
                    android.util.Log.e("ServiceCallbackProbe", "Platform callback inspection failed", it)
                    JSONObject().put("passed", false).put("error", it.stackTraceToString().take(8000))
                }
                java.io.File(context.filesDir, "service-callback-result.json").writeText(result.toString())
            } finally {
                pending.finish()
            }
        }.start()
    }
}

internal object ServiceConnectionProbe {
    fun inspect(): JSONObject {
        val count = IServiceConnection::class.java.methods.filter { it.name == "connected" }.maxOf { it.parameterTypes.size }
        check(count in 2..4)
        val callbacks = mutableListOf<Array<out Any?>>()
        val receiverBinder = Binder()
        val receiver = Proxy.newProxyInstance(IServiceConnection::class.java.classLoader,
            arrayOf(IServiceConnection::class.java)) { _, method, arguments ->
            when (method.name) {
                "asBinder" -> receiverBinder
                "connected" -> { callbacks.add(arguments!!.copyOf()); null }
                else -> error("Unexpected connection method ${method.name}")
            }
        } as IServiceConnection
        val originalName = ComponentName("probe.system", "probe.system.Service")
        val targetName = ComponentName("probe.guest", "probe.guest.Service")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val start = java.util.concurrent.CountDownLatch(1)
        val candidates = (0 until 8).map { pool.submit<ServiceConnectionDelegate> {
            check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
            ServiceConnectionDelegate.getOrCreateDelegate(receiver, targetName)
        } }
        start.countDown()
        val delegate = try {
            val instances = candidates.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            check(instances.all { it === instances.first() }) { "Concurrent binds created different delegates" }
            check(ServiceConnectionDelegate.getDelegate(receiver) === instances.first())
            instances.first()
        } finally { pool.shutdownNow() }
        inspectDeathCleanup(targetName)
        inspectIntentLogging()
        inspectStartupFailure()
        inspectServiceTeardown()
        val storageChecked = inspectTranslatedContextStorage()
        // queryLocalInterface must return null so the OS-generated Proxy writes the real wire format.
        val transport = object : Binder() {
            override fun queryLocalInterface(descriptor: String): IInterface? = null
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                delegate.asBinder().transact(code, data, reply, flags)
        }
        val remote = IServiceConnection.Stub.asInterface(transport)
        val service = Binder()
        val sessionBinder = Binder()
        val session = Proxy.newProxyInstance(IBinderSession::class.java.classLoader,
            arrayOf(IBinderSession::class.java)) { _, method, _ ->
            check(method.name == "asBinder") { "Session must be forwarded without being called" }
            sessionBinder
        } as IBinderSession

        fun expect(binder: IBinder?, expectedSession: IBinder?, dead: Boolean, deliver: () -> Unit) {
            val before = callbacks.size
            deliver()
            check(callbacks.size == before + 1) { "Callback was dropped or duplicated" }
            val values = callbacks.last()
            check(values.size == count && values[0] == targetName && values[1] === binder)
            if (count == 4) check((values[2] as IBinderSession?)?.asBinder() === expectedSession)
            if (count >= 3) check(values.last() == dead)
        }
        expect(service, if (count == 4) sessionBinder else null, false) {
            ServiceConnectionCompat.connected(remote, originalName, service, session, false)
        }
        expect(service, if (count == 4) sessionBinder else null, true) {
            ServiceConnectionCompat.connected(remote, originalName, service, session, true)
        }
        expect(null, null, false) { ServiceConnectionCompat.connected(remote, originalName, null, null, false) }
        expect(service, null, false) { delegate.connected(originalName, service) }
        expect(service, null, true) { delegate.connected(originalName, service, true) }
        return JSONObject().put("passed", true).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("framework_parameters", count).put("callbacks", callbacks.size)
            .put("platform_proxy_and_stub", true).put("component_preserved", true)
            .put("service_binder_preserved", true).put("session_preserved", count == 4)
            .put("null_binding", true).put("dead_flag", true).put("legacy_entry_points", true)
            .put("concurrent_delegate_reuse", true).put("dead_delegate_removed", true)
            .put("activity_log_payloads_untouched", true)
            .put("startup_failure_releases_waiters", true)
            .put("dead_server_unbind_does_not_reconnect", true)
            .put("dead_server_destroy_does_not_reconnect", true)
            .put("service_errors_preserved", true)
            .put("translated_context_storage_checked", storageChecked)
    }

    private fun inspectTranslatedContextStorage(): Boolean {
        if (!com.lody.virtual.helper.compat.ContainerAbiCompat.requiresHostContextPaths(android.os.Build.SUPPORTED_ABIS)) return false
        val host = com.lody.virtual.client.core.VirtualCore.get().context
        val root = java.io.File(host.cacheDir, "context-storage-probe").apply { mkdirs() }
        val internal = java.io.File(root, "internal").apply { mkdirs() }
        val external = java.io.File(root, "external").apply { mkdirs() }
        val game = "com.ProjectMoon.LimbusCompany"
        val redirect = com.lody.virtual.client.NativeEngine::class.java.getDeclaredMethod(
            "nativeIORedirect", String::class.java, String::class.java).apply { isAccessible = true }
        // Register path mappings in this debug-only server process, without installing IO hooks.
        // Java calls must reach the mapped directory even when no ARM libc hook can intercept them.
        redirect.invoke(null, "/data/user/0/$game/", internal.path + "/")
        redirect.invoke(null, "/storage/emulated/0/Android/data/$game/", external.path + "/")
        val base = host.createPackageContext(host.packageName, 0)
        val isolated = com.lody.virtual.client.fixer.LimbusStorageContext.wrap(base, game, android.os.Build.SUPPORTED_ABIS)
        check(com.lody.virtual.client.fixer.LimbusStorageContext.wrap(base, "other.package", android.os.Build.SUPPORTED_ABIS) === base)
        val nativeArm = com.lody.virtual.client.fixer.LimbusStorageContext.wrap(base, game, arrayOf("arm64-v8a"))
        check(nativeArm.getExternalFilesDir(null)!!.path == "/storage/emulated/0/Android/data/$game/files")
        check(nativeArm.externalCacheDir!!.path == "/storage/emulated/0/Android/data/$game/cache")
        val dirs = listOf(isolated.filesDir, isolated.cacheDir, isolated.codeCacheDir,
            isolated.noBackupFilesDir, requireNotNull(isolated.getExternalFilesDir(null)),
            requireNotNull(isolated.externalCacheDir))
        for (dir in dirs) {
            check(dir.canonicalPath.startsWith(root.canonicalPath + "/")) { "Context escaped mapped storage: ${dir.path}" }
            dir.mkdirs()
            val file = java.io.File(dir, "resource-probe.txt")
            file.writeText("unity-resource-fixture")
            check(file.readText() == "unity-resource-fixture")
            check(dir.usableSpace > 0)
            check(file.delete())
        }
        val prefs = isolated.getSharedPreferences("storage-probe", Context.MODE_PRIVATE)
        check(prefs.edit().putInt("generation", 7).commit())
        check(java.io.File(internal, "shared_prefs/storage-probe.xml").isFile)
        check(prefs.getInt("generation", 0) == 7)
        check(isolated.getExternalFilesDir("Pictures")!!.isDirectory)
        check(isolated.getExternalFilesDirs(null).single() == isolated.getExternalFilesDir(null))
        check(isolated.externalCacheDirs.single() == isolated.externalCacheDir)
        check(runCatching { isolated.getExternalFilesDir("../../../escape") }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { isolated.getExternalFilesDir("/absolute") }.exceptionOrNull() is IllegalArgumentException)
        return true
    }

    private fun inspectServiceTeardown() {
        val type = com.lody.virtual.client.ipc.VActivityManager::class.java
        // Isolated client: never replace the live process's manager or kill a real service.
        val client = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val field = type.getDeclaredField("mService").apply { isAccessible = true }
        val component = ComponentName("probe.guest", "probe.guest.Service")
        var calls = 0
        var alive = false
        var failure: Exception? = null
        val binder = object : Binder() { override fun isBinderAlive() = alive }
        val expected = com.lody.virtual.remote.ServiceResult().apply { startId = 4; clientCount = 2 }
        val service = Proxy.newProxyInstance(type.classLoader,
            arrayOf(com.lody.virtual.server.interfaces.IActivityManager::class.java)) { _, method, _ ->
            when (method.name) {
                "asBinder" -> binder
                "onServiceUnBind", "onServiceDestroyed" -> {
                    calls++
                    failure?.let { throw it }
                    if (method.name == "onServiceUnBind") expected else null
                }
                else -> error("Unexpected teardown RPC ${method.name}")
            }
        }
        check(client.onServiceUnBind(0, component).died)
        client.onServiceDestroyed(0, component)
        field.set(client, service)
        check(client.onServiceUnBind(0, component).died)
        client.onServiceDestroyed(0, component)
        check(calls == 0)
        alive = true
        check(client.onServiceUnBind(0, component) === expected)
        failure = android.os.DeadObjectException()
        check(client.onServiceUnBind(0, component).let { it.died && !it.restart && it.startId == 0 })
        client.onServiceDestroyed(0, component)
        failure = SecurityException("fixture permission denied")
        check(runCatching { client.onServiceUnBind(0, component) }.exceptionOrNull() === failure)
        failure = RemoteException("fixture transport failure")
        val observed = runCatching { client.onServiceUnBind(0, component) }.exceptionOrNull()
        check(observed is RuntimeException && observed.cause === failure)
    }

    private fun inspectStartupFailure() {
        val type = com.lody.virtual.client.core.VirtualCore::class.java
        val constructor = type.getDeclaredConstructor().apply { isAccessible = true }
        val isolated = constructor.newInstance()
        val expected = IllegalStateException("Synthetic startup failure")
        val completed = java.util.concurrent.CountDownLatch(1)
        var observed: Throwable? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                isolated.startup(com.lody.virtual.client.core.VirtualCore.get().context,
                    object : com.lody.virtual.client.core.SettingConfig() {
                        override fun getHostPackageName(): String = throw expected
                        override fun getPluginEnginePackageName(): String? = null
                    })
            } catch (error: Throwable) { observed = error }
            finally { completed.countDown() }
        }
        check(completed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        check(observed === expected && !isolated.isStartup)
        check(isolated.initLock.block(100)) { "Failed startup left its gate closed" }
        val result = runCatching { isolated.waitStartup() }
        check(result.exceptionOrNull()?.cause === expected)
    }

    private fun inspectDeathCleanup(target: ComponentName) {
        val deaths = mutableListOf<IBinder.DeathRecipient>()
        var alreadyDead = false
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "linkToDeath" -> {
                    if (alreadyDead) throw RemoteException("Synthetic dead Binder")
                    deaths.add(args!![0] as IBinder.DeathRecipient); null
                }
                "unlinkToDeath" -> true
                else -> error("Unexpected Binder method ${method.name}")
            }
        } as IBinder
        val receiver = Proxy.newProxyInstance(IServiceConnection::class.java.classLoader, arrayOf(IServiceConnection::class.java)) { _, method, _ ->
            check(method.name == "asBinder"); binder
        } as IServiceConnection
        val first = ServiceConnectionDelegate.getOrCreateDelegate(receiver, target)
        deaths.first().binderDied()
        check(ServiceConnectionDelegate.getDelegate(receiver) == null)
        val replacement = ServiceConnectionDelegate.getOrCreateDelegate(receiver, target)
        check(replacement !== first)
        deaths.first().binderDied() // An old death callback cannot remove a replacement.
        check(ServiceConnectionDelegate.getDelegate(receiver) === replacement)
        deaths.last().binderDied()
        alreadyDead = true
        ServiceConnectionDelegate.getOrCreateDelegate(receiver, target)
        check(ServiceConnectionDelegate.getDelegate(receiver) == null)
    }

    private fun inspectIntentLogging() {
        val parcel = Parcel.obtain()
        val input = Intent("bybus.probe").putExtra("guest_payload", ExplodingLogExtra())
        val lazy = try {
            input.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        for (type in listOf("com.lody.virtual.server.am.VActivityManagerService", "com.lody.virtual.server.am.ActivityStack")) {
            val describe = Class.forName(type).getDeclaredMethod("describeIntentForLimbus", Intent::class.java)
            describe.isAccessible = true
            check((describe.invoke(null, lazy) as String).contains("bybus.probe"))
        }
        @Suppress("DEPRECATION")
        check(runCatching { lazy.getParcelableExtra<Parcelable>("guest_payload") }.isFailure) {
            "Fixture must throw when the guest payload is actually decoded"
        }
    }
}

class ExplodingLogExtra : Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) { dest.writeInt(1) }
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<ExplodingLogExtra> {
            override fun createFromParcel(source: Parcel): ExplodingLogExtra = error("Guest-only payload decoded")
            override fun newArray(size: Int): Array<ExplodingLogExtra?> = arrayOfNulls(size)
        }
    }
}
