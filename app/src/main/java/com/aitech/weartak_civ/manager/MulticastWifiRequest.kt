package com.aitech.weartak_civ.manager

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.util.Log
import com.aitech.weartak_civ.service.DiagnosticResource
import com.aitech.weartak_civ.service.DiagnosticResources
import com.aitech.weartak_civ.service.ResourceAction
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class MulticastWifiRequest(
    private val connectivityManager: ConnectivityManager,
    private val handler: Handler,
    private val idleTimeoutMs: Long = 600_000L
) : Closeable {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var idleReleased = false
    private val idleTimeout = Runnable {
        synchronized(this) {
            if (callback != null) {
                try {
                    releaseRequest(idle = true)
                } catch (error: SecurityException) {
                    Log.e(TAG, "Permission denied releasing idle multicast Wi-Fi request", error)
                } catch (error: IllegalArgumentException) {
                    Log.e(TAG, "Idle multicast Wi-Fi request was no longer registered", error)
                }
            }
        }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean, resetIdle: Boolean = false) {
        if (!enabled) {
            close()
            return
        }
        if (resetIdle) idleReleased = false
        if (callback != null) {
            if (resetIdle) armIdleTimeout()
            return
        }
        if (idleReleased) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val requestedCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onUnavailable() {
                DiagnosticResources.note(DiagnosticResource.MULTICAST_NETWORK, this, ResourceAction.FAILED)
                DiagnosticResources.end(DiagnosticResource.MULTICAST_NETWORK, this)
            }

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
        DiagnosticResources.acquire(DiagnosticResource.MULTICAST_NETWORK, requestedCallback) {
            connectivityManager.requestNetwork(request, requestedCallback, handler)
        }
        callback = requestedCallback
        armIdleTimeout()
        publishState()
        Log.i(TAG, "Requested local Wi-Fi for multicast (no power lock; internet not required)")
    }

    @Synchronized
    fun onPeerPacketReceived() {
        if (callback != null) armIdleTimeout()
    }

    private fun armIdleTimeout() {
        handler.removeCallbacks(idleTimeout)
        handler.postDelayed(idleTimeout, idleTimeoutMs)
    }

    @Synchronized
    override fun close() = releaseRequest(idle = false)

    private fun releaseRequest(idle: Boolean) {
        handler.removeCallbacks(idleTimeout)
        val registered = callback
        callback = null
        idleReleased = idle
        try {
            if (registered != null) DiagnosticResources.release(DiagnosticResource.MULTICAST_NETWORK, registered) {
                connectivityManager.unregisterNetworkCallback(registered)
            }
        } finally {
            publishState()
            if (registered != null) Log.i(TAG, if (idle) "Released idle multicast Wi-Fi request" else
                "Released multicast Wi-Fi request")
        }
    }

    private fun publishState() = synchronized(state) {
        if (stateOwner == null || stateOwner === this || callback != null) {
            stateOwner = if (callback != null || idleReleased) this else null
            state.value = RuntimeState(callback != null, idleReleased)
        }
    }

    data class RuntimeState(val requested: Boolean, val idleReleased: Boolean)

    companion object {
        private var stateOwner: MulticastWifiRequest? = null
        private val state = MutableStateFlow(RuntimeState(false, false))
        val runtimeState = state.asStateFlow()
        const val TAG = "WearTAK-MulticastWifi"
    }
}
