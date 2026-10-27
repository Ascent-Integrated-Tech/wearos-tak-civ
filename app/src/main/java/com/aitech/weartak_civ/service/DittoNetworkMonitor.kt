package com.aitech.weartak_civ.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import live.ditto.transports.DittoTransportConfig

internal object DittoTransportPolicy {
    fun apply(config: DittoTransportConfig, features: PackageManager, wifi: Boolean, internet: Boolean,
        websocketUrls: Set<String>) {
        config.peerToPeer.wifiAware.enabled = features.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        // SDK 4 exposes BLE, LAN and Aware, but no Wi-Fi Direct transport to enable.
        config.peerToPeer.lan.enabled = wifi
        config.connect.websocketUrls = if (internet) websocketUrls.toMutableSet() else mutableSetOf()
    }

    fun enabled(config: DittoTransportConfig): Set<String> = buildSet {
        if (config.peerToPeer.bluetoothLe.enabled) add("BLE")
        if (config.peerToPeer.lan.enabled) add("LAN")
        if (config.peerToPeer.wifiAware.enabled) add("Wi-Fi Aware")
        if (config.connect.websocketUrls.isNotEmpty()) add("WebSocket")
    }
}

/** Includes local-only Wi-Fi, not just Android's internet/default network. */
internal class DittoNetworkMonitor(
    context: Context,
    private val changed: (wifi: Boolean, internet: Boolean) -> Unit,
) : ConnectivityManager.NetworkCallback(), AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networks = mutableMapOf<Network, NetworkCapabilities>()
    private var registered = false
    private var closed = false

    @Synchronized
    fun start() {
        connectivity.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), this)
        registered = true
        connectivity.allNetworks.forEach { network ->
            connectivity.getNetworkCapabilities(network)?.let { networks[network] = it }
        }
        publish()
    }

    @Synchronized
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        if (closed) return
        networks[network] = caps
        publish()
    }

    @Synchronized
    override fun onLost(network: Network) {
        if (closed) return
        networks.remove(network)
        publish()
    }

    private fun publish() = changed(
        networks.values.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
        networks.values.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) },
    )

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (registered) connectivity.unregisterNetworkCallback(this)
        networks.clear()
    }
}
