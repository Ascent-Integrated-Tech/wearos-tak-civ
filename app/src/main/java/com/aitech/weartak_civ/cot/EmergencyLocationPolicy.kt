package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository.LocationSnapshot

internal object EmergencyLocationPolicy {
    fun usable(location: LocationSnapshot): Boolean =
        location.timeMillis > 0 && location.lat.isFinite() && location.lat in -90.0..90.0 &&
            location.lon.isFinite() && location.lon in -180.0..180.0

    fun forDispatch(location: LocationSnapshot?, nowMillis: Long): LocationSnapshot? {
        if (location == null || !usable(location)) return null
        val ageSeconds = ((nowMillis - location.timeMillis).coerceAtLeast(0) / 1000.0 - 3).coerceAtLeast(0.0)
        // Stale positions are estimates, not fresh GPS: allow at least 5 m/s of movement.
        val movement = maxOf(5.0, location.speed?.takeIf { it.isFinite() && it >= 0 }?.toDouble() ?: 0.0)
        val accuracy = location.ce?.takeIf { it.isFinite() && it >= 0 }?.toDouble() ?: 9_999_999.0
        return location.copy(
            ce = (accuracy + ageSeconds * movement).coerceAtMost(9_999_999.0).toFloat(),
            hae = location.hae?.takeIf { it.isFinite() },
        )
    }
}
