package com.example.limbuszhcn.translation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.example.limbuszhcn.PersistentDiagnosticLog

/** Wait before sending, never retry a request whose billing/outcome is uncertain. */
internal class AiNetworkGuard(private val context: Context) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val blocked = java.util.concurrent.ConcurrentHashMap<Network, Boolean>()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { blocked.clear(); log("available") }
        override fun onLost(network: Network) { blocked.remove(network); log("lost") }
        override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) {
            blocked[network] = isBlocked; log("blocked=$isBlocked")
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) { log("power-changed") }
    }
    private val networkRegistered = runCatching { connectivity.registerDefaultNetworkCallback(callback) }.isSuccess
    private val powerRegistered = runCatching {
        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED).apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }.isSuccess

    fun waitingReason(): String? {
        if (power.isDeviceIdleMode && !AiBackgroundAccess.allowed(context)) return "系统休眠限制联网，等待恢复"
        val network = connectivity.activeNetwork ?: return "网络已断开，等待连接"
        if (blocked[network] == true) return "系统暂停了联网，等待恢复"
        val caps = connectivity.getNetworkCapabilities(network)
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) return "网络尚未就绪，等待连接"
        return null
    }

    fun awaitReady(control: AiRequestControl, status: (String?) -> Unit) {
        var previous: String? = null
        while (true) {
            control.check()
            val reason = waitingReason()
            if (reason != previous) { status(reason); previous = reason }
            if (reason == null) return
            Thread.sleep(250)
        }
    }

    fun post(url: String, key: String, body: String, control: AiRequestControl, status: (String?) -> Unit): String {
        val started = SystemClock.elapsedRealtime()
        var last = AiResponseProgress(AiResponseStage.Sending)
        var previousStage: AiResponseStage? = null
        try { return AiTranslationClient.post(url, key, body, control, progress = { current ->
            last = current
            if (previousStage != current.stage) {
                previousStage = current.stage
                status(when (current.stage) {
                    AiResponseStage.Sending -> "正在发送请求"
                    AiResponseStage.Waiting -> "等待翻译结果"
                    AiResponseStage.Receiving -> "正在接收译文"
                    AiResponseStage.Thinking -> "模型正在思考"
                })
            }
        }) { awaitReady(control, status) }.also {
            log("request-complete stage=${last.stage} bytes=${last.bytes} events=${last.events} elapsedMs=${SystemClock.elapsedRealtime() - started}")
        } }
        catch (error: Exception) {
            // Types and system state only: provider URLs, keys and response bodies must stay private.
            log("request-ended type=${error.javaClass.simpleName} status=${(error as? AiHttpException)?.status ?: 0} stage=${last.stage} bytes=${last.bytes} events=${last.events} elapsedMs=${SystemClock.elapsedRealtime() - started}")
            throw error
        }
    }

    private fun log(event: String) {
        val active = connectivity.activeNetwork
        val caps = active?.let(connectivity::getNetworkCapabilities)
        val restricted = Build.VERSION.SDK_INT >= 28 && context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted
        PersistentDiagnosticLog.info("AiNetwork", "$event idle=${power.isDeviceIdleMode} interactive=${power.isInteractive} restricted=$restricted exempt=${AiBackgroundAccess.allowed(context)} connected=${active != null} blocked=${active?.let(blocked::get)} vpn=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)} validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
    }

    override fun close() {
        if (networkRegistered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        if (powerRegistered) runCatching { context.unregisterReceiver(receiver) }
    }
}
