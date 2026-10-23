package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.data.model.TakRoutePoint
import org.junit.Assert.*
import org.junit.Test

class RouteFollowTest {
    private fun point(meters: Double) = TakRoutePoint(0.0, Math.toDegrees(meters / 6_371_000.0))
    private val points = listOf(point(0.0), point(100.0), point(200.0))

    @Test fun nearestStartUsesOriginalVertexIndex() {
        val follow = RouteFollow.start(points, RouteFollow.StartMode.NEAREST, point(110.0))
        assertEquals(1, follow.currentIndex)
        assertEquals(2, follow.pointNumber)
    }

    @Test fun beginningStartIgnoresNearestVertex() {
        assertEquals(0, RouteFollow.start(points, RouteFollow.StartMode.BEGINNING, point(200.0)).currentIndex)
    }

    @Test fun advanceSkipsConsecutiveDenseVerticesWithinTwentyMeters() {
        val follow = RouteFollow.start(listOf(point(0.0), point(5.0), point(19.0), point(80.0)),
            RouteFollow.StartMode.BEGINNING).advance(point(0.0))
        assertEquals(3, follow.currentIndex)
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
        assertEquals(180.0, follow.advance(point(19.0)).remainingDistanceMeters(point(20.0)), 0.001)
    }

    @Test fun singlePointRouteCompletesOnlyOnArrival() {
        val follow = RouteFollow.start(listOf(point(0.0)), RouteFollow.StartMode.BEGINNING)
        assertFalse(follow.advance(point(30.0)).completed)
        assertTrue(follow.advance(point(0.0)).completed)
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
