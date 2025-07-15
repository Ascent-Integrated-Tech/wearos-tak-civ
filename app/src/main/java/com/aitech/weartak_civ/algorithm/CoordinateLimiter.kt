package com.aitech.weartak_civ.algorithm

import com.aitech.weartak_civ.service.MovementState
import kotlin.math.*

object CoordinateLimiter {
    private const val EARTH_RADIUS = 6371000.0 // meters
    private const val MAX_JUMP_METERS = 5.0
    private const val GPS_FIX_INTERVAL_MILLIS = 5 * 60 * 1000L

    var lastFixTime: Long = System.currentTimeMillis()
    var lastLat: Double? = null
    var lastLon: Double? = null

    fun clampJump(predLat: Double, predLon: Double, movementState: MovementState): Pair<Double, Double> {
        val lat0 = lastLat ?: return predLat to predLon
        val lon0 = lastLon ?: return predLat to predLon

        val dist = haversine(lat0, lon0, predLat, predLon)

        val maxJump = when (movementState) {
            MovementState.WALKING, MovementState.RUNNING, MovementState.STATIONARY -> MAX_JUMP_METERS
            else -> Double.MAX_VALUE // Effectively unlimited
        }

        if (dist <= maxJump) return predLat to predLon

        val bearing = computeBearing(lat0, lon0, predLat, predLon)
        return movePoint(lat0, lon0, maxJump, bearing)
    }


    fun updateLastFix(lat: Double, lon: Double, timestamp: Long = System.currentTimeMillis()) {
        lastLat = lat
        lastLon = lon
        lastFixTime = timestamp
    }

    fun shouldForceGpsFix(): Boolean {
        return System.currentTimeMillis() - lastFixTime > GPS_FIX_INTERVAL_MILLIS
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun computeBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun movePoint(lat: Double, lon: Double, distanceMeters: Double, bearingDeg: Double): Pair<Double, Double> {
        val bearing = Math.toRadians(bearingDeg)
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)

        val newLat = asin(
            sin(latRad) * cos(distanceMeters / EARTH_RADIUS) +
                    cos(latRad) * sin(distanceMeters / EARTH_RADIUS) * cos(bearing)
        )
        val newLon = lonRad + atan2(
            sin(bearing) * sin(distanceMeters / EARTH_RADIUS) * cos(latRad),
            cos(distanceMeters / EARTH_RADIUS) - sin(latRad) * sin(newLat)
        )

        return Math.toDegrees(newLat) to Math.toDegrees(newLon)
    }
}
