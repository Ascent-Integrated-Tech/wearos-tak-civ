package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized

internal class LocationAcquisitionPolicy {
    private var failures = 0
    val backoffLevel: Int get() = (failures - FAILURE_THRESHOLD + 1).coerceAtLeast(0)

    fun failed() {
        failures = (failures + 1).coerceAtMost(FAILURE_THRESHOLD + 9)
    }

    fun reset() { failures = 0 }

    fun intervalMillis(normalMillis: Long): Long =
        if (backoffLevel == 0) normalMillis
        else maxOf(normalMillis, minOf(MAX_BACKOFF_MILLIS, normalMillis * (1L shl backoffLevel)))

    companion object {
        const val FAILURE_THRESHOLD = 3
        const val MAX_BACKOFF_MILLIS = 300_000L
        const val STATIONARY_MAX_AGE_MILLIS = 30_000L

        fun moving(activity: ActivityRecognized): Boolean = activity in setOf(
            ActivityRecognized.WALKING, ActivityRecognized.RUNNING,
            ActivityRecognized.ON_FOOT, ActivityRecognized.IN_VEHICLE, ActivityRecognized.BICYCLING)

        fun stationary(speedMps: Double?, activity: ActivityRecognized): Boolean {
            if (moving(activity)) return false
            return if (speedMps != null) speedMps < SpeedReportingPolicy.STATIONARY_SPEED_MPS
                else activity == ActivityRecognized.STILL
        }

        fun freshEnough(fixNanos: Long, requestedNanos: Long, nowNanos: Long, maxAgeMillis: Long): Boolean =
            fixNanos > 0 && fixNanos <= nowNanos &&
                fixNanos >= requestedNanos - maxAgeMillis * 1_000_000L
    }
}
