package com.aitech.weartak_civ.data

import android.app.Application
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RouteRemovalTest {
    @Test fun pickerRemovalRemovesAllVersionedCopiesAndStopsMatchingNavigation() {
        val app = RuntimeEnvironment.getApplication()
        val repo = DataRepository(app)
        val bh = BloodhoundRepository(app)
        val route = TakRoute("route", "Route", listOf(TakRoutePoint(0.0, 0.0)), "server", eventTime = 200)
        val other = route.copy(uid = "other")
        listOf(route, route.copy(serverId = "fallback"), other).forEach(repo::upsertTakRoute)
        bh.startRoute(route, RouteFollow.StartMode.BEGINNING, null)
        repo.removeRouteFromWatch(route, bh)
        assertEquals(listOf(other), repo.takRouteCopies)
        assertNull(bh.activeUid.value)
        assertNull(bh.routeFollow.value)
    }

    @Test fun removeAllClearsRoutesAndCompletedSessionAsWellAsTargets() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val repo = DataRepository(app)
        val bh = BloodhoundRepository(app)
        val route = TakRoute("route", "Route", listOf(TakRoutePoint(0.0, 0.0)), "server")
        repo.upsertTakRoute(route)
        bh.startRoute(route, RouteFollow.StartMode.BEGINNING, null)
        bh.updateRouteLocation(route.points.single())
        bh.removeAllFromMenu()
        repo.clearRoutesFromWatch(bh)
        assertTrue(repo.takRouteCopies.isEmpty())
        assertNull(bh.routeFollow.value)
        assertNull(bh.activeUid.value)
    }

    @Test fun versionedCopiesRemainOwnedAndOlderOrUnversionedUpdatesCannotReplaceCurrentRevision() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val latest = TakRoute("shared", "New", listOf(TakRoutePoint(34.0, -117.0)), "server-1", eventTime = 200)
        repo.upsertTakRoute(latest)
        repo.upsertTakRoute(latest.copy(serverId = "server-2"))
        repo.upsertTakRoute(latest.copy(name = "Old", eventTime = 100))
        repo.upsertTakRoute(latest.copy(name = "Unversioned", eventTime = null))
        assertEquals(2, repo.takRouteCopies.size)
        assertEquals(setOf("New"), repo.takRouteCopies.map { it.name }.toSet())
        assertEquals(listOf(latest), withTimeout(5000) { repo.takRoutes.first { it.size == 1 } })
        repo.removeTakRoute("server-1", "shared")
        assertEquals(listOf(latest.copy(serverId = "server-2")), withTimeout(5000) {
            repo.takRoutes.first { it.singleOrNull()?.serverId == "server-2" }
        })
    }

    @Test fun removesOnlySelectedServerAndRouteAndAllowsExplicitResend() = runBlocking {
        MainActivity.device_uid = "watch"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val route = TakRoute("shared-route", "Test", listOf(TakRoutePoint(34.0, -117.0)), "server-1")
        val otherServer = route.copy(serverId = "server-2")
        val otherRoute = route.copy(uid = "other-route")
        listOf(route, otherServer, otherRoute).forEach(repo::upsertTakRoute)
        withTimeout(5000) { repo.takRoutes.first { it.size == 3 } }
        repo.removeTakRoute(route.serverId, route.uid)
        val remaining = withTimeout(5000) { repo.takRoutes.first { it.size == 2 } }
        assertEquals(setOf(otherServer, otherRoute), remaining.toSet())
        repo.removeTakRoute("missing-server", route.uid)
        repo.removeTakRoute(route.serverId, "missing-route")
        repo.upsertTakRoute(route)
        assertEquals(setOf(route, otherServer, otherRoute),
            withTimeout(5000) { repo.takRoutes.first { it.size == 3 } }.toSet())
    }
}
