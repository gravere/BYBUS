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
    private val session = AiTaskService.currentSession()
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
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }.isSuccess

    fun waitingReason(): String? {
        if (session?.usesJob != true && power.isDeviceIdleMode && !AiBackgroundAccess.allowed(context)) return "系统休眠限制联网，等待恢复"
        val network = (if (session?.usesJob == true) session.jobNetwork else connectivity.activeNetwork)
            ?: return if (session?.usesJob == true) "等待系统恢复任务网络" else "网络已断开，等待连接"
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
        val request = org.json.JSONObject(body)
        val connection = AiTranslationJob.digest("$url|${request.optString("model")}").take(12)
        val entries = runCatching { org.json.JSONArray(request.getJSONArray("messages").getJSONObject(1).getString("content")) }.getOrNull()
        val characters = entries?.let { values -> (0 until values.length()).sumOf { values.getJSONObject(it).optString("text").length } } ?: 0
        log("request-start connection=$connection items=${entries?.length() ?: 0} sourceChars=$characters streaming=${request.optBoolean("stream", false)} requestBytes=${body.toByteArray(Charsets.UTF_8).size} outputLimit=${request.optInt("max_completion_tokens", 0)}")
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
        }, clock = SystemClock::elapsedRealtime, openConnection = { address ->
            control.check()
            if (session?.usesJob == true) {
                // Never bind the whole app or fall back outside the system-granted task network.
                val network = session.jobNetwork ?: throw AiTranslationException("后台任务网络已中断，进度已保留")
                network.openConnection(address) as java.net.HttpURLConnection
            } else address.openConnection() as java.net.HttpURLConnection
        }) { awaitReady(control, status) }.also {
            log("request-complete stage=${last.stage} bytes=${last.bytes} events=${last.events} heartbeats=${last.heartbeats} elapsedMs=${SystemClock.elapsedRealtime() - started}")
        } }
        catch (error: Exception) {
            // Types and system state only: provider URLs, keys and response bodies must stay private.
            val service = error as? AiServiceException
            log("request-ended type=${error.javaClass.simpleName} status=${service?.httpStatus ?: 0} providerStatus=${service?.providerStatus ?: 0} reason=${service?.reason ?: "None"} stage=${last.stage} bytes=${last.bytes} events=${last.events} heartbeats=${last.heartbeats} elapsedMs=${SystemClock.elapsedRealtime() - started}")
            throw error
        }
    }

    private fun log(event: String) {
        val active = if (session?.usesJob == true) session.jobNetwork else connectivity.activeNetwork
        val caps = active?.let(connectivity::getNetworkCapabilities)
        val restricted = Build.VERSION.SDK_INT >= 28 && context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted
        val process = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
        PersistentDiagnosticLog.info("AiNetwork", "$event owner=${if (session?.usesJob == true) "uidt" else if (session != null) "fgs" else "none"} importance=${process.importance} idle=${power.isDeviceIdleMode} interactive=${power.isInteractive} restricted=$restricted exempt=${AiBackgroundAccess.allowed(context)} connected=${active != null} blocked=${active?.let(blocked::get)} vpn=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)} validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
    }

    override fun close() {
        if (networkRegistered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        if (powerRegistered) runCatching { context.unregisterReceiver(receiver) }
    }
}
