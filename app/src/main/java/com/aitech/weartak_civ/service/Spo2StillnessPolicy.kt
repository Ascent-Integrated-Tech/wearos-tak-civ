package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized

internal fun spo2StillnessAllowed(
    motion: StationaryMotionPolicy.State,
    activity: ActivityRecognized,
    confidence: Int?,
    nowNanos: Long,
): Boolean = when {
    motion.stationary -> true
    motion.movingUntilNanos > nowNanos -> false
    else -> activity == ActivityRecognized.STILL && (confidence ?: 0) >= 50
}
