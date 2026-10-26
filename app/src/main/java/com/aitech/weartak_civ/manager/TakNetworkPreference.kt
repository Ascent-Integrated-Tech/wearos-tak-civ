package com.aitech.weartak_civ.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun <T> preferredTakNetwork(
    validatedWifi: Collection<T>, defaultNetwork: T?, previous: T?
): T? = when {
    previous in validatedWifi -> previous
    defaultNetwork in validatedWifi -> defaultNetwork
    validatedWifi.isNotEmpty() -> validatedWifi.first()
    else -> defaultNetwork
}

/** Observes existing networks; never requests a radio or binds the entire process. */
internal class TakNetworkPreference(context: Context) : ConnectivityManager.NetworkCallback() {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = linkedSetOf<Network>()
    private val available = linkedSetOf<Network>()
    private val selected = MutableStateFlow<Network?>(manager.activeNetwork)
    val network = selected.asStateFlow()
    private var active = false
    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (active) {
                update(network, networkCapabilities)
                select()
            }
        }
        override fun onLost(network: Network) {
            // Losing default status does not mean the network itself disappeared.
            if (active) select()
        }
    }

    fun start() {
        active = true
        manager.allNetworks.forEach { net ->
            manager.getNetworkCapabilities(net)?.let { update(net, it) }
        }
        manager.registerNetworkCallback(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            this, Handler(Looper.getMainLooper()))
        manager.registerDefaultNetworkCallback(defaultCallback, Handler(Looper.getMainLooper()))
        select()
    }

    private fun update(net: Network, caps: NetworkCapabilities) {
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) available.add(net)
        else available.remove(net)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) wifi.add(net)
        else wifi.remove(net)
    }

    private fun select() {
        val default = manager.activeNetwork
        if (default != null && manager.getNetworkCapabilities(default)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            // Transport preference must not bypass the user's default VPN.
            selected.value = default
            return
        }
        val fallback = default?.takeIf { it in available }
            ?: available.firstOrNull()
        selected.value = preferredTakNetwork(wifi, fallback, selected.value)
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        if (!active) return
        update(network, networkCapabilities)
        select()
    }

    override fun onLost(network: Network) {
        if (!active) return
        wifi.remove(network)
        available.remove(network)
        select()
    }

    fun stop() {
        if (!active) return
        active = false
        manager.unregisterNetworkCallback(this)
        manager.unregisterNetworkCallback(defaultCallback)
        wifi.clear()
        available.clear()
    }
}
