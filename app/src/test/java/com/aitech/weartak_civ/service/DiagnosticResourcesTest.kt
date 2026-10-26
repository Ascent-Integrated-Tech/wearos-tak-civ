package com.aitech.weartak_civ.service

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class DiagnosticResourcesTest {
    private var now = 100L
    private val rows = mutableListOf<Map<DiagnosticMetric, Long>>()
    private val tracker = DiagnosticResourceTracker { now }
    @After fun cleanup() {
        tracker.stop()
        rows.clear()
    }
    private fun start() = tracker.start { event, metrics ->
        assertEquals(DiagnosticEvent.RESOURCE_LIFETIME, event)
        rows.add(metrics)
        true
    }
    private fun summary(id: DiagnosticResource) = rows.last {
        it[DiagnosticMetric.RESOURCE_ID] == id.code &&
            it[DiagnosticMetric.ACTION] == ResourceAction.SUMMARY.code
    }

    @Test fun successfulPairPreservesTokenAndMeasuredDuration() {
        start()
        val owner = Any()
        tracker.begin(DiagnosticResource.HEART_RATE, owner, periodUs = 200_000)
        now = 350
        tracker.end(DiagnosticResource.HEART_RATE, owner)
        assertEquals(rows[0][DiagnosticMetric.TOKEN], rows[1][DiagnosticMetric.TOKEN])
        assertEquals(250L, rows[1][DiagnosticMetric.DURATION_MS])
        assertEquals(0L, rows[1][DiagnosticMetric.ACTIVE])
        assertEquals(200_000L, rows[0][DiagnosticMetric.PERIOD_US])
    }

    @Test fun overlappingHoldsUseUnionRatherThanSum() {
        start()
        val first = Any()
        val second = Any()
        tracker.begin(DiagnosticResource.DEBUG_CPU, first)
        now = 150
        tracker.begin(DiagnosticResource.DEBUG_CPU, second)
        now = 200
        tracker.end(DiagnosticResource.DEBUG_CPU, first)
        now = 300
        tracker.end(DiagnosticResource.DEBUG_CPU, second)
        tracker.flush()
        val row = summary(DiagnosticResource.DEBUG_CPU)
        assertEquals(200L, row[DiagnosticMetric.UNION_MS])
        assertEquals(2L, row[DiagnosticMetric.COUNT])
    }

    @Test fun enableMidHoldAndClearKeepOperationalOwnership() {
        val owner = Any()
        tracker.begin(DiagnosticResource.MULTICAST_LOCK, owner)
        assertTrue(rows.isEmpty())
        now = 200
        start()
        assertEquals(ResourceAction.ALREADY_ACTIVE.code, rows.single()[DiagnosticMetric.ACTION])
        assertEquals(100L, rows.single()[DiagnosticMetric.START_MS])
        tracker.stop()
        now = 300
        start()
        tracker.reset()
        now = 400
        tracker.end(DiagnosticResource.MULTICAST_LOCK, owner)
        tracker.flush()
        assertEquals(100L, summary(DiagnosticResource.MULTICAST_LOCK)[DiagnosticMetric.UNION_MS])
        assertEquals(0L, summary(DiagnosticResource.MULTICAST_LOCK)[DiagnosticMetric.ACTIVE])
    }

    @Test fun failedRegistrationIsNotActiveAndFailedRemovalDoesNotAssertEnd() {
        start()
        val owner = Any()
        tracker.note(DiagnosticResource.HEADING, owner, ResourceAction.FAILED)
        assertEquals(0L, rows.last()[DiagnosticMetric.ACTIVE])
        tracker.begin(DiagnosticResource.HEADING, owner)
        tracker.note(DiagnosticResource.HEADING, owner, ResourceAction.REMOVAL_REQUESTED)
        tracker.note(DiagnosticResource.HEADING, owner, ResourceAction.UNCERTAIN)
        tracker.flush()
        assertEquals(1L, summary(DiagnosticResource.HEADING)[DiagnosticMetric.ACTIVE])
        assertEquals(2L, summary(DiagnosticResource.HEADING)[DiagnosticMetric.FAILURES])
        assertFalse(rows.any { it[DiagnosticMetric.ACTION] == ResourceAction.END.code })
    }

    @Test fun inventoryAndResourceClassesAreBoundedAndOverflowIsExplicit() {
        start()
        val owners = List(65) { Any() }
        owners.forEach { tracker.begin(DiagnosticResource.SAMSUNG_PPG, it) }
        assertEquals(ResourceAction.OVERFLOW.code, rows.last()[DiagnosticMetric.ACTION])
        assertEquals(64L, rows.last()[DiagnosticMetric.ACTIVE])
        assertTrue(DiagnosticResource.values().size <= 32)
        owners.take(64).forEach { tracker.end(DiagnosticResource.SAMSUNG_PPG, it) }
        tracker.flush()
        assertEquals(0L, summary(DiagnosticResource.SAMSUNG_PPG)[DiagnosticMetric.ACTIVE])
        assertEquals(1L, summary(DiagnosticResource.SAMSUNG_PPG)[DiagnosticMetric.FAILURES])
    }

    @Test fun timedExpiryReportsObservationNotInventedExactDuration() {
        start()
        val owner = Any()
        tracker.begin(DiagnosticResource.LOCATION_CPU, owner, timeoutMs = 6_000)
        now = 10_000
        tracker.end(DiagnosticResource.LOCATION_CPU, owner, expired = true)
        assertEquals(ResourceAction.EXPIRED_OBSERVED.code, rows.last()[DiagnosticMetric.ACTION])
        assertFalse(rows.last().containsKey(DiagnosticMetric.DURATION_MS))
        tracker.flush()
        assertEquals(6_000L, summary(DiagnosticResource.LOCATION_CPU)[DiagnosticMetric.UNION_MS])
    }

    @Test fun rejectedDetailIsCountedWithoutLosingInventory() {
        var accept = false
        tracker.start { _, metrics ->
            if (accept) rows.add(metrics)
            accept
        }
        val owner = Any()
        tracker.begin(DiagnosticResource.PASSIVE_HEALTH, owner)
        accept = true
        tracker.flush()
        assertEquals(1L, summary(DiagnosticResource.PASSIVE_HEALTH)[DiagnosticMetric.SUPPRESSED])
        assertEquals(1L, summary(DiagnosticResource.PASSIVE_HEALTH)[DiagnosticMetric.ACTIVE])
        tracker.end(DiagnosticResource.PASSIVE_HEALTH, owner)
        tracker.flush()
        assertEquals(0L, summary(DiagnosticResource.PASSIVE_HEALTH)[DiagnosticMetric.ACTIVE])
    }

    @Test fun expiryBoundsStillRespectOverlappingIntervals() {
        start()
        val first = Any()
        val second = Any()
        tracker.begin(DiagnosticResource.LOCATION_CPU, first, timeoutMs = 100)
        now = 150
        tracker.begin(DiagnosticResource.LOCATION_CPU, second, timeoutMs = 100)
        now = 500
        tracker.end(DiagnosticResource.LOCATION_CPU, first, expired = true)
        tracker.end(DiagnosticResource.LOCATION_CPU, second, expired = true)
        tracker.flush()
        assertEquals(150L, summary(DiagnosticResource.LOCATION_CPU)[DiagnosticMetric.UNION_MS])
        assertEquals(0L, summary(DiagnosticResource.LOCATION_CPU)[DiagnosticMetric.RESULT])
    }

    @Test fun resetRejectsRemainderOfOldLifecycleBatch() {
        tracker.begin(DiagnosticResource.HEADING, Any())
        tracker.begin(DiagnosticResource.HEADING, Any())
        var resetOnce = false
        tracker.start { _, metrics ->
            rows.add(metrics)
            if (!resetOnce) {
                resetOnce = true
                tracker.reset()
            }
            true
        }
        assertEquals(1, rows.size)
        tracker.flush()
        assertEquals(1, rows.size)
    }

    @Test fun measurementDeadlineDoesNotPretendListenerAutomaticallyExpired() {
        start()
        val owner = Any()
        tracker.begin(DiagnosticResource.SAMSUNG_SPO2, owner, timeoutMs = 100)
        now = 500
        tracker.flush()
        assertEquals(400L, summary(DiagnosticResource.SAMSUNG_SPO2)[DiagnosticMetric.UNION_MS])
        assertEquals(1L, summary(DiagnosticResource.SAMSUNG_SPO2)[DiagnosticMetric.ACTIVE])
    }

    @Test fun uncertainOwnershipSurvivesResetAndEnableEpoch() {
        start()
        val owner = Any()
        tracker.begin(DiagnosticResource.SAMSUNG_HR, owner)
        tracker.note(DiagnosticResource.SAMSUNG_HR, owner, ResourceAction.UNCERTAIN)
        tracker.reset()
        rows.clear()
        tracker.flush()
        assertTrue(rows.isEmpty())
        tracker.begin(DiagnosticResource.SAMSUNG_HR, owner)
        assertEquals(0L, rows.last()[DiagnosticMetric.RESULT])
        tracker.flush()
        assertEquals(0L, summary(DiagnosticResource.SAMSUNG_HR)[DiagnosticMetric.RESULT])
        tracker.stop()
        start()
        assertEquals(0L, rows.last()[DiagnosticMetric.RESULT])
    }

    @Test fun stopFlushesOnceAndOffFlushDoesNotEmitOrSampleClock() {
        var reads = 0
        val local = DiagnosticResourceTracker { reads++; now }
        local.start { _, metrics -> rows.add(metrics); true }
        local.begin(DiagnosticResource.DEBUG_CPU, Any())
        local.stop()
        assertEquals(ResourceAction.SUMMARY.code, rows.last()[DiagnosticMetric.ACTION])
        val previousReads = reads
        val previousRows = rows.size
        local.flush()
        local.stop()
        assertEquals(previousReads, reads)
        assertEquals(previousRows, rows.size)
    }

    @Test fun cleanupUncertaintyForInactiveTrackerDoesNotInventFailure() {
        start()
        tracker.note(DiagnosticResource.SAMSUNG_PPG, Any(), ResourceAction.UNCERTAIN, onlyIfActive = true)
        tracker.flush()
        assertTrue(rows.isEmpty())
    }

    @Test fun resetCanReplaceGenerationBoundEmitterWithoutLosingHold() {
        start()
        val owner = Any()
        tracker.begin(DiagnosticResource.HEADING, owner)
        val oldRows = rows.size
        val freshRows = mutableListOf<Map<DiagnosticMetric, Long>>()
        tracker.reset { _, metrics -> freshRows.add(metrics); true }
        tracker.flush()
        assertTrue(freshRows.isEmpty())
        now = 200
        tracker.end(DiagnosticResource.HEADING, owner)
        assertEquals(oldRows, rows.size)
        assertEquals(ResourceAction.END.code, freshRows[0][DiagnosticMetric.ACTION])
        assertEquals(rows.last()[DiagnosticMetric.TOKEN], freshRows[0][DiagnosticMetric.TOKEN])
        assertEquals(rows.last()[DiagnosticMetric.START_MS], freshRows[0][DiagnosticMetric.START_MS])
        tracker.flush()
        assertEquals(ResourceAction.SUMMARY.code, freshRows[1][DiagnosticMetric.ACTION])
        assertEquals(100L, freshRows[1][DiagnosticMetric.UNION_MS])
    }

    @Test fun resetFlushSkipsUntouchedHoldsEvenWhenElapsedTimeAdvances() {
        start()
        tracker.begin(DiagnosticResource.DEBUG_CPU, Any())
        val ended = Any()
        tracker.begin(DiagnosticResource.DEBUG_WIFI, ended)
        tracker.end(DiagnosticResource.DEBUG_WIFI, ended)
        tracker.reset()
        rows.clear()
        now = 500
        tracker.flush()
        tracker.stop()
        assertTrue(rows.isEmpty())
    }

    @Test fun realSnapshotCanObserveRetainedHoldAfterSilentReset() {
        start()
        val owner = Any()
        tracker.begin(DiagnosticResource.DEBUG_CPU, owner)
        tracker.reset()
        rows.clear()
        now = 500
        tracker.flush(observeActive = true)
        assertEquals(1, rows.size)
        assertEquals(1L, summary(DiagnosticResource.DEBUG_CPU)[DiagnosticMetric.ACTIVE])
        assertEquals(400L, summary(DiagnosticResource.DEBUG_CPU)[DiagnosticMetric.UNION_MS])
        tracker.end(DiagnosticResource.DEBUG_CPU, owner)
    }
}
