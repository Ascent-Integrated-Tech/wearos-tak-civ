package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import org.junit.Assert.*
import org.junit.Test

class LocationAcquisitionPolicyTest {
    @Test fun thirdFailureStartsExponentialBackoffAndCapsWithoutShorteningNormalCadence() {
        val policy = LocationAcquisitionPolicy()
        repeat(2) {
            policy.failed()
            assertEquals(0, policy.backoffLevel)
            assertEquals(30_000L, policy.intervalMillis(30_000L))
        }
        listOf(60_000L, 120_000L, 240_000L, 300_000L).forEach {
            policy.failed()
            assertEquals(it, policy.intervalMillis(30_000L))
        }
        repeat(100) { policy.failed() }
        assertEquals(300_000L, policy.intervalMillis(30_000L))
        assertEquals(3_600_000L, policy.intervalMillis(3_600_000L))
        policy.reset()
        assertEquals(0, policy.backoffLevel)
        assertEquals(30_000L, policy.intervalMillis(30_000L))
        repeat(2) { policy.failed() }
        assertEquals(0, policy.backoffLevel)
    }

    @Test fun unknownDoesNotMeanStillAndDetectedMotionOverridesStationarySpeed() {
        assertFalse(LocationAcquisitionPolicy.stationary(null, ActivityRecognized.UNKNOWN))
        assertTrue(LocationAcquisitionPolicy.stationary(null, ActivityRecognized.STILL))
        assertTrue(LocationAcquisitionPolicy.stationary(0.0, ActivityRecognized.UNKNOWN))
        assertFalse(LocationAcquisitionPolicy.stationary(
            SpeedReportingPolicy.STATIONARY_SPEED_MPS, ActivityRecognized.STILL))
        ActivityRecognized.entries.filter(LocationAcquisitionPolicy::moving).forEach {
            assertFalse(LocationAcquisitionPolicy.stationary(0.0, it))
        }
    }

    @Test fun recentFixAgeIsBoundedAndHighAccuracyRemainsFreshOnly() {
        val requested = 100_000_000_000L
        assertTrue(LocationAcquisitionPolicy.freshEnough(70_000_000_000L, requested, requested, 30_000))
        assertFalse(LocationAcquisitionPolicy.freshEnough(69_999_999_999L, requested, requested, 30_000))
        assertFalse(LocationAcquisitionPolicy.freshEnough(requested - 1, requested, requested, 0))
        assertTrue(LocationAcquisitionPolicy.freshEnough(requested, requested, requested, 0))
        assertFalse(LocationAcquisitionPolicy.freshEnough(requested + 1, requested, requested, 30_000))
        assertFalse(LocationAcquisitionPolicy.freshEnough(0, requested, requested, 30_000))
    }
}
