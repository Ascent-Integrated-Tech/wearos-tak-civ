package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized

/** Requires independent location and activity evidence; unknown is never stationary. */
internal class StationaryLocationPolicy {
    private var candidateSinceNanos: Long? = null
    var nextEvaluationNanos: Long? = null
        private set

    fun observe(nowNanos: Long, stationaryFix: Boolean, activity: ActivityRecognized,
        confidence: Int?, enabled: Boolean, alerting: Boolean, lastMotionNanos: Long): Boolean {
        nextEvaluationNanos = null
        val movingUntil = lastMotionNanos + DWELL_NANOS
        if (!enabled || alerting || !stationaryFix || activity != ActivityRecognized.STILL ||
            (confidence ?: 0) < MIN_CONFIDENCE || (lastMotionNanos > 0 && nowNanos < movingUntil)) {
            candidateSinceNanos = null
            if (enabled && !alerting && stationaryFix && activity == ActivityRecognized.STILL &&
                (confidence ?: 0) >= MIN_CONFIDENCE && lastMotionNanos > 0 && nowNanos < movingUntil)
                nextEvaluationNanos = movingUntil
            return false
        }
        val since = candidateSinceNanos ?: nowNanos.also { candidateSinceNanos = it }
        if (nowNanos - since < DWELL_NANOS) {
            nextEvaluationNanos = since + DWELL_NANOS
            return false
        }
        return true
    }

    companion object {
        const val INTERVAL_SECONDS = 60
        const val MIN_CONFIDENCE = 50
        const val DWELL_NANOS = 30_000_000_000L

        fun intervalSeconds(normalSeconds: Int, stationary: Boolean): Int =
            if (stationary) maxOf(normalSeconds, INTERVAL_SECONDS) else normalSeconds

        fun staleSeconds(intervalSeconds: Int, stationary: Boolean): Int =
            2 * intervalSeconds(intervalSeconds, stationary) + 15
    }
}
