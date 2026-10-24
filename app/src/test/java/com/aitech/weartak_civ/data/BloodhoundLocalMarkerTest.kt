package com.aitech.weartak_civ.data

import android.content.Context
import com.aitech.weartak_civ.provider.LocationVectorCalculator
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BloodhoundLocalMarkerTest {
    private fun marker(uid: String = "pin", lat: Double = 38.001) = DataRepository.DroppedMarker(
        DataRepository.MarkerCotType.EDIT, uid, DataRepository.MarkerType.FRIENDLY, "My pin", "",
        100, DataRepository.LocationSnapshot(200, lat, -77.0, 0.0, null, null, null, null),
        true, false)

    private fun seed(repository: BloodhoundRepository, local: Boolean = true) {
        repository.addMarkerTarget("pin", "My pin", "a-f-G", 38.001, -77.0, 100,
            tracksLocalMarker = local)
        repository.activate("pin")
    }

    @Test fun movingTrackedPinRefreshesPointPreservesStateAndChangesGuidanceVector() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        seed(repository)
        val before = repository.activeTrackingItem()!!
        val beforeVector = LocationVectorCalculator.calculate(38.0, -77.0,
            before.target.point.lat, before.target.point.lon)
        assertTrue(repository.refreshLocalMarkerTargets(listOf(marker(lat = 38.00001))))
        val moved = repository.activeTrackingItem()!!
        assertEquals(38.00001, moved.target.point.lat, 0.0)
        assertEquals(moved.target.point, moved.bhEvent.mapItem.point)
        assertEquals(before.state, moved.state)
        assertEquals(before.origin, moved.origin)
        assertEquals(before.bhEvent.timeIssued, moved.bhEvent.timeIssued)
        assertEquals(before.bhEvent.sourceServerId, moved.bhEvent.sourceServerId)
        assertEquals("pin", repository.activeUid.value)
        assertEquals(1, repository.bloodhoundMap.value.size)
        val vector = LocationVectorCalculator.calculate(38.0, -77.0,
            moved.target.point.lat, moved.target.point.lon)
        repository.updateBloodhoundItemVector("pin", vector)
        assertTrue(vector.distance!! < beforeVector.distance!! / 10)
        assertEquals(vector, repository.activeTrackingItem()!!.vector)
        assertFalse(repository.refreshLocalMarkerTargets(listOf(marker(lat = 38.00001))))
    }

    @Test fun unrelatedMovesReceivedCopiesAndStoppedOrDeletedTargetsAreNotActivated() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        seed(repository)
        assertFalse(repository.refreshLocalMarkerTargets(listOf(marker(uid = "other", lat = 39.0))))
        assertEquals(38.001, repository.activeTrackingItem()!!.target.point.lat, 0.0)
        assertEquals(setOf("pin"), repository.bloodhoundMap.value.keys)
        repository.stopTracking("pin")
        assertTrue(repository.refreshLocalMarkerTargets(listOf(marker(lat = 39.0))))
        assertNull(repository.activeUid.value)
        repository.removeTarget("pin")
        assertFalse(repository.refreshLocalMarkerTargets(listOf(marker(lat = 40.0))))
        assertTrue(repository.bloodhoundMap.value.isEmpty())
        seed(repository, local = false)
        assertFalse(repository.refreshLocalMarkerTargets(listOf(marker(lat = 40.0))))
        assertEquals(38.001, repository.activeTrackingItem()!!.target.point.lat, 0.0)
    }

    @Test fun clearAndInvalidOrMissingCoordinatesNeverMoveTarget() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        seed(repository)
        assertFalse(repository.refreshLocalMarkerTargets(listOf(marker().copy(
            cotType = DataRepository.MarkerCotType.CLEAR))))
        assertFalse(repository.refreshLocalMarkerTargets(listOf(marker().copy(location = null))))
        assertFalse(repository.refreshLocalMarkerTargets(listOf(marker(lat = Double.NaN))))
        assertFalse(repository.refreshLocalMarkerTargets(emptyList()))
        assertEquals(38.001, repository.activeTrackingItem()!!.target.point.lat, 0.0)
    }
}
