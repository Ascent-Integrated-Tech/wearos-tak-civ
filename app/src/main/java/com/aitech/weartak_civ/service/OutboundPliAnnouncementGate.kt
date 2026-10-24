package com.aitech.weartak_civ.service

import android.location.Location

internal class OutboundPliAnnouncementGate {
    private var ready = emptySet<String>()
    private val pending = mutableSetOf<String>()
    private var requestedNanos = 0L

    @Synchronized
    fun updateReady(routes: Set<String>, nowNanos: Long): Boolean {
        val added = routes - ready
        ready = routes
        pending.retainAll(routes)
        pending.addAll(added)
        if (added.isNotEmpty()) requestedNanos = nowNanos
        return added.isNotEmpty()
    }

    @Synchronized
    fun hasPending(): Boolean = pending.isNotEmpty()

    @Synchronized
    fun canDispatch(location: Location, nowNanos: Long): Boolean =
        pending.isNotEmpty() && location.elapsedRealtimeNanos >= requestedNanos && fresh(location, nowNanos)

    @Synchronized
    fun complete() {
        pending.clear()
    }

    companion object {
        fun fresh(location: Location, nowNanos: Long): Boolean =
            location.time > 0 && location.altitude.isFinite() &&
                location.elapsedRealtimeNanos > 0 && location.elapsedRealtimeNanos <= nowNanos &&
                nowNanos - location.elapsedRealtimeNanos <= InteractionPliRefreshGate.TIMEOUT_MILLIS * 1_000_000L &&
                InteractionPliRefreshGate.usable(location.latitude, location.longitude,
                    location.accuracy, location.hasAccuracy())
    }
}
