package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.data.model.TakRoutePoint
import org.junit.Assert.*
import org.junit.Test

class RouteFollowTest {
    private fun point(meters: Double) = TakRoutePoint(0.0, Math.toDegrees(meters / 6_371_000.0))
    private val points = listOf(point(0.0), point(100.0), point(200.0))

    @Test fun nearestStartUsesNextCheckpointAheadOfProjectedPosition() {
        val follow = RouteFollow.start(points, RouteFollow.StartMode.NEAREST, point(110.0))
        assertEquals(2, follow.currentIndex)
        assertEquals(3, follow.pointNumber)
    }

    @Test fun beginningStartIgnoresNearestVertex() {
        assertEquals(0, RouteFollow.start(points, RouteFollow.StartMode.BEGINNING, point(200.0)).currentIndex)
    }

    @Test fun denseCheckpointsWaitForAlongRouteDepartureAfterArrival() {
        val follow = RouteFollow.start(listOf(point(0.0), point(5.0), point(9.0), point(80.0)),
            RouteFollow.StartMode.BEGINNING).advance(point(0.0))
        assertEquals(1, follow.currentIndex)
        assertEquals(3, follow.advance(point(10.0)).currentIndex)
        assertFalse(follow.completed)
    }

    @Test fun outsideArrivalRadiusDoesNotAdvance() {
        val follow = RouteFollow.start(points, RouteFollow.StartMode.BEGINNING).advance(point(-21.0))
        assertEquals(0, follow.currentIndex)
    }

    @Test fun finalPointCompletesAndStaysComplete() {
        val follow = RouteFollow.start(points, RouteFollow.StartMode.NEAREST, point(200.0)).advance(point(200.0))
        assertTrue(follow.completed)
        assertEquals(3, follow.pointNumber)
        assertSame(follow, follow.advance(point(0.0)))
        assertEquals(0.0, follow.remainingDistanceMeters(point(0.0)), 0.001)
    }

    @Test fun remainingIncludesApproachAndRemainingSegments() {
        val follow = RouteFollow.start(points, RouteFollow.StartMode.BEGINNING)
        assertEquals(250.0, follow.remainingDistanceMeters(point(-50.0)), 0.001)
        assertEquals(180.0, follow.advance(point(0.0)).remainingDistanceMeters(point(20.0)), 0.001)
    }

    @Test fun singlePointRouteCompletesOnlyOnArrival() {
        val follow = RouteFollow.start(listOf(point(0.0)), RouteFollow.StartMode.BEGINNING)
        assertFalse(follow.advance(point(30.0)).completed)
        assertTrue(follow.advance(point(0.0)).completed)
    }

    @Test fun passingUnvisitedWaypointDoesNotSkipItButFinalArrivalCompletes() {
        val follow = RouteFollow.start(points, RouteFollow.StartMode.BEGINNING)
        assertEquals(0, follow.advance(point(50.0)).currentIndex)
        assertTrue(follow.advance(points.last()).completed)
    }

    @Test fun arrivalBoundaryAdvancesAndFinalBoundaryCompletes() {
        val follow = RouteFollow.start(points, RouteFollow.StartMode.BEGINNING)
        assertEquals(1, follow.advance(point(9.999)).currentIndex)
        assertEquals(0, follow.advance(point(10.001)).currentIndex)
        val final = follow.atIndex(2)
        assertTrue(final.advance(point(209.999)).completed)
        assertFalse(final.advance(point(210.001)).completed)
    }

    @Test fun methodRadiiAndGnssAccuracyDetermineFinalArrival() {
        listOf("Walking" to 10.0, "Swimming" to 10.0, "Driving" to 20.0,
            "Watercraft" to 100.0, "Flying" to 1000.0, "Unknown" to 10.0).forEach { (method, radius) ->
            val follow = RouteFollow.start(listOf(point(0.0)), RouteFollow.StartMode.BEGINNING, method = method)
            assertEquals(radius, follow.arrivalRadiusMeters, 0.0)
            assertFalse(follow.advance(point(radius + 1)).completed)
            assertTrue(follow.advance(point(radius + 4), accuracyMeters = 5.0).completed)
            assertFalse(follow.advance(point(radius + 1), accuracyMeters = Double.NaN).completed)
        }
    }

