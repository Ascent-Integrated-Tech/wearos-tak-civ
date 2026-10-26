package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class StationaryMotionPolicy {
    data class State(
        val stationary: Boolean = false,
        val movingUntilNanos: Long = 0,
        val confirmedSpeedMps: Double? = null,
    )

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    fun hold() { mutableState.value = State(stationary = true) }
    fun clear() { mutableState.value = State() }
    fun confirm(untilNanos: Long, speedMps: Double?) {
        mutableState.value = State(movingUntilNanos = untilNanos, confirmedSpeedMps = speedMps)
    }

    fun expire(expected: State, nowNanos: Long) {
        if (mutableState.value == expected && expected.movingUntilNanos in 1..nowNanos) clear()
    }

    fun activity(activity: ActivityRecognized, nowNanos: Long): ActivityRecognized {
        val current = state.value
        return when {
            current.stationary -> ActivityRecognized.STILL
            current.movingUntilNanos > nowNanos && !LocationAcquisitionPolicy.moving(activity) ->
                ActivityRecognized.UNKNOWN
            else -> activity
        }
    }

    fun speed(speedMps: Double?, nowNanos: Long): Double? {
        val current = state.value
        return when {
            current.stationary -> null
            current.movingUntilNanos > nowNanos -> current.confirmedSpeedMps
            else -> speedMps
        }
    }

    companion object {
        const val MAX_ACCURACY_METERS = 25.0
        const val MIN_SPEED_MPS = 1.0
        const val MIN_DISTANCE_METERS = 15.0

        fun reliableSpeed(speed: Double?, uncertainty: Double?): Double? =
            speed?.takeIf {
                it.isFinite() && uncertainty != null && uncertainty.isFinite() && uncertainty >= 0 &&
                    it >= maxOf(MIN_SPEED_MPS, SpeedReportingPolicy.STATIONARY_SPEED_MPS + 2 * uncertainty)
            }

        fun usableAccuracy(accuracy: Double?): Boolean =
            accuracy != null && accuracy.isFinite() && accuracy in 0.0..MAX_ACCURACY_METERS

        fun moved(accuracy: Double?, speed: Double?, speedUncertainty: Double?,
            distance: Double?, anchorAccuracy: Double?): Boolean {
            if (!usableAccuracy(accuracy)) return false
            if (reliableSpeed(speed, speedUncertainty) != null) return true
            return usableAccuracy(anchorAccuracy) && distance != null && distance.isFinite() &&
                distance > maxOf(MIN_DISTANCE_METERS, 2 * (requireNotNull(accuracy) + requireNotNull(anchorAccuracy)))
        }
    }
}
