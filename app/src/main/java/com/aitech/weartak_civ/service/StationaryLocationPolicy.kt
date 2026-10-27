package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized

/** Unknown activity requires independent, affirmative off-body evidence to relax acquisition. */
internal class StationaryLocationPolicy {
    private var candidateSinceNanos: Long? = null
    var nextEvaluationNanos: Long? = null
        private set
    var minimumIntervalSeconds = INTERVAL_SECONDS
        private set

    fun observe(nowNanos: Long, stationaryFix: Boolean, activity: ActivityRecognized,
        confidence: Int?, enabled: Boolean, alerting: Boolean, lastMotionNanos: Long,
        interactive: Boolean = true, worn: Boolean? = null, navigating: Boolean = false): Boolean {
        nextEvaluationNanos = null
        minimumIntervalSeconds = INTERVAL_SECONDS
        val movingUntil = lastMotionNanos + DWELL_NANOS
        if (!enabled || alerting || navigating || LocationAcquisitionPolicy.moving(activity)) {
            candidateSinceNanos = null
            return false
        }
        val recentMotion = lastMotionNanos > 0 && nowNanos < movingUntil
        if (!interactive && worn == false && !recentMotion) {
            candidateSinceNanos = null
            minimumIntervalSeconds = IDLE_INTERVAL_SECONDS
            return true
        }
        if (!enabled || alerting || !stationaryFix || activity != ActivityRecognized.STILL ||
            (confidence ?: 0) < MIN_CONFIDENCE || recentMotion) {
            candidateSinceNanos = null
            if (recentMotion && ((!interactive && worn == false) ||
                    (stationaryFix && activity == ActivityRecognized.STILL &&
                        (confidence ?: 0) >= MIN_CONFIDENCE)))
                nextEvaluationNanos = movingUntil
            return false
        }
        val since = candidateSinceNanos ?: nowNanos.also { candidateSinceNanos = it }
        if (nowNanos - since < DWELL_NANOS) {
            nextEvaluationNanos = since + DWELL_NANOS
            return false
        }
        if (!interactive) {
            val stationarySince = since + DWELL_NANOS
            val elapsed = nowNanos - stationarySince
            minimumIntervalSeconds = when {
                elapsed >= IDLE_AFTER_NANOS -> IDLE_INTERVAL_SECONDS
                elapsed >= EXTENDED_AFTER_NANOS -> EXTENDED_INTERVAL_SECONDS
                else -> INTERVAL_SECONDS
            }
            nextEvaluationNanos = when {
                elapsed < EXTENDED_AFTER_NANOS -> stationarySince + EXTENDED_AFTER_NANOS
                elapsed < IDLE_AFTER_NANOS -> stationarySince + IDLE_AFTER_NANOS
                else -> null
            }
        }
        return true
    }

    companion object {
        const val INTERVAL_SECONDS = 60
        const val EXTENDED_INTERVAL_SECONDS = 120
        const val IDLE_INTERVAL_SECONDS = 300
        const val EXTENDED_AFTER_NANOS = 120_000_000_000L
        const val IDLE_AFTER_NANOS = 300_000_000_000L
        const val MIN_CONFIDENCE = 50
        const val DWELL_NANOS = 30_000_000_000L

        fun intervalSeconds(normalSeconds: Int, stationary: Boolean,
            minimumSeconds: Int = INTERVAL_SECONDS): Int =
            if (stationary) maxOf(normalSeconds, minimumSeconds) else normalSeconds

        fun staleSeconds(intervalSeconds: Int, stationary: Boolean,
            minimumSeconds: Int = INTERVAL_SECONDS): Int =
            2 * intervalSeconds(intervalSeconds, stationary, minimumSeconds) + 15
    }
}
