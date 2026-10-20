package com.aitech.weartak_civ.data

import android.app.Application
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RouteRemovalTest {
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
