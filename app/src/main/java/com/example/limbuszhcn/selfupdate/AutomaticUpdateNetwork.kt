package com.example.limbuszhcn.selfupdate

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Tracks the actual default network: VPNs and unavailable networks cannot be guessed as Wi-Fi. */
internal class AutomaticUpdateNetwork(context: Context, private val changed: (Boolean) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        override fun onLost(network: Network) = refresh()
    }
    private var registered = false

    init {
        registered = runCatching { manager.registerDefaultNetworkCallback(callback); true }.getOrDefault(false)
        refresh()
    }

    private fun refresh() {
        val allowed = runCatching {
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }.getOrDefault(false)
        changed(allowed)
    }

    override fun close() {
        if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        registered = false
    }
}
