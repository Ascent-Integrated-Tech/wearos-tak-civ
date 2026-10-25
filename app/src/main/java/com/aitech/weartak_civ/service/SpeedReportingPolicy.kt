package com.aitech.weartak_civ.service

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object SpeedReportingPolicy {
    const val STATIONARY_SPEED_MPS = 0.44704
    const val VEHICLE_SPEED_MPS = 13.4112
    const val OBSERVATION_CAP_SECONDS = 60

    fun desiredObservationIntervalSeconds(selectedSeconds: Int, onFootSeconds: Int, vehicleSeconds: Int): Int =
        minOf(OBSERVATION_CAP_SECONDS, selectedSeconds, onFootSeconds, vehicleSeconds)

    fun validSpeed(
        speedMps: Double?,
        observedElapsedNanos: Long,
        nowElapsedNanos: Long,
        currentInput: Boolean,
        validUntilElapsedNanos: Long,
    ): Double? {
        if (!currentInput || speedMps == null || !speedMps.isFinite() || speedMps < 0 ||
            observedElapsedNanos <= 0 || nowElapsedNanos < observedElapsedNanos ||
            nowElapsedNanos > validUntilElapsedNanos) return null
        return speedMps
    }

    fun observationIntervalSeconds(
        dynamic: Boolean,
        desiredReportSeconds: Int,
        actualReportSeconds: Int,
        powerThrottled: Boolean,
        dockThrottled: Boolean,
    ): Int {
        if (!dynamic) return actualReportSeconds
        if (dockThrottled) return com.aitech.weartak_civ.DEFAULT_DOCKED_REPORTING_INTERVAL_SECS
        val interval = min(desiredReportSeconds, OBSERVATION_CAP_SECONDS)
        return if (powerThrottled) PowerManagerService.slowDownReportingInterval(interval) else interval
    }

    fun intervalSeconds(
        speedMps: Double?,
        alerting: Boolean,
        alertingSeconds: Int,
        onFootSeconds: Int,
        vehicleSeconds: Int,
        stationarySeconds: Int,
    ): Int {
        require(listOf(alertingSeconds, onFootSeconds, vehicleSeconds, stationarySeconds).all { it > 0 })
        if (alerting) return alertingSeconds
        if (speedMps == null || !speedMps.isFinite() || speedMps < 0) {
            return max(onFootSeconds, vehicleSeconds)
        }
        if (speedMps < STATIONARY_SPEED_MPS) return stationarySeconds
        val fraction = ((speedMps - STATIONARY_SPEED_MPS) /
            (VEHICLE_SPEED_MPS - STATIONARY_SPEED_MPS)).coerceIn(0.0, 1.0)
        return floor(onFootSeconds.toDouble() +
            (vehicleSeconds.toDouble() - onFootSeconds) * fraction + 0.5).toInt()
            .coerceIn(min(onFootSeconds, vehicleSeconds), max(onFootSeconds, vehicleSeconds))
    }
}
