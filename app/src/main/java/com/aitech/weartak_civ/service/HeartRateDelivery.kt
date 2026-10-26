package com.aitech.weartak_civ.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class HeartRateSample(val bpm: Int, val measuredAtMillis: Long)

internal interface SamsungHeartRateSource {
    fun start(onSample: (HeartRateSample) -> Unit, onFailure: () -> Unit): Boolean
    fun stop(): Boolean
}

/** Main-thread source ownership; a connected SDK is not necessarily a running HR tracker. */
internal object HeartRateDelivery {
    val samsungSource = MutableStateFlow<SamsungHeartRateSource?>(null)
    private val requests = mutableSetOf<Any>()
    private val _live = MutableStateFlow(false)
    val live = _live.asStateFlow()

    fun requestLive(): AutoCloseable {
        val token = Any()
        synchronized(requests) {
            requests.add(token)
            _live.value = true
        }
        return AutoCloseable {
            synchronized(requests) {
                requests.remove(token)
                _live.value = requests.isNotEmpty()
            }
        }
    }
}
