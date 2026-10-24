package com.aitech.weartak_civ.data.parsers

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TakRouteCotParserTest {
    @Test fun methodCheckpointsControlsAndPointUidsRoundTripWithoutChangingRawIndices() {
        val source = """
            <event uid="route" type="b-m-r"><detail><contact callsign="A &amp; B"/>
            <link_attr method="Driving"/><link uid="start" type="b-m-p-w" point="0,0"/>
            <link uid="control" type="b-m-p-c" point="0,0.001"/>
            <link uid="finish" type="b-m-p-w" point="0,0.002"/>
            </detail></event>
        """.trimIndent()
        val route = requireNotNull(TakRouteCotParser.parse(source, "server", strictPoints = true))
        assertEquals("Driving", route.method)
        assertEquals(listOf(true, false, true), route.points.map { it.isCheckpoint })
        assertEquals(listOf("start", "control", "finish"), route.points.map { it.uid })
        val restored = requireNotNull(TakRouteCotParser.parse(TakRouteCotParser.serialize(route), "server", strictPoints = true))
        assertEquals(route.method, restored.method)
        assertEquals(route.name, restored.name)
        assertEquals(route.points.map { it.uid to it.isCheckpoint }, restored.points.map { it.uid to it.isCheckpoint })
        assertEquals(route.points.map { it.longitude }, restored.points.map { it.longitude })
        assertFalse(requireNotNull(TakRouteCotParser.parse(
            """<event uid="legacy" type="b-m-r"><detail><link point="0,0"/></detail></event>""", "server"))
            .points.single().isCheckpoint)
    }

    private val xml = """
        <event uid="route-1" type="b-m-r"><detail>
            <contact callsign="Test route"/>
            <remarks source="sender-1">Private route remarks</remarks>
            <marti><dest mission="Test mission"/></marti>
            <link point="34.12345,-117.54321,100" callsign="Start"/>
            <link point="34.2,-117.6"/>
            <link point="34.3,-117.7,200" callsign="Finish"/>
        </detail></event>
    """.trimIndent()

    @Test
    fun preservesPointOrderCoordinatesAndRouteMetadata() {
        val route = requireNotNull(TakRouteCotParser.parse(xml, "server-1"))

        assertEquals("route-1", route.uid)
        assertEquals("Test route", route.name)
        assertEquals("Test mission", route.missionName)
        assertEquals("sender-1", route.sender)
        assertEquals("server-1", route.serverId)
        assertEquals(listOf("Start", null, "Finish"), route.points.map { it.name })
        assertEquals(listOf(34.12345, 34.2, 34.3), route.points.map { it.latitude })
        assertEquals(listOf(-117.54321, -117.6, -117.7), route.points.map { it.longitude })
        assertEquals(listOf(100.0, null, 200.0), route.points.map { it.altitude })
    }

    @Test
    fun rejectsNonRoutesMissingIdentityEmptyAndMalformedDocuments() {
        listOf(
            xml.replace("b-m-r", "a-f-G"),
            xml.replace("uid=\"route-1\"", "uid=\" \""),
            "<event uid='route' type='b-m-r'><detail/></event>",
            "<event uid='route' type='b-m-r'><detail>"
        ).forEach { assertNull(TakRouteCotParser.parse(it, "server-1")) }
    }

    @Test
    fun skipsInvalidCoordinatesWithoutReorderingValidPoints() {
        val links = listOf(
            "NaN,-117", "34,Infinity", "91,-117", "34,-181", "invalid,-117", "34,-117,Infinity"
        ).joinToString("") { "<link point='$it'/>" }
        val route = requireNotNull(TakRouteCotParser.parse(
            xml.replace("<contact", "$links<contact"), "server-1"
        ))
        assertEquals(listOf("Start", null, "Finish"), route.points.map { it.name })
    }

    @Test
    fun parsingDoesNotLogRoutePayloadEvenOnFailure() {
        ShadowLog.clear()
        TakRouteCotParser.parse(xml, "server-1")
        assertNull(TakRouteCotParser.parse(xml.replace("</detail>", "</broken>"), "server-1"))
        val messages = ShadowLog.getLogsForTag("RouteCoT").joinToString { it.msg }
        assertFalse(messages.contains("<event"))
        assertFalse(messages.contains("34.12345"))
        assertFalse(messages.contains("Private route remarks"))
        assertFalse(messages.contains("sender-1"))
        assertTrue(messages.contains("Unable to parse route CoT"))
    }
}
