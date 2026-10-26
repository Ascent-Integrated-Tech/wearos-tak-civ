package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import org.junit.Assert.*
import org.junit.Test

class StationaryLocationPolicyTest {
    private val start = 100_000_000_000L
    private val dwell = StationaryLocationPolicy.DWELL_NANOS
    private fun StationaryLocationPolicy.sample(now: Long = start, fix: Boolean = true,
        activity: ActivityRecognized = ActivityRecognized.STILL, confidence: Int? = 80,
        enabled: Boolean = true, alerting: Boolean = false, motion: Long = 0) =
        observe(now, fix, activity, confidence, enabled, alerting, motion)

    @Test fun independentEvidenceMustAgreeForTheFullDwell() {
        val policy = StationaryLocationPolicy()
        assertFalse(policy.sample())
        assertEquals(start + dwell, policy.nextEvaluationNanos)
        assertFalse(policy.sample(start + dwell - 1))
        assertTrue(policy.sample(start + dwell))
        assertNull(policy.nextEvaluationNanos)
    }

    @Test fun unknownLowConfidenceOrMissingLocationNeverMeansStationary() {
        listOf<ActivityRecognized>(ActivityRecognized.UNKNOWN, ActivityRecognized.TILTING,
            ActivityRecognized.WALKING, ActivityRecognized.RUNNING, ActivityRecognized.IN_VEHICLE,
            ActivityRecognized.BICYCLING, ActivityRecognized.ON_FOOT).forEach { activity ->
            val policy = StationaryLocationPolicy()
            assertFalse(policy.sample(activity = activity))
            assertFalse(policy.sample(start + 2 * dwell, activity = activity))
        }
        listOf<Int?>(null, 0, 49).forEach { confidence ->
            val policy = StationaryLocationPolicy()
            assertFalse(policy.sample(confidence = confidence))
            assertFalse(policy.sample(start + 2 * dwell, confidence = confidence))
        }
        val policy = StationaryLocationPolicy()
        assertFalse(policy.sample(fix = false))
        assertFalse(policy.sample(start + 2 * dwell, fix = false))
    }

    @Test fun anyDisagreementRestartsTheDwell() {
        val policy = StationaryLocationPolicy()
        policy.sample()
        assertFalse(policy.sample(start + dwell - 1, fix = false))
        assertFalse(policy.sample(start + dwell))
        assertFalse(policy.sample(start + 2 * dwell - 1))
        assertTrue(policy.sample(start + 2 * dwell))
    }

    @Test fun motionAndUserInteractionRestoreImmediatelyAndRequireNewDwell() {
        val policy = StationaryLocationPolicy()
        policy.sample()
        assertTrue(policy.sample(start + dwell))
        val motion = start + dwell + 1
        assertFalse(policy.sample(motion, motion = motion))
        assertEquals(motion + dwell, policy.nextEvaluationNanos)
        assertFalse(policy.sample(motion + dwell, motion = motion))
        assertTrue(policy.sample(motion + 2 * dwell, motion = motion))
        assertFalse(policy.sample(motion + 2 * dwell + 1, activity = ActivityRecognized.WALKING))
    }

    @Test fun alertsAndDisabledLocationAlwaysBypassEvenAfterStationaryEntry() {
        listOf(true, false).forEach { alert ->
            val policy = StationaryLocationPolicy()
            policy.sample()
            assertTrue(policy.sample(start + dwell))
            assertFalse(policy.sample(start + dwell + 1, enabled = alert, alerting = alert))
            assertNull(policy.nextEvaluationNanos)
        }
    }

    @Test fun usesSixtySecondsWithoutShorteningConfiguredOrOffBodyPowerIntervals() {
        listOf(1, 5, 15, 30, 60, 180, 300, 3600).forEach { configured ->
            assertEquals(maxOf(configured, 60), StationaryLocationPolicy.intervalSeconds(configured, true))
            assertEquals(configured, StationaryLocationPolicy.intervalSeconds(configured, false))
        }
    }

    @Test fun staleAlwaysCoversTwoEffectiveIntervalsPlusMargin() {
        listOf(5, 60, 180, 300, 3600).forEach { configured ->
            listOf(false, true).forEach { stationary ->
                val interval = StationaryLocationPolicy.intervalSeconds(configured, stationary)
                assertEquals(2 * interval + 15, StationaryLocationPolicy.staleSeconds(configured, stationary))
            }
        }
    }
}
