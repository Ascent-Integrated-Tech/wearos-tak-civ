package com.aitech.weartak_civ.manager

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class MulticastWifiNetworkMonitor : ConnectivityManager.NetworkCallback() {
    private val availableNetworks = linkedSetOf<Network>()
    private val selectedNetwork = MutableStateFlow<Network?>(null)
    val wifiNetwork = selectedNetwork.asStateFlow()

    override fun onAvailable(network: Network) {
        availableNetworks.add(network)
        publish()
    }

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            availableNetworks.add(network)
        } else {
            availableNetworks.remove(network)
        }
        publish()
    }

    override fun onLost(network: Network) {
        availableNetworks.remove(network)
        publish()
    }

    fun clear() {
        availableNetworks.clear()
        publish()
    }

    private fun publish() {
        val selected = selectedNetwork.value?.takeIf { it in availableNetworks }
            ?: availableNetworks.firstOrNull()
        if (selectedNetwork.value != selected) {
            selectedNetwork.value = selected
            Log.d("WearTAK-NetworkManager", "Sending wifiNetwork=$selected")
        }
    }
}
