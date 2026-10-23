package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.events.PliRefreshReason

/** One bounded acquisition shared by overlapping interactions; never extends its deadline. */
internal class InteractionPliRefreshGate {
    private val reasons = linkedSetOf<PliRefreshReason>()
    private var latestRequestNanos = 0L
    private var startedNanos = 0L

    fun request(reason: PliRefreshReason, nowNanos: Long): Boolean {
        val start = reasons.isEmpty()
        if (start) startedNanos = nowNanos
        reasons.add(reason)
        latestRequestNanos = nowNanos
        return start
    }

    fun consume(fixNanos: Long, nowNanos: Long, usable: Boolean): Set<PliRefreshReason>? {
        if (reasons.isEmpty() || !usable || nowNanos - startedNanos > TIMEOUT_MILLIS * 1_000_000L ||
            fixNanos < latestRequestNanos ||
            fixNanos > nowNanos || nowNanos - fixNanos > TIMEOUT_MILLIS * 1_000_000L) return null
        return reasons.toSet().also { cancel() }
    }

    fun cancel() {
        reasons.clear()
    }

    companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val IDENTITY_FIX_MAX_AGE_MILLIS = 3_000L

        fun freshForIdentity(fixNanos: Long, nowNanos: Long, usable: Boolean): Boolean =
            usable && fixNanos > 0L && nowNanos >= fixNanos &&
                nowNanos - fixNanos <= IDENTITY_FIX_MAX_AGE_MILLIS * 1_000_000L

        fun usable(latitude: Double, longitude: Double, accuracy: Float, hasAccuracy: Boolean) =
            latitude.isFinite() && latitude in -90.0..90.0 &&
                longitude.isFinite() && longitude in -180.0..180.0 &&
                hasAccuracy && accuracy.isFinite() && accuracy >= 0f
    }
}
