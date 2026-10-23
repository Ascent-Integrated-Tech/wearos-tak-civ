package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import com.aitech.weartak_civ.presentation.viewmodel.findRoutePoint
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class RoutePointSelectionTest {
    private val first = TakRoute(
        uid = "shared-route",
        name = "First route",
        points = listOf(
            TakRoutePoint(34.0, -117.0, name = "Start"),
            TakRoutePoint(35.0, -118.0, name = "Finish")
        ),
        serverId = "server-1",
        missionName = "First mission",
        sender = "sender-1"
    )
    private val second = first.copy(
        serverId = "server-2",
        points = listOf(
            TakRoutePoint(40.0, -120.0, name = "Other start"),
            TakRoutePoint(41.0, -121.0, name = "Other finish")
        ),
        missionName = "Second mission",
        sender = "sender-2"
    )

    private fun TakRoute.mapRoute() = RouteCollection(
        serverId = serverId,
        uid = uid,
        name = name,
        color = "White",
        points = points.map { RouteItem(it.latitude, it.longitude, it.name != null, it.name) }
    )

    @Test
    fun featureSelectionPreservesServerPointAndBloodhoundCoordinates() {
        val routes = listOf(first, second)
        routes.forEach { route ->
            val mapRoute = route.mapRoute()
            assertEquals(route.serverId, mapRoute.toFeature()?.properties?.get("serverId")?.jsonPrimitive?.content)
            val features = mapRoute.toPointFeatures()
            assertEquals(2, features.size)
            features.forEachIndexed { index, feature ->
                val properties = requireNotNull(feature.properties)
                val selected = requireNotNull(findRoutePoint(
                    routes,
                    properties.getValue("serverId").jsonPrimitive.content,
                    properties.getValue("routeUid").jsonPrimitive.content,
                    properties.getValue("pointIndex").jsonPrimitive.content.toInt()
                ))
                assertEquals(route.serverId, selected.serverId)
                assertEquals(route.uid, selected.routeUid)
                assertEquals(index, selected.pointIndex)
                assertEquals(route.points[index].name, selected.title)
                assertEquals(route.points[index].latitude, selected.latitude, 0.0)
                assertEquals(route.points[index].longitude, selected.longitude, 0.0)
                assertEquals(route.sender, selected.droppedBy)
                assertEquals(route.missionName, selected.missionName)
            }
        }
    }

    @Test
    fun unknownServerRouteOrPointNeverFallsBackToAnotherRoute() {
        val routes = listOf(first, second)
        assertNull(findRoutePoint(routes, "missing-server", first.uid, 0))
        assertNull(findRoutePoint(routes, first.serverId, "missing-route", 0))
        assertNull(findRoutePoint(routes, first.serverId, first.uid, -1))
        assertNull(findRoutePoint(routes, first.serverId, first.uid, 2))
        assertNull(findRoutePoint(listOf(first), second.serverId, second.uid, 0))
    }

    @Test
    fun unnamedVerticesStillDrawTheLineWithoutChangingNamedPointIndices() {
        val route = first.copy(points = listOf(
            first.points[0], TakRoutePoint(34.5, -117.5), first.points[1]
        )).mapRoute()
        assertEquals(1, listOf(route).toFeatureCollection().features.size)
        assertEquals(listOf("0", "2"), route.toPointFeatures().map {
            it.properties?.get("pointIndex")?.jsonPrimitive?.content
        })
    }

    @Test
    fun emptyNamedTrackVerticesPreserveLineButDoNotCreateThousandsOfMarkers() {
        val route = first.copy(points = List(2469) { index ->
            TakRoutePoint(34.0 + index / 100000.0, -117.0,
                name = when (index) { 0 -> "Start"; 2468 -> "Finish"; else -> "" })
        }).mapRoute()
        assertEquals(2469, route.toLineString()?.coordinates?.size)
        assertEquals(listOf("0", "2468"), route.toPointFeatures().map {
            it.properties?.get("pointIndex")?.jsonPrimitive?.content
        })
        assertNotNull(findRoutePoint(listOf(first.copy(points = List(2469) {
            TakRoutePoint(34.0, -117.0, name = "")
        })), first.serverId, first.uid, 1234))
    }
}
