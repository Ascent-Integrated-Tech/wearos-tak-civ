package com.aitech.weartak_civ.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class CompanionTransportDebounce(
    private val scope: CoroutineScope,
    private val graceMillis: Long = DISCONNECT_GRACE_MILLIS,
) {
    private val _graceActive = MutableStateFlow(false)
    val graceActive: StateFlow<Boolean> = _graceActive.asStateFlow()
    private var graceJob: Job? = null

    @Synchronized
    fun connected() {
        graceJob?.cancel()
        graceJob = null
        _graceActive.value = false
    }

    @Synchronized
    fun disconnected(wasConnected: Boolean) {
        if (!wasConnected) return
        beginGracePeriod()
    }

    @Synchronized
    fun awaitingInitialConnection() {
        if (!_graceActive.value) beginGracePeriod()
    }

    @Synchronized
    private fun beginGracePeriod() {
        graceJob?.cancel()
        _graceActive.value = true
        graceJob = scope.launch {
            delay(graceMillis)
            synchronized(this@CompanionTransportDebounce) {
                _graceActive.value = false
                graceJob = null
            }
        }
    }

    @Synchronized
    fun resumeImmediately(): Boolean {
        if (!_graceActive.value) return false
        graceJob?.cancel()
        graceJob = null
        _graceActive.value = false
        return true
    }

    companion object {
        const val DISCONNECT_GRACE_MILLIS = 45_000L
    }
}
