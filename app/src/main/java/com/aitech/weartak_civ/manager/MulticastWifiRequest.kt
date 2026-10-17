package com.aitech.weartak_civ.manager

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.util.Log
import java.io.Closeable

internal class MulticastWifiRequest(
    private val connectivityManager: ConnectivityManager,
    wifiManager: WifiManager,
    private val handler: Handler
) : Closeable {
    private var callback: ConnectivityManager.NetworkCallback? = null
    // Android 14+ maps this to low latency mode, which only takes effect screen-on/in foreground.
    @Suppress("DEPRECATION")
    private val wifiLock = wifiManager.createWifiLock(
        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:wifi"
    ).apply { setReferenceCounted(false) }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            close()
            return
        }
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val requestedCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Multicast Wi-Fi request available: $network")
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Multicast Wi-Fi request lost: $network; waiting for Wi-Fi")
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                Log.i(TAG, "Multicast Wi-Fi capabilities: $network " +
                    "validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                    "notSuspended=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)}")
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                Log.i(TAG, "Multicast Wi-Fi link: $network interface=${properties.interfaceName}")
            }
        }
        // An observer or Wi-Fi lock alone does not count as demand for Wear OS Wi-Fi.
        var registered = false
        try {
            wifiLock.acquire()
            connectivityManager.requestNetwork(request, requestedCallback, handler)
            callback = requestedCallback
            registered = true
            Log.i(TAG, "Requested local Wi-Fi for multicast with Wi-Fi power lock (internet not required)")
        } finally {
            if (!registered && wifiLock.isHeld) wifiLock.release()
        }
    }

    override fun close() {
        val registered = callback ?: return
        callback = null
        try {
            connectivityManager.unregisterNetworkCallback(registered)
        } finally {
            if (wifiLock.isHeld) wifiLock.release()
            Log.i(TAG, "Released multicast Wi-Fi request and power lock")
        }
    }

    private companion object {
        const val TAG = "WearTAK-MulticastWifi"
    }
}
