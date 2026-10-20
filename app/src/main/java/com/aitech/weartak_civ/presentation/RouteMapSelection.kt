package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.data.model.TakRoute
import kotlin.math.abs

internal data class RouteScreenPoint(val x: Double, val y: Double)

internal data class RouteMapSelection(
    val serverId: String,
    val routeUid: String,
    val pointIndex: Int? = null
)

internal fun findRouteMapSelection(
    routes: List<TakRoute>,
    tap: RouteScreenPoint,
    pointsVisible: Boolean,
    project: (Double, Double) -> RouteScreenPoint
): RouteMapSelection? {
    val radiusSquared = 12.0 * 12.0
    var nearestPoint: RouteMapSelection? = null
    var pointDistance = radiusSquared
    var nearestLine: RouteMapSelection? = null
    var lineDistance = radiusSquared
    routes.forEach { route ->
        var previous: RouteScreenPoint? = null
        route.points.forEachIndexed { index, point ->
            val screen = project(point.latitude, point.longitude)
            if (!screen.x.isFinite() || !screen.y.isFinite()) {
                previous = null
                return@forEachIndexed
            }
            val dx = screen.x - tap.x
            val dy = screen.y - tap.y
            val distance = dx * dx + dy * dy
            if (pointsVisible && distance <= pointDistance) {
                pointDistance = distance
                nearestPoint = RouteMapSelection(route.serverId, route.uid, index)
            }
            val start = previous
            if (start != null && abs(point.longitude - route.points[index - 1].longitude) <= 180.0) {
                val sx = screen.x - start.x
                val sy = screen.y - start.y
                val lengthSquared = sx * sx + sy * sy
                val t = if (lengthSquared == 0.0) 0.0 else
                    (((tap.x - start.x) * sx + (tap.y - start.y) * sy) / lengthSquared).coerceIn(0.0, 1.0)
                val lineDx = start.x + t * sx - tap.x
                val lineDy = start.y + t * sy - tap.y
                val segmentDistance = lineDx * lineDx + lineDy * lineDy
                if (segmentDistance <= lineDistance) {
                    lineDistance = segmentDistance
                    nearestLine = RouteMapSelection(route.serverId, route.uid)
                }
            }
            previous = screen
        }
    }
    return nearestPoint ?: nearestLine
}
