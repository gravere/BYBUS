package com.example.limbuszhcn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Read-only view for this UID. No network binding, probes, addresses or network names. */
internal fun gameNetworkSnapshot(context: Context): String = runCatching {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return "networkSnapshot=unavailable"
    val active = manager.activeNetwork
    val caps = active?.let(manager::getNetworkCapabilities)
    val transports = listOf(
        NetworkCapabilities.TRANSPORT_WIFI to "wifi",
        NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
        NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
        NetworkCapabilities.TRANSPORT_VPN to "vpn"
    ).filter { caps?.hasTransport(it.first) == true }.joinToString(",") { it.second }.ifEmpty { "none" }
    val vpnVisible = manager.allNetworks.any {
        manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
    "networkSnapshot=available active=${active != null} transports=$transports " +
        "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
        "vpnVisibleForHost=$vpnVisible processBound=${manager.boundNetworkForProcess != null}"
}.getOrElse { "networkSnapshot=unavailable error=${it.javaClass.simpleName}" }
