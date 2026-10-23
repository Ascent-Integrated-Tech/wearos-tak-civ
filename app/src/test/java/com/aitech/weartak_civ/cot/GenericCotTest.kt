package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.presentation.circleOutline
import com.aitech.weartak_civ.presentation.circleSegments
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GenericCotTest {
    private fun xml(type: String, detail: String = "") = """
        <event uid="plane" type="$type" time="2026-01-01T00:00:00Z" start="2026-01-01T00:00:00Z" stale="2026-01-01T00:15:00Z">
          <point lat="41" lon="-87" hae="100" ce="10" le="10"/><detail>$detail</detail>
        </event>
    """.trimIndent()

    @Test fun spotAndOtherAtomsHaveValidatedCoordinatesAndEndpointMetadata() {
        for (type in listOf("b-m-p-s-m", "a-n-A-C-F", "a-n-A-C-H", "a-f-Z", "a-u-X")) {
            val event = CotMessageParser.parseMarkerOrNull(xml(type, "<contact callsign='Flight' endpoint='*:4242:tcp'/>"))!!
            assertEquals(type, event.type)
            assertEquals("*:4242:tcp", event.detail.contact)
            assertNull(CotMessageParser.parseMarkerOrNull(xml(type).replace("lat=\"41\"", "lat=\"91\"")))
            assertNull(CotMessageParser.parseMarkerOrNull(xml(type))!!.detail.contact)
        }
    }

    @Test fun deletionUsesExplicitTargetOrEventUidAndRejectsInvalidTime() {
        assertEquals("target", CotMessageParser.parseDeletionOrNull(xml("t-x-d-d", "<link uid='target' relation='none'/>"))!!.uid)
        assertEquals("plane", CotMessageParser.parseDeletionOrNull(xml("t-x-d-d"))!!.uid)
        assertNull(CotMessageParser.parseDeletionOrNull(xml("a-f-Z")))
        assertNull(CotMessageParser.parseDeletionOrNull(xml("t-x-d-d").replace("2026-01-01T00:00:00Z", "invalid")))
        assertTrue(CompanionGatewaySession.supported(xml("t-x-d-d")))
        val gateway = CompanionGatewaySession()
        assertTrue(gateway.acceptEvent("plane", 100))
        assertTrue(gateway.acceptEvent("plane", 100, deleted = true))
        assertFalse(gateway.acceptEvent("plane", 100))
        assertFalse(gateway.acceptEvent("plane", 100, deleted = true))
        assertTrue(gateway.acceptEvent("plane", 101))
    }

    @Test fun onlyBoundedCircularEllipsesHaveAnOutline() {
        fun radius(major: String, minor: String) = CotMessageParser.parseMarkerOrNull(
            xml("u-d-c-c", "<shape><ellipse major='$major' minor='$minor'/></shape>"))!!.detail.circleRadiusMeters
        assertEquals(100.0, radius("100", "100")!!, 0.0)
        for ((major, minor) in listOf("0" to "0", "-1" to "-1", "NaN" to "NaN", "100" to "200", "100001" to "100001"))
            assertNull(radius(major, minor))
        val outline = circleOutline(41.0, -87.0, 100.0)
        assertEquals(37, outline.size)
        assertEquals(outline.first(), outline.last())
        outline.forEach { point ->
            val dLat = Math.toRadians(point.latitude - 41.0)
            val dLon = Math.toRadians(point.longitude + 87.0)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(41.0)) *
                cos(Math.toRadians(point.latitude)) * sin(dLon / 2).pow(2)
            assertEquals(100.0, 6_371_000 * 2 * asin(sqrt(a)), 0.01)
        }
        assertTrue(circleOutline(89.999, 179.999, 1000.0).all {
            it.latitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        })
        val segments = circleSegments(circleOutline(41.0, 179.999, 1000.0))
        assertTrue(segments.size > 1)
        assertTrue(segments.all { it.zipWithNext().all { (a, b) -> abs(a.longitude - b.longitude) <= 180 } })
    }
}
