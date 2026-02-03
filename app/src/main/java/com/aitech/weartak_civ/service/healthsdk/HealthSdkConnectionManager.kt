package com.aitech.weartak_civ.service.healthsdk

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val isHeartRateSupported: Boolean) : ConnectionState()
    data class Failed(val error: HealthSdkError) : ConnectionState()
}

class HealthSdkConnectionManager {
    companion object {
        private const val TAG = "WearTAK-HealthSdkConn"
    }

    private var service: HealthTrackingService? = null
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _state

    private val listener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            val s = service ?: run {
                _state.value = ConnectionState.Failed(HealthSdkError.Failed("Service null on success"))
                return
            }
            val supported = try {
                s.trackingCapability.supportHealthTrackerTypes.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)
            } catch (t: Throwable) {
                Log.w(TAG, "Capability check failed: ${t.message}")
                false
            }
            _state.value = ConnectionState.Connected(supported)
            Log.i(TAG, "Connected; HEART_RATE_CONTINUOUS supported=$supported")
        }

        override fun onConnectionEnded() {
            Log.w(TAG, "Connection ended")
            _state.value = ConnectionState.Disconnected
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e(TAG, "Connection failed: msg=${e.message} code=${e.errorCode} hasResolution=${e.hasResolution()}", e)
            val err = if (e.hasResolution()) HealthSdkError.ResolutionRequired else HealthSdkError.Failed(e.message)
            _state.value = ConnectionState.Failed(err)
        }
    }

    fun connect(appContext: Context) {
        if (_state.value is ConnectionState.Connecting) return
        _state.value = ConnectionState.Connecting
        try {
            service = HealthTrackingService(listener, appContext.applicationContext)
            service?.connectService()
        } catch (t: Throwable) {
            Log.e(TAG, "connect() threw: ${t.message}", t)
            _state.value = ConnectionState.Failed(HealthSdkError.Failed(t.message))
        }
    }

    fun disconnect() {
        try {
            service?.disconnectService()
        } catch (_: Throwable) { }
        service = null
        _state.value = ConnectionState.Disconnected
    }

    fun getHeartRateTrackerOrNull(): HealthTracker? {
        val curr = _state.value
        val s = service ?: return null
        if (curr is ConnectionState.Connected && curr.isHeartRateSupported) {
            return try {
                s.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            } catch (t: Throwable) {
                Log.e(TAG, "getHeartRateTracker failed", t)
                null
            }
        }
        return null
    }
}


