package com.aitech.weartak_civ.data

import android.app.Application
import com.aitech.weartak_civ.cot.*
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GenericCotLifecycleTest {
    init {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
    }
    private val repo = DataRepository(RuntimeEnvironment.getApplication())
    private val bloodhounds = mockk<BloodhoundRepository>(relaxed = true)
    private val now = System.currentTimeMillis()
    private fun marker(uid: String, time: Long = now, stale: Long = now + 60_000, type: String = "a-n-A-C-F") =
        CotMarkerEvent("2.0", uid, type, "m-g", time, time, stale, null, CotPoint(41.0, -87.0, 0.0, 10.0, 10.0),
            CotMarkerDetail(null, null, null, null, null, null))

    @Test fun aircraftChurnIsBoundedWithoutEvictingExistingDropMarkers() = runBlocking {
        repo.applyReceivedMarkerEvent(marker("drop", type = "a-f-G"), "TAK", "server", now, now)
        repeat(850) {
            repo.applyReceivedMarkerEvent(marker("plane-$it"), "TAK", "server", now + it, now)
        }
        val points = repo.receivedMarkerList.value
        assertEquals(513, points.size)
        assertTrue(points.any { it.uid == "drop" })
        assertFalse(points.any { it.uid == "plane-0" })
        assertEquals("plane-849", points.last().title)
        repo.clearReceivedMarkers()
    }

    @Test fun staleAndUnrefreshedGenericCopiesLeaveMapAndNavigation() = runBlocking {
        repo.receiveMarkerEvent(marker("short", stale = now + 1_000), bloodhounds, "TAK", "server", now)
        repo.receiveMarkerEvent(marker("long", stale = now + 3_600_000), bloodhounds, "TAK", "server", now)
        repo.pruneGenericMarkers(now + 1_001)
        assertEquals(listOf("long"), repo.receivedMarkerList.value.map { it.uid })
        verify { bloodhounds.removeMarkerCopy("short", "server", now, now + 1_001) }
        repo.pruneGenericMarkers(now + 15 * 60_000)
        assertTrue(repo.receivedMarkerList.value.isEmpty())
        verify { bloodhounds.removeMarkerCopy("long", "server", now, now + 15 * 60_000) }
    }

    @Test fun deleteIsSourceScopedVersionedAndDoesNotResurrectFromDelayedPackets() = runBlocking {
        val event = marker("shared")
        repo.receiveMarkerEvent(event, bloodhounds, "TAK", "a", now)
        repo.receiveMarkerEvent(event.copy(time = now + 1), bloodhounds, "TAK", "b", now)
        repo.receiveCotDeletion(CotDeletion("shared", now - 1), "b", bloodhounds, now)
        assertEquals("b", repo.receivedMarkerList.value.single().sourceServerId)
        repo.receiveCotDeletion(CotDeletion("shared", now + 2), "b", bloodhounds, now)
        assertEquals("a", repo.receivedMarkerList.value.single().sourceServerId)
        assertFalse(repo.receiveMarkerEvent(event, bloodhounds, "TAK", "b", now))
        assertTrue(repo.receiveMarkerEvent(event.copy(time = now + 3), bloodhounds, "TAK", "b", now))
        repo.clearReceivedMarkers()
    }

    @Test fun deletionBeforeCreationAlsoRejectsOldUsersAndMarkers() = runBlocking {
        repo.receiveCotDeletion(CotDeletion("missing", now + 2), "server", bloodhounds, now)
        assertFalse(repo.receiveMarkerEvent(marker("missing"), bloodhounds, "TAK", "server", now))
        val event = marker("missing")
        repo.applyReceivedUserEvent(CotUserPositionEvent(event.version, event.uid, "Blue", "a-f-G-U-C",
            "Team Member", event.how, event.time, event.start, event.stale, null, event.point, event.detail),
            "TAK", "server", now)
        assertTrue(repo.receivedUserList.value.isEmpty())
        assertTrue(repo.receivedMarkerList.value.isEmpty())
    }

    @Test fun deletionRejectsDelayedRoutesButPreservesOtherSourcesAndNewerRoutes() = runBlocking {
        val route = com.aitech.weartak_civ.data.model.TakRoute("route", "Route", emptyList(), "a", eventTime = now)
        repo.upsertTakRoute(route)
        repo.upsertTakRoute(route.copy(serverId = "b"))
        repo.receiveCotDeletion(CotDeletion("route", now), "a", bloodhounds, now)
        repo.upsertTakRoute(route)
        assertEquals(listOf("b"), repo.takRouteCopies.map { it.serverId })
        repo.upsertTakRoute(route.copy(eventTime = now + 1))
        assertEquals(2, repo.takRouteCopies.size)
    }

    @Test fun idleGenericExpiryRunsWithoutAnotherIncomingPacket() = runBlocking {
        repo.receiveMarkerEvent(marker("short", stale = now + 500), bloodhounds, "TAK", "server", now)
        val deadline = System.currentTimeMillis() + 4_000
        while (repo.receivedMarkerList.value.isNotEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
        }
        assertTrue(repo.receivedMarkerList.value.isEmpty())
        verify { bloodhounds.removeMarkerCopy("short", "server", now, any()) }
    }
}
