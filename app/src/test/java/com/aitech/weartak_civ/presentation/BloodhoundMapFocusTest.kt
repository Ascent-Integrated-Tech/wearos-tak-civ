package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.data.model.TakRoutePoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BloodhoundMapFocusTest {
    @Test fun pointSnapsToExactTargetAtUsualZoom() {
        val frame = routeCameraFrame(listOf(TakRoutePoint(38.0, -77.0)), 192.0, 192.0)
        assertEquals(38.0, frame.latitude, 0.000001)
        assertEquals(-77.0, frame.longitude, 0.000001)
        assertEquals(15.5, frame.zoom, 0.0)
    }

    @Test fun fullRouteFitsRoundWatchSafeViewport() {
        val points = listOf(TakRoutePoint(34.0, -118.0), TakRoutePoint(36.0, -116.0), TakRoutePoint(35.0, -119.0))
        val frame = routeCameraFrame(points, 192.0, 192.0)
        val scale = 512.0 * 2.0.pow(frame.zoom)
        fun mercator(lat: Double) = (1.0 - ln(tan(PI / 4.0 + Math.toRadians(lat) / 2.0)) / PI) / 2.0
        points.forEach {
            assertTrue(abs(it.longitude - frame.longitude) / 360.0 * scale <= 192.0 * 0.3 + 0.00001)
            assertTrue(abs(mercator(it.latitude) - mercator(frame.latitude)) * scale <= 192.0 * 0.3 + 0.00001)
        }
    }

    @Test fun datelineRouteFitsLocallyRatherThanZoomingToWholeWorld() {
        val frame = routeCameraFrame(listOf(TakRoutePoint(0.0, 179.9), TakRoutePoint(0.0, -179.9)), 192.0, 192.0)
        assertEquals(180.0, abs(frame.longitude), 0.000001)
        assertTrue(frame.zoom > 8.0)
    }

    @Test(expected = IllegalArgumentException::class) fun emptyRouteCannotFrame() {
        routeCameraFrame(emptyList(), 192.0, 192.0)
    }
}
