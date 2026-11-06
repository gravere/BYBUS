package com.example.limbuszhcn

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Process
import com.lody.virtual.helper.compat.MicrogAuthenticatorClient
import com.lody.virtual.helper.compat.MicrogAuthenticatorCompat
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Real framework authenticator with synthetic responses; no AccountManager or Google account. */
internal object AuthenticatorPermissionProbe {
    fun inspect(context: Context): JSONObject {
        val responseType = Class.forName("android.accounts.IAccountAuthenticatorResponse")
        val authenticatorType = Class.forName("android.accounts.IAccountAuthenticator")
        val stub = Class.forName("android.accounts.IAccountAuthenticator\$Stub")
        val prepare = MicrogAuthenticatorCompat::class.java.getDeclaredMethod("prepareBinder",
            Context::class.java, IBinder::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val checks = JSONObject()
        fun inspectCase(name: String, uid: Int, pid: Int, configure: Boolean, allowed: Boolean) {
            val target = object : AbstractAccountAuthenticator(context) {
                override fun editProperties(r: AccountAuthenticatorResponse, type: String): Bundle = error("Unused")
                override fun addAccount(r: AccountAuthenticatorResponse, type: String, token: String?,
                                        features: Array<out String>?, options: Bundle?): Bundle = error("Unused")
                override fun confirmCredentials(r: AccountAuthenticatorResponse, account: Account, options: Bundle?): Bundle = error("Unused")
                override fun getAuthToken(r: AccountAuthenticatorResponse, account: Account, token: String,
                                          options: Bundle?): Bundle = error("Unused")
                override fun getAuthTokenLabel(token: String): String = error("Unused")
                override fun updateCredentials(r: AccountAuthenticatorResponse, account: Account, token: String?,
                                               options: Bundle?): Bundle = error("Unused")
                override fun hasFeatures(r: AccountAuthenticatorResponse, account: Account,
                                         features: Array<out String>): Bundle = Bundle().apply { putBoolean("probe", true) }
            }.iBinder
            if (configure) check(prepare.invoke(null, context, target, uid, pid) == true)
            var callbacks = 0
            val responseBinder = Binder()
            val response = Proxy.newProxyInstance(responseType.classLoader, arrayOf(responseType)) { _, method, args ->
                when (method.name) {
                    "asBinder" -> responseBinder
                    "onResult" -> { check((args!![0] as Bundle).getBoolean("probe")); callbacks++; null }
                    else -> error("Unexpected response ${method.name}")
                }
            }
            responseBinder.attachInterface(response as IInterface, responseType.name)
            var sync = false
            val transport = object : Binder() {
                init { attachInterface(null, authenticatorType.name) }
                override fun queryLocalInterface(descriptor: String): IInterface? = null
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    sync = flags and IBinder.FLAG_ONEWAY == 0
                    return target.transact(code, data, reply, flags)
                }
            }
            val remote = stub.getMethod("asInterface", IBinder::class.java)
                .invoke(null, MicrogAuthenticatorClient.wrap(transport))
            val method = authenticatorType.getMethod("hasFeatures", responseType, Account::class.java, Array<String>::class.java)
            val failure = runCatching { method.invoke(remote, response, Account("probe", "bybus.probe"), emptyArray<String>()) }
                .exceptionOrNull()
            if (allowed) {
                check(failure == null && callbacks == 1) { "$name did not preserve the authenticator result: $failure" }
            } else {
                var cause = failure
                while (cause is InvocationTargetException) cause = cause.targetException
                check(cause is SecurityException && callbacks == 0) { "$name did not preserve permission rejection: $cause" }
            }
            check(sync) { "Authenticator caller PID would be lost in oneway transport" }
            checks.put(name, true)
        }
        val uid = Process.myUid()
        val pid = Process.myPid()
        inspectCase("platform_denies_unprivileged_caller", uid, pid, configure = false, allowed = false)
        inspectCase("exact_server_preserves_result", uid, pid, configure = true, allowed = true)
        inspectCase("wrong_server_rejected", uid, pid + 1, configure = true, allowed = false)
        inspectCase("wrong_uid_rejected", uid + 1, pid, configure = true, allowed = false)
        return checks
    }
}
