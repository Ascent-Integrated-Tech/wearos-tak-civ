package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import kotlin.math.*

/** Immutable navigation over the original, ordered vertices (including unnamed points). */
class RouteFollow private constructor(
    val points: List<TakRoutePoint>,
    val mode: StartMode,
    val currentIndex: Int,
    val completed: Boolean,
    private val remainingSegments: DoubleArray
) {
    enum class StartMode { BEGINNING, NEAREST }

    val pointNumber: Int get() = currentIndex + 1
    val target: TakRoutePoint get() = points[currentIndex]

    fun advance(location: TakRoutePoint): RouteFollow {
        var index = currentIndex
        if (completed) return this
        while (distanceMeters(location, points[index]) <= ARRIVAL_RADIUS_METERS) {
            if (index == points.lastIndex) {
                return RouteFollow(points, mode, index, true, remainingSegments)
            }
            index++
        }
        return if (index == currentIndex) this
            else RouteFollow(points, mode, index, false, remainingSegments)
    }

    fun remainingDistanceMeters(location: TakRoutePoint): Double =
        if (completed) 0.0 else distanceMeters(location, target) + remainingSegments[currentIndex]

    companion object {
        const val ARRIVAL_RADIUS_METERS = 20.0

        fun start(points: List<TakRoutePoint>, mode: StartMode, location: TakRoutePoint? = null): RouteFollow {
            require(points.isNotEmpty()) { "Cannot follow an empty route" }
            require(points.all(::validLocation)) { "Invalid route coordinates" }
            require(location == null || validLocation(location)) { "Invalid user location" }
            require(mode != StartMode.NEAREST || location != null) { "Nearest start requires a location fix" }
            val snapshot = points.toList()
            val index = if (mode == StartMode.NEAREST) nearestIndex(snapshot, requireNotNull(location)) else 0
            val remaining = DoubleArray(snapshot.size)
            for (i in snapshot.lastIndex - 1 downTo 0) {
                remaining[i] = remaining[i + 1] + distanceMeters(snapshot[i], snapshot[i + 1])
            }
            return RouteFollow(snapshot, mode, index, false, remaining)
        }

        fun nearestIndex(points: List<TakRoutePoint>, location: TakRoutePoint): Int {
            require(points.isNotEmpty()) { "Cannot find a vertex in an empty route" }
            return points.indices.minBy { distanceMeters(location, points[it]) }
        }

        fun validLocation(point: TakRoutePoint): Boolean =
            point.latitude.isFinite() && point.longitude.isFinite() &&
                point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0

        fun distanceMeters(a: TakRoutePoint, b: TakRoutePoint): Double {
            val lat = Math.toRadians(b.latitude - a.latitude)
            val lon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
                cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
            return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}

data class RouteFollowSession(
    val route: TakRoute,
    val navigation: RouteFollow,
    val remainingMeters: Double?
) {
    val uid: String get() = "route-follow-${route.serverId.length}-${route.serverId}-${route.uid}"
    fun matches(route: TakRoute): Boolean = this.route.serverId == route.serverId && this.route.uid == route.uid
}
