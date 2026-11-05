package com.example.limbuszhcn

import android.app.Activity
import android.app.IBinderSession
import android.app.IServiceConnection
import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
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
            val result = runCatching { inspect() }.getOrElse {
                android.util.Log.e("ServiceCallbackProbe", "Platform callback inspection failed", it)
                JSONObject().put("passed", false).put("error", it.stackTraceToString().take(8000))
            }
            runOnUiThread { output.text = "SERVICE_CALLBACK_RESULT\n$result" }
        }.start()
    }

    private fun inspect(): JSONObject {
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
        val delegate = ServiceConnectionDelegate.getOrCreateDelegate(receiver, targetName)
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
    }
}
