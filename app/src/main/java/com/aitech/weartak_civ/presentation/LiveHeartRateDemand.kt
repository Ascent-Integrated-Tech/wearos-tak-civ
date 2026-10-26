package com.aitech.weartak_civ.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aitech.weartak_civ.service.HeartRateDelivery

internal class LiveHeartRateDemand(private val lifecycle: Lifecycle) :
    LifecycleEventObserver, AutoCloseable {
    private var request: AutoCloseable? = null

    init {
        lifecycle.addObserver(this)
        update()
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) = update()

    private fun update() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (request == null) request = HeartRateDelivery.requestLive()
        } else {
            request?.close()
            request = null
        }
    }

    override fun close() {
        lifecycle.removeObserver(this)
        request?.close()
        request = null
    }
}

@Composable
internal fun ObserveLiveHeartRate(enabled: Boolean) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, enabled) {
        val demand = if (enabled) LiveHeartRateDemand(owner.lifecycle) else null
        onDispose { demand?.close() }
    }
}