    @Test fun controlsRemainGeometryButAreNotNavigationObjectives() {
        val route = listOf(point(0.0), point(50.0).copy(isCheckpoint = false), point(100.0), point(200.0))
        val follow = RouteFollow.start(route, RouteFollow.StartMode.BEGINNING).advance(point(0.0))
        assertEquals(2, follow.currentIndex)
        assertEquals(150.0, follow.remainingDistanceMeters(point(50.0)), 0.001)
        assertEquals(2, RouteFollow.start(route, RouteFollow.StartMode.NEAREST, point(30.0)).currentIndex)
    }

    @Test fun arrivalBeforeCheckpointWaitsForProjectionAndDepartureNeedNotLeaveBubble() {
        val follow = RouteFollow.start(points, RouteFollow.StartMode.NEAREST, point(90.0))
        assertEquals(1, follow.advance(point(95.0)).currentIndex)
        assertEquals(2, follow.advance(point(95.0)).advance(point(101.0)).currentIndex)
        assertEquals(1, follow.advance(point(130.0)).currentIndex)
    }

    @Test fun noCheckpointsAndTrailingControlsUseFinalVertexAsDestination() {
        val controls = points.map { it.copy(isCheckpoint = false) }
        val follow = RouteFollow.start(controls, RouteFollow.StartMode.BEGINNING)
        assertEquals(2, follow.currentIndex)
        assertTrue(follow.advance(point(200.0)).completed)
        val trailing = listOf(point(0.0), point(100.0), point(200.0).copy(isCheckpoint = false))
        assertEquals(2, RouteFollow.start(trailing, RouteFollow.StartMode.NEAREST, point(150.0)).currentIndex)
    }

    @Test fun duplicateCoordinatesAndProjectionTiesKeepOriginalIndicesAndTerminate() {
        val duplicate = RouteFollow.start(listOf(point(0.0), point(0.0), point(100.0)),
            RouteFollow.StartMode.BEGINNING).advance(point(0.0))
        assertEquals(2, duplicate.currentIndex)
        val loop = listOf(point(0.0), point(100.0), point(0.0))
        assertEquals(50.0, RouteFollow.projectedDistanceMeters(loop, point(50.0)), 0.001)
        assertEquals(1, RouteFollow.start(loop, RouteFollow.StartMode.NEAREST, point(50.0)).currentIndex)
    }

    @Test fun invalidAccuracyDoesNotEnlargeBubbleAndDatelineProjectionFollowsShortSegment() {
        val follow = RouteFollow.start(listOf(point(0.0)), RouteFollow.StartMode.BEGINNING)
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertFalse(follow.advance(point(11.0), it).completed)
        }
        val dateline = listOf(TakRoutePoint(0.0, 179.999), TakRoutePoint(0.0, -179.999))
        assertEquals(RouteFollow.distanceMeters(dateline[0], dateline[1]) / 2.0,
            RouteFollow.projectedDistanceMeters(dateline, TakRoutePoint(0.0, 180.0)), 0.001)
    }

    @Test(expected = IllegalArgumentException::class) fun emptyRouteIsGuarded() {
        RouteFollow.start(emptyList(), RouteFollow.StartMode.BEGINNING)
    }

    @Test(expected = IllegalArgumentException::class) fun nearestWithoutFixIsGuarded() {
        RouteFollow.start(points, RouteFollow.StartMode.NEAREST)
    }

    @Test(expected = IllegalArgumentException::class) fun invalidRouteIsGuarded() {
        RouteFollow.start(listOf(TakRoutePoint(Double.NaN, 0.0)), RouteFollow.StartMode.BEGINNING)
    }
}
