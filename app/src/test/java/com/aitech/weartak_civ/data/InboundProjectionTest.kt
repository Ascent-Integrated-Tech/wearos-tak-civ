package com.aitech.weartak_civ.data

import android.app.Application
import com.aitech.weartak_civ.cot.*
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class InboundProjectionTest {
    private val now = System.currentTimeMillis()
    private fun user(time: Long = now, lat: Double = 1.0) = CotUserPositionEvent(
        "2.0", "remote", "Blue", "a-f-G-U-C", "Team Member", "m-g",
        time, time, now + 60_000, null, CotPoint(lat, 2.0, 0.0, 1.0, 1.0),
        CotMarkerDetail("Remote", null, null, null, null, null))
    private fun marker(time: Long = now, lat: Double = 1.0) = user(time, lat).let {
        CotMarkerEvent(it.version, it.uid, "a-n-A-C-F", it.how, it.time, it.start,
            it.stale, null, it.point, it.detail)
    }

    @Test fun pliUsesLatestWindowButUrgentAndDeletesRemainImmediate() = runTest {
        MainActivity.device_uid = "watch"
        MainActivity.versionNumber = "test"
        val repo = DataRepository(RuntimeEnvironment.getApplication(), projectionScope = backgroundScope)
        val navigation = BloodhoundRepository(RuntimeEnvironment.getApplication(), projectionScope = backgroundScope)
        repo.applyReceivedUserEvent(user(), "TCP", "tcp", now)
        repo.applyReceivedUserEvent(user(now + 1, 3.0), "TCP", "tcp", now + 1)
        runCurrent()
        assertEquals(1.0, repo.receivedUserList.value.single().location!!.lat, 0.0)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(3.0, repo.receivedUserList.value.single().location!!.lat, 0.0)

        repo.applyReceivedUserEvent(user(now + 2, 4.0), "TCP", "tcp", now + 2)
        val urgent = user(now + 3, 5.0).let { it.copy(detail = it.detail.copy(hasUrgentDetail = true)) }
        repo.applyReceivedUserEvent(urgent, "TCP", "tcp", now + 3)
        assertEquals(5.0, repo.receivedUserList.value.single().location!!.lat, 0.0)
        repo.applyReceivedUserEvent(user(now + 4, 6.0), "TCP", "tcp", now + 4)
        repo.receiveCotDeletion(CotDeletion("remote", now + 4), "tcp", navigation, now + 5)
        assertTrue(repo.receivedUserList.value.isEmpty())
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(repo.receivedUserList.value.isEmpty())
        repo.clearReceivedUsers()
    }

    @Test fun navigationSignalsAndSnapshotsAreBatchedWithoutResurrectingDeletedTargets() = runTest {
        MainActivity.device_uid = "watch"
        MainActivity.versionNumber = "test"
        val repo = DataRepository(RuntimeEnvironment.getApplication(), projectionScope = backgroundScope)
        val navigation = BloodhoundRepository(RuntimeEnvironment.getApplication(), projectionScope = backgroundScope)
        repo.receiveMarkerEvent(marker(), navigation, "TCP", "tcp", now)
        val firstSignal = navigation.bloodhoundEvent.value
        assertTrue(firstSignal!!.isNewUid)
        repeat(10) {
            repo.receiveMarkerEvent(marker(now + 1 + it, 3.0 + it), navigation, "TCP", "tcp", now + 1 + it)
        }
        runCurrent()
        assertSame(firstSignal, navigation.bloodhoundEvent.value)
        assertEquals(1.0, navigation.bloodhoundMap.value["remote"]!!.target.point.lat, 0.0)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(12.0, navigation.bloodhoundMap.value["remote"]!!.target.point.lat, 0.0)
        assertEquals(12.0, navigation.bloodhoundEvent.value!!.event.mapItem.point.lat, 0.0)
        assertFalse(navigation.bloodhoundEvent.value!!.isNewUid)
        // Generic track receipt time controls the independent 15-minute retention cap.
        repo.receiveMarkerEvent(marker(now + 10, 12.0), navigation, "TCP", "tcp", now + 15)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(now + 15, repo.receivedMarkerList.value.single().receivedAt)
        repo.receiveMarkerEvent(marker(now + 20, 20.0), navigation, "TCP", "tcp", now + 20)
        repo.receiveCotDeletion(CotDeletion("remote", now + 20), "tcp", navigation, now + 21)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(navigation.bloodhoundMap.value.isEmpty())
        assertNull(navigation.bloodhoundEvent.value)
        assertTrue(repo.receivedMarkerList.value.isEmpty())
        repo.clearReceivedMarkers()
    }

    @Test fun localActivationUsesLatestAuthoritativeSourceWithoutWaitingForProjection() = runTest {
        MainActivity.device_uid = "watch"
        MainActivity.versionNumber = "test"
        val navigation = BloodhoundRepository(RuntimeEnvironment.getApplication(), projectionScope = backgroundScope)
        navigation.applyReceivedMarkerEvent(marker(), "TCP", "tcp", now)
        navigation.applyReceivedMarkerEvent(marker(now + 1, 2.0), "BLE", "ble", now + 1)
        navigation.applyReceivedMarkerEvent(marker(now + 2, 3.0), "TCP", "tcp", now + 2)
        assertEquals("ble", navigation.bloodhoundMap.value["remote"]!!.sourceServerId)
        val (_, active) = navigation.activate("remote")
        assertEquals("tcp", active!!.sourceServerId)
        assertEquals(3.0, active.target.point.lat, 0.0)
        assertEquals("tcp", navigation.activeTrackingItem()!!.sourceServerId)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("tcp", navigation.activeTrackingItem()!!.sourceServerId)
    }
}
