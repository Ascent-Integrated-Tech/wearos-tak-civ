package com.aitech.weartak_civ.provider

data class TargetVector(
    val distance: Double? = null,
    val bearing: Double? = null
)

object LocationVectorCalculator {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun calculate(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): TargetVector {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val deltaLat = Math.toRadians(toLat - fromLat)
        val deltaLon = Math.toRadians(toLon - fromLon)

        val sinLat = kotlin.math.sin(deltaLat / 2)
        val sinLon = kotlin.math.sin(deltaLon / 2)
        val a = sinLat * sinLat +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * sinLon * sinLon
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        val distanceKm = (EARTH_RADIUS_M * c) / 1000.0

        val y = kotlin.math.sin(deltaLon) * kotlin.math.cos(lat2)
        val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
            kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(deltaLon)
        val bearingDeg = normalize0to360(Math.toDegrees(kotlin.math.atan2(y, x)))

        return TargetVector(distance = distanceKm, bearing = bearingDeg)
    }

    fun normalize0to360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    fun smoothAngle(prevDeg: Double, newDeg: Double, alpha: Double): Double {
        var delta = newDeg - prevDeg
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return normalize0to360(prevDeg + alpha * delta)
    }

    fun fuseHeading(
        useGpsHeading: Boolean,
        gpsCogTrueDeg: Double?,
        magAzTrueDeg: Double?
    ): Double? {
        val chosen = if (useGpsHeading) {
            gpsCogTrueDeg ?: magAzTrueDeg
        } else {
            magAzTrueDeg ?: gpsCogTrueDeg
        }
        return chosen?.let(::normalize0to360)
    }

    fun needleAngle(targetBearingDeg: Double?, fusedHeadingDeg: Double?): Double? {
        if (targetBearingDeg == null || fusedHeadingDeg == null) return targetBearingDeg
        return normalize0to360(targetBearingDeg - fusedHeadingDeg)
    }
}
