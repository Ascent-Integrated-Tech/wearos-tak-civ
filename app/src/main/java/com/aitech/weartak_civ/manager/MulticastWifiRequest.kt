package com.aitech.weartak_civ.manager

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.util.Log
import java.io.Closeable

internal class MulticastWifiRequest(
    private val connectivityManager: ConnectivityManager,
    private val handler: Handler
) : Closeable {
    private var callback: ConnectivityManager.NetworkCallback? = null

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
        }
        // An observer or Wi-Fi lock alone does not count as demand for Wear OS Wi-Fi.
        connectivityManager.requestNetwork(request, requestedCallback, handler)
        callback = requestedCallback
        Log.i(TAG, "Requested local Wi-Fi for multicast (internet not required)")
    }

    override fun close() {
        val registered = callback ?: return
        connectivityManager.unregisterNetworkCallback(registered)
        callback = null
        Log.i(TAG, "Released multicast Wi-Fi request")
    }

    private companion object {
        const val TAG = "WearTAK-MulticastWifi"
    }
}
