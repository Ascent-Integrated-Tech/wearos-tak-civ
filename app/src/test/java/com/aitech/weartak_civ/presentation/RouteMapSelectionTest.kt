package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import org.junit.Assert.*
import org.junit.Test

class RouteMapSelectionTest {
    private val route = TakRoute(
        uid = "route", name = "Route", serverId = "server",
        points = listOf(TakRoutePoint(0.0, 0.0), TakRoutePoint(0.0, 100.0))
    )
    private fun hit(x: Double, y: Double, visible: Boolean = true, routes: List<TakRoute> = listOf(route)) =
        findRouteMapSelection(routes, RouteScreenPoint(x, y), visible) { lat, lon ->
            RouteScreenPoint(lon, lat)
        }

    @Test
    fun nearMissWithinTwelveDpSelectsVertexNotOnlyExactFiveDpCircle() {
        assertEquals(RouteMapSelection("server", "route", 0), hit(0.0, 12.0))
        assertNull(hit(0.0, 12.01))
    }

    @Test
    fun lineBetweenVerticesOpensWholeRouteWithTouchTolerance() {
        assertEquals(RouteMapSelection("server", "route"), hit(50.0, 12.0))
        assertNull(hit(50.0, 12.01))
    }

    @Test
    fun hiddenPointsDoNotInterceptWholeRouteAtLowZoom() {
        assertEquals(RouteMapSelection("server", "route"), hit(0.0, 0.0, visible = false))
    }

    @Test
    fun nearestVertexRetainsExactSourceAndOriginalIndex() {
        val other = route.copy(serverId = "other", points = listOf(
            TakRoutePoint(10.0, 0.0), TakRoutePoint(10.0, 100.0)
        ))
        assertEquals(RouteMapSelection("other", "route", 1), hit(100.0, 9.0, routes = listOf(route, other)))
    }

    @Test
    fun denseUnnamedGeometryStillHasExactPointAndWholeRouteIdentity() {
        val dense = route.copy(points = List(2469) { TakRoutePoint(0.0, it / 100.0, name = "") })
        assertEquals(RouteMapSelection("server", "route", 1234), hit(12.34, 0.0, routes = listOf(dense)))
        assertEquals(RouteMapSelection("server", "route"), hit(12.34, 0.0, false, listOf(dense)))
        assertEquals(2469, dense.points.size)
    }

    @Test
    fun dateLineDiscontinuityAndOffscreenGeometryNeverCreatePhantomHits() {
        val wrapped = route.copy(points = listOf(TakRoutePoint(0.0, 179.0), TakRoutePoint(0.0, -179.0)))
        assertNull(hit(0.0, 0.0, routes = listOf(wrapped)))
        assertNull(hit(500.0, 500.0))
    }

    @Test
    fun invalidProjectionBreaksSegmentsInsteadOfConnectingAcrossIt() {
        assertNull(findRouteMapSelection(listOf(route), RouteScreenPoint(0.0, 0.0), true) { _, _ ->
            RouteScreenPoint(Double.NaN, 0.0)
        })
    }
}
