package com.aitech.weartak_civ.service.healthsdk

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class HealthStreamEvent {
    data class HeartRateSample(val status: Int, val bpm: Int, val tsMs: Long) : HealthStreamEvent()
    data class IbiBatch(val ibiMs: List<Int>, val ibiStatus: List<Int>?, val tsMs: Long) : HealthStreamEvent()
    data class Error(val error: HealthSdkError, val tsMs: Long) : HealthStreamEvent()
    data class HeartSnapshot(val hr: Int, val hrStatus: Int, val ibiMs: Int, val tsMs: Long) : HealthStreamEvent()
}

sealed class HealthSdkError {
    object ResolutionRequired : HealthSdkError()
    data class Failed(val message: String?) : HealthSdkError()
    object PermissionDenied : HealthSdkError()
    object SdkPolicyError : HealthSdkError()
}

class HealthStreamBus {
    companion object {
        val shared: HealthStreamBus by lazy { HealthStreamBus() }
    }

    private val _events = MutableSharedFlow<HealthStreamEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<HealthStreamEvent> = _events

    // Device worn state (plumbing only; gating not enforced here)
    private val _deviceWorn = MutableStateFlow(true)
    val deviceWorn: StateFlow<Boolean> = _deviceWorn.asStateFlow()

    fun emit(event: HealthStreamEvent) {
        _events.tryEmit(event)
    }

    fun onSamsungHrIbiSnapshot(
        hr: Int,
        hrStatus: Int,
        ibiMs: Int,
        lastCallbackElapsedRealtime: Long
    ) {
        _events.tryEmit(HealthStreamEvent.HeartSnapshot(hr, hrStatus, ibiMs, System.currentTimeMillis()))
    }

    fun publishDeviceWorn(worn: Boolean, source: String) {
        Log.i("HealthStreamBus", "deviceWorn=$worn source=$source")
        _deviceWorn.value = worn
    }
}

fun computeRmssdMs(ibiMs: List<Int>): Double {
    if (ibiMs.size < 2) return 0.0
    var sumSq = 0.0
    var n = 0
    for (i in 1 until ibiMs.size) {
        val diff = (ibiMs[i] - ibiMs[i - 1]).toDouble()
        sumSq += diff * diff
        n++
    }
    return if (n > 0) Math.sqrt(sumSq / n) else 0.0
}


