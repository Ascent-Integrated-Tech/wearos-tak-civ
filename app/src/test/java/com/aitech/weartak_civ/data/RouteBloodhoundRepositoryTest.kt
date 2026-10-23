package com.aitech.weartak_civ.data

import android.content.Context
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouteBloodhoundRepositoryTest {
    class RefreshObserver {
        val reasons = mutableListOf<com.aitech.weartak_civ.events.PliRefreshReason>()
        @org.greenrobot.eventbus.Subscribe
        fun receive(event: com.aitech.weartak_civ.events.InteractionPliRefresh) { reasons += event.reason }
    }
    private val repo = BloodhoundRepository(mockk<Context>(relaxed = true))
    private val route = TakRoute("route", "Test route", listOf(
        TakRoutePoint(0.0, 0.0), TakRoutePoint(0.0, 0.001), TakRoutePoint(0.0, 0.002)), "server")

    @Test fun routeAndOrdinaryTrackingRequestActiveLocationWithoutWaitingForIt() {
        val observer = RefreshObserver()
        org.greenrobot.eventbus.EventBus.getDefault().register(observer)
        try {
            assertTrue(repo.startRoute(route, RouteFollow.StartMode.BEGINNING, null))
            assertNotNull(repo.activeUid.value)
            assertTrue(observer.reasons.contains(com.aitech.weartak_civ.events.PliRefreshReason.NAVIGATION_STARTED))
            observer.reasons.clear()
            repo.addMarkerTarget("other", "Other", "b-m-p-s-m", 1.0, 1.0, 1L)
            repo.activate("other")
            assertEquals("other", repo.activeUid.value)
            assertEquals(listOf(com.aitech.weartak_civ.events.PliRefreshReason.NAVIGATION_STARTED), observer.reasons)
        } finally {
            org.greenrobot.eventbus.EventBus.getDefault().unregister(observer)
        }
    }

    @Test fun advancesSameTargetWithIncreasingTimeAndPreservesActivation() {
        assertTrue(repo.startRoute(route, RouteFollow.StartMode.BEGINNING, null))
        val initial = repo.activeTrackingItem()!!
        repo.updateRouteLocation(route.points[0])
        val updated = repo.activeTrackingItem()!!
        assertEquals(initial.uid, updated.uid)
        assertEquals(0.001, updated.target.point.lon, 0.0)
        assertTrue(updated.bhEvent.mapItem.cotEvent.time > initial.bhEvent.mapItem.cotEvent.time)
        assertTrue(updated.state.active)
        assertTrue(updated.bhEvent.isRouteFollow)
        assertNull(updated.origin.issuerUid)
        assertEquals(2, repo.routeFollow.value!!.navigation.pointNumber)
        assertNull(repo.setInPos(initial.uid))
        assertEquals(initial.uid, repo.activeUid.value)
    }

    @Test fun anotherTargetStopsRouteAndDoesNotRecreateItOnLocationUpdates() {
        repo.startRoute(route, RouteFollow.StartMode.BEGINNING, null)
        val uid = repo.activeUid.value!!
        repo.addMarkerTarget("other", "Other", "b-m-p-s-m", 1.0, 1.0, 1L)
        repo.activate("other")
        repo.updateRouteLocation(route.points[0])
        assertNull(repo.routeFollow.value)
        assertFalse(repo.isUidValid(uid))
        assertEquals("other", repo.activeUid.value)
    }

    @Test fun stopClearsActiveTarget() {
        repo.startRoute(route, RouteFollow.StartMode.BEGINNING, null)
        repo.stopRoute()
        assertNull(repo.activeUid.value)
        assertNull(repo.routeFollow.value)
        assertTrue(repo.bloodhoundMap.value.isEmpty())
    }

    @Test fun removingRouteStopsOnlyMatchingSource() {
        repo.startRoute(route, RouteFollow.StartMode.BEGINNING, null)
        repo.reconcileRoutes(listOf(route, route.copy(serverId = "other")))
        assertNotNull(repo.activeUid.value)
        repo.reconcileRoutes(listOf(route.copy(serverId = "other")))
        assertNull(repo.activeUid.value)
        assertNull(repo.routeFollow.value)
    }

    @Test fun geometryChangesStopFollowing() {
        repo.startRoute(route, RouteFollow.StartMode.BEGINNING, null)
        repo.reconcileRoutes(listOf(route.copy(points = route.points.reversed())))
        assertNull(repo.activeUid.value)
    }

    @Test fun completionClearsTargetAndRetainsCompletionForDetails() {
        repo.startRoute(route, RouteFollow.StartMode.NEAREST, route.points.last())
        repo.updateRouteLocation(route.points.last())
        assertTrue(repo.routeFollow.value!!.navigation.completed)
        assertNull(repo.activeUid.value)
        assertTrue(repo.bloodhoundMap.value.isEmpty())
        assertEquals(0.0, repo.routeFollow.value!!.remainingMeters!!, 0.0)
    }

    @Test fun invalidStartDoesNotStopExistingTarget() {
        repo.addMarkerTarget("other", "Other", "b-m-p-s-m", 1.0, 1.0, 1L)
        repo.activate("other")
        assertFalse(repo.startRoute(route, RouteFollow.StartMode.NEAREST, null))
        assertFalse(repo.startRoute(route.copy(points = emptyList()), RouteFollow.StartMode.BEGINNING, null))
        assertEquals("other", repo.activeUid.value)
    }
}
