package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.data.DataRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoTrackPublicationGateTest {
    private val gate = DittoTrackPublicationGate()
    private val sample = DittoTrackPublicationGate.Sample(
        DataRepository.LocationSnapshot(1_800_000_000_000, 40.0, -88.0, 100.0,
            3f, 4f, 0f, 0f),
        80, listOf("WATCH", "Team Member", "Cyan", "uid"), "MOPP1",
        DataRepository.PhysioData(80, 90f, null, null, null, 0.2, null, null), 60)

    @Test fun firstFixAndHeartbeatPublishButRepeatedAndJitteringFixesDoNot() {
        assertTrue(gate.due(sample, 0))
        gate.published(sample, 0)
        assertFalse(gate.due(sample, 59_999))
        assertTrue(gate.due(sample, 60_000))
        val jitter = sample.copy(location = sample.location.copy(lat = 40.00001))
        assertFalse(gate.due(jitter, 1_000))
        assertTrue(gate.due(sample.copy(location = sample.location.copy(lat = 40.001)), 1_000))
    }

    @Test fun movementReusesAccuracyAwareTakDistanceThresholds() {
        gate.published(sample, 0)
        assertFalse(gate.due(sample.copy(location = sample.location.copy(lat = 40.0001)), 1_000))
        assertFalse(gate.due(sample.copy(location = sample.location.copy(lat = 40.001, ce = 100f)), 1_000))
        assertTrue(gate.due(sample.copy(location = sample.location.copy(lat = 40.001, ce = 5f)), 1_000))
    }

    @Test fun meaningfulVitalsAndRemarksChangeButSampleClocksAndSmallJitterDoNot() {
        val original = sample.copy(remarks = "MOPP1; HR: 80 bpm @ 12:00:00Z")
        gate.published(original, 0)
        assertFalse(gate.due(original.copy(remarks = "MOPP1; HR: 81 bpm @ 12:00:01Z",
            battery = 79, physio = sample.physio!!.copy(heartRateBpm = 81)), 1_000))
        assertTrue(gate.due(original.copy(physio = sample.physio!!.copy(heartRateBpm = 85)), 1_000))
        assertTrue(gate.due(original.copy(physio = sample.physio!!.copy(skinTempF = 90.5f)), 1_000))
        assertTrue(gate.due(original.copy(physio = sample.physio!!.copy(exertion = 0.3)), 1_000))
        assertTrue(gate.due(original.copy(physio = null), 1_000))
        assertTrue(gate.due(original.copy(remarks = "MOPP2"), 1_000))
        assertTrue(gate.due(original.copy(battery = 75), 1_000))
        assertTrue(gate.due(original.copy(identity = listOf("NEW")), 1_000))
    }

    @Test fun stationaryIntervalChangesPublishNewStaleBeforeDeferringAndResumeForcesRefresh() {
        gate.published(sample, 0)
        val stationary = sample.copy(intervalSeconds = StationaryLocationPolicy.intervalSeconds(15, true))
        assertFalse(gate.due(stationary, 15_000))
        assertTrue(gate.due(stationary, 60_000))
        assertTrue(gate.due(sample.copy(intervalSeconds = 120), 1_000))
        assertTrue(gate.due(sample.copy(intervalSeconds = 15), 1_000))
        gate.reset()
        assertTrue(gate.due(sample, 1_000))
    }

    @Test fun heartbeatFollowsSharedMr535SixtyToOneTwentyToThreeHundredSecondIdleLadder() {
        val before = CotDispatchService.actualReportingIntervalSecs
        val stationaryBefore = CotDispatchService.stationaryIntervalSeconds
        try {
            CotDispatchService.stationaryIntervalSeconds = 0
            CotDispatchService.actualReportingIntervalSecs = 15
            for (minimum in listOf(60, 120, 300)) {
                CotDispatchService.stationaryIntervalSeconds = minimum
                val idle = sample.copy(intervalSeconds = CotDispatchService.actualReportingIntervalSecs)
                assertEquals(minimum, idle.intervalSeconds)
                gate.published(idle, 0)
                assertFalse(gate.due(idle, minimum * 1_000L - 1))
                assertTrue(gate.due(idle, minimum * 1_000L))
                assertEquals(2 * minimum + 15,
                    StationaryLocationPolicy.staleSeconds(15, true, minimum))
            }
        } finally {
            CotDispatchService.stationaryIntervalSeconds = stationaryBefore
            CotDispatchService.actualReportingIntervalSecs = before
        }
    }
}
