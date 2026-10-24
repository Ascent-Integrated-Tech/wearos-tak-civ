package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import kotlin.math.*

/** Checkpoint navigation over original geometry; control vertices still contribute route distance. */
class RouteFollow private constructor(
    val points: List<TakRoutePoint>,
    val mode: StartMode,
    val currentIndex: Int,
    val completed: Boolean,
    private val remainingSegments: DoubleArray,
    val arrivalRadiusMeters: Double,
    private val arrived: Set<Int> = emptySet()
) {
    enum class StartMode { BEGINNING, NEAREST }

    val pointNumber: Int get() = currentIndex + 1
    val target: TakRoutePoint get() = points[currentIndex]

    fun advance(location: TakRoutePoint, accuracyMeters: Double = 0.0): RouteFollow {
        if (completed) return this
        require(validLocation(location)) { "Invalid user location" }
        val radius = arrivalRadiusMeters + (accuracyMeters.takeIf { it.isFinite() && it >= 0 } ?: 0.0)
        val reached = arrived + points.indices.filter { points[it].isCheckpoint &&
            distanceMeters(location, points[it]) <= radius }
        if (distanceMeters(location, points.last()) <= radius) {
            return RouteFollow(points, mode, points.lastIndex, true, remainingSegments, arrivalRadiusMeters, reached)
        }
        var index = currentIndex
        val along = projectedDistanceMeters(points, location)
        while (index < points.lastIndex && index in reached && along >= distanceAlong(index)) {
            index = points.indices.first { it > index && (points[it].isCheckpoint || it == points.lastIndex) }
        }
        return if (index == currentIndex && reached == arrived) this
            else RouteFollow(points, mode, index, false, remainingSegments, arrivalRadiusMeters, reached)
    }

    private fun distanceAlong(index: Int) = remainingSegments[0] - remainingSegments[index]
    fun remainingDistanceMeters(location: TakRoutePoint): Double =
        if (completed) 0.0 else distanceMeters(location, target) + remainingSegments[currentIndex]

    fun atIndex(index: Int, completed: Boolean = false): RouteFollow {
        require(index in points.indices) { "Route target index out of range" }
        return RouteFollow(points, mode, index, completed, remainingSegments, arrivalRadiusMeters, arrived)
    }

    companion object {
        fun arrivalRadiusMeters(method: String): Double = when (method.lowercase()) {
            "swimming", "walking" -> 10.0
            "driving" -> 20.0
            "watercraft" -> 100.0
            "flying" -> 1000.0
            else -> 10.0
        }

        fun start(points: List<TakRoutePoint>, mode: StartMode, location: TakRoutePoint? = null,
                  method: String = "Walking"): RouteFollow {
            require(points.isNotEmpty()) { "Cannot follow an empty route" }
            require(points.all(::validLocation)) { "Invalid route coordinates" }
            require(location == null || validLocation(location)) { "Invalid user location" }
            require(mode != StartMode.NEAREST || location != null) { "Nearest start requires a location fix" }
            val snapshot = points.toList()
            val remaining = DoubleArray(snapshot.size)
            for (i in snapshot.lastIndex - 1 downTo 0) {
                remaining[i] = remaining[i + 1] + distanceMeters(snapshot[i], snapshot[i + 1])
            }
            val checkpoints = snapshot.indices.filter { snapshot[it].isCheckpoint || it == snapshot.lastIndex }
            val along = if (mode == StartMode.NEAREST) projectedDistanceMeters(snapshot, requireNotNull(location)) else 0.0
            val index = checkpoints.firstOrNull { remaining[0] - remaining[it] >= along } ?: snapshot.lastIndex
            return RouteFollow(snapshot, mode, index, false, remaining, arrivalRadiusMeters(method))
        }

        /** Closest segment in a local equirectangular plane; returns cumulative geodesic metres. */
        fun projectedDistanceMeters(points: List<TakRoutePoint>, location: TakRoutePoint): Double {
            var bestDistance = Double.POSITIVE_INFINITY
            var bestAlong = 0.0
            var total = 0.0
            for (i in 0 until points.lastIndex) {
                val a = points[i]
                val b = points[i + 1]
                val scale = cos(Math.toRadians(location.latitude))
                fun longitudeDelta(value: Double) = ((value + 540.0) % 360.0) - 180.0
                val ax = longitudeDelta(a.longitude - location.longitude) * scale
                val ay = a.latitude - location.latitude
                val bx = ax + longitudeDelta(b.longitude - a.longitude) * scale
                val by = b.latitude - location.latitude
                val dx = bx - ax
                val dy = by - ay
                val lengthSquared = dx * dx + dy * dy
                val fraction = if (lengthSquared == 0.0) 0.0 else
                    (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
                val distance = (ax + fraction * dx).pow(2) + (ay + fraction * dy).pow(2)
                val segmentMeters = distanceMeters(a, b)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestAlong = total + fraction * segmentMeters
                }
                total += segmentMeters
            }
            return bestAlong
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
    val remainingMeters: Double?,
    val phoneSessionId: String? = null,
    val localSessionId: String = java.util.UUID.randomUUID().toString()
) {
    val uid: String get() = if (route.eventTime != null) "route-follow-cot-${route.uid}"
        else "route-follow-${route.serverId.length}-${route.serverId}-${route.uid}"
    fun matches(route: TakRoute): Boolean = this.route.uid == route.uid &&
        (this.route.serverId == route.serverId || (this.route.eventTime != null && route.eventTime != null))
}
