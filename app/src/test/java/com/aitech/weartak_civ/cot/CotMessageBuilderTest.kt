package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.BatteryService.DockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class CotMessageBuilderTest {
    @Test
    fun pliMatchesLegacyStructureWithStatus() {
        val actual = CotMessageBuilder.pli(pliSnapshot(), CotMessageBuilder.PliOptions("EAGLE", "Team Member", "Cyan", false, "<hail/>"))
        val legacy = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='watch-1' type='a-f-G-U-C' time='$START' start='$START' stale='$STALE' how='m-g'><point lat='41.000000' lon='-87.000000' hae='100.0' ce='4' le='5'/><detail><status readiness='true' battery='80'/><remarks>Exert:0.25%;HR:72;SkinTemp:98.6</remarks><contact endpoint='*:-1:stcp' callsign='EAGLE'/><__group role='Team Member' name='Cyan'/><track course='45' speed='3'/><hail/></detail></event>"
        assertEquals(project(legacy.replace("<detail>", "<detail>${CotDetailBuilder.takv()}")), project(actual))
    }

    @Test
    fun batdokHeartRateOnlyUsesIncidentTimeNotExpiration() {
        assertBatdokVitalTimes(72, -1f)
    }

    @Test
    fun batdokHeartRateAndTemperatureUseIncidentTimeNotExpiration() {
        assertBatdokVitalTimes(72, 98.6f)
    }

    @Test
    fun batdokTemperatureOnlyKeepsIncidentTime() {
        assertBatdokVitalTimes(-1, 98.6f)
    }

    private fun assertBatdokVitalTimes(heartRateBpm: Int, skinTempF: Float) {
        val epoch = Instant.parse("2026-10-15T12:34:56.789Z").toEpochMilli()
        val staleOffsets = listOf(60, 300).flatMap { reportingIntervalSeconds ->
            // Exercise both the reported historical offsets and the current PLI policy.
            listOf(3 * reportingIntervalSeconds, 2 * reportingIntervalSeconds + 15)
        }
        for (staleOffsetSeconds in staleOffsets) {
            val eventTime = CotEventTimeFactory.at(
                nowMillis = epoch,
                staleOffset = CotTimeOffset(seconds = staleOffsetSeconds),
            )
            val physio = physio().copy(data = physio().data.copy(
                heartRateBpm = heartRateBpm,
                skinTempF = skinTempF,
            ))
            val xml = CotMessageBuilder.pli(
                pliSnapshot().copy(time = eventTime, physioData = physio),
                CotMessageBuilder.PliOptions("EAGLE", "Team Member", "Cyan", true, ""),
            )
            val event = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(xml))).documentElement
            assertEquals(eventTime.startCot, event.getAttribute("time"))
            assertEquals(eventTime.startCot, event.getAttribute("start"))
            assertEquals(eventTime.staleCot, event.getAttribute("stale"))
            assertEquals(
                epoch + staleOffsetSeconds * 1_000L,
                Instant.parse(event.getAttribute("stale")).toEpochMilli(),
            )

            val atmistNodes = event.getElementsByTagName("_atmist_")
            assertEquals(1, atmistNodes.length)
            val atmist = atmistNodes.item(0) as Element
            assertEquals("30", atmist.getAttribute("age"))
            assertEquals(eventTime.startCot, atmist.getAttribute("timeOfIncident"))
            val expectedTuples = buildList {
                if (heartRateBpm != -1) add("(HR,72,$epoch)")
                if (skinTempF != -1f) add("(Temp,98.6 (F) 37 (C),$epoch)")
            }
            val vitals = atmist.getElementsByTagName("vitalSign")
            assertEquals(expectedTuples.size, vitals.length)
            expectedTuples.forEachIndexed { index, tuple ->
                val vital = vitals.item(index) as Element
                assertEquals(index.toString(), vital.getAttribute("index"))
                assertEquals(tuple, vital.textContent)
                assertEquals(atmist.getAttribute("timeOfIncident"), vital.getAttribute("timestamp"))
                assertEquals(epoch, Instant.parse(vital.getAttribute("timestamp")).toEpochMilli())
            }
        }
    }

    @Test
    fun emergencyAlertAndCancelMatchLegacyStructure() {
        val alert = emergencySnapshot(DataRepository.AlertState.ALERT)
        val cancel = emergencySnapshot(DataRepository.AlertState.CANCEL)
        val alertXml = CotMessageBuilder.emergency(alert)
        val cancelXml = CotMessageBuilder.emergency(cancel)
        assertTrue(alertXml.contains("<biometrics"))
        assertTrue(alertXml.contains("uid='alert-1'"))
        assertTrue(alertXml.contains("alertUid='alert-1'"))
        assertTrue(alertXml.contains("<weartak_location timeMillis='0'/>"))
        assertTrue(alertXml.contains("alertState='ALERT'"))
        assertTrue(alertXml.contains("alertCategory='Alert'"))
        assertTrue(alertXml.contains("alertPriority='1'"))
        assertTrue(alertXml.contains("<device><model>WEAROS</model><uid>watch-1</uid><hr>72</hr><skt>98.6</skt><exert>0.25</exert></device>"))
        assertTrue(!alertXml.contains("<device model='WEAROS'"))
        assertTrue(alertXml.contains("type='b-a-o'"))
        assertTrue(cancelXml.contains("<biometrics"))
        assertTrue(cancelXml.contains("alertUid='alert-1'"))
        assertTrue(cancelXml.contains("alertState='CANCEL'"))
        assertTrue(cancelXml.contains("<device><model>WEAROS</model><uid>watch-1</uid><hr>72</hr><skt>98.6</skt><exert>0.25</exert></device>"))
        assertTrue(cancelXml.contains("type='b-a-o-can'"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emergencyWithoutLocationCannotCreateAPhantomPosition() {
        CotMessageBuilder.emergency(
            emergencySnapshot(DataRepository.AlertState.ALERT).copy(location = null),
        )
    }

    @Test
    fun emergencyNeverSerializesInvalidCoordinatesOrErrors() {
        for (invalid in listOf(
            location().copy(lat = Double.NaN),
            location().copy(lon = Double.POSITIVE_INFINITY),
            location().copy(lat = 91.0),
            location().copy(lon = -181.0),
        )) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                CotMessageBuilder.emergency(emergencySnapshot(DataRepository.AlertState.ALERT).copy(location = invalid))
            }
        }
        val xml = CotMessageBuilder.emergency(
            emergencySnapshot(DataRepository.AlertState.CANCEL).copy(
                location = location().copy(hae = Double.NaN, ce = Float.POSITIVE_INFINITY, le = -1f),
            ),
        )
        assertTrue(xml.contains("hae='0.0'"))
        assertTrue(xml.contains("ce='9999999.0'"))
        assertTrue(xml.contains("le='9999999.0'"))
    }

    @Test
    fun standardManualAlertsUseAtakTypesAndRoundTripAsTheirDisplayCategory() {
        StandardEmergencyType.entries.forEach { standardType ->
            val alert = emergencySnapshot(DataRepository.AlertState.ALERT)
                .copy(category = "Manual Alert", description = standardType.displayName)
            val xml = CotMessageBuilder.emergency(alert)
            val parsed = CotMessageParser.parseEmergencyOrNull(xml)

            assertEquals(standardType.cotType, rootAttribute(xml, "type"))
            assertTrue(xml.contains("type='${standardType.cotDescription}'"))
            assertEquals(standardType.displayName, parsed?.category)
            assertEquals(standardType.cotDescription, parsed?.description)

            val cancelXml = CotMessageBuilder.emergency(alert.copy(alertState = DataRepository.AlertState.CANCEL))
            assertEquals("b-a-o-can", rootAttribute(cancelXml, "type"))
            assertEquals(alert.uid, rootAttribute(cancelXml, "uid"))
        }
    }

    @Test
    fun markerAndClearMatchLegacyStructure() {
        val marker = markerSnapshot(time())
        val clear = markerSnapshot(CotEventTime(0, 0, 0, START, "2026-08-19T11:59:56.000Z", "2026-08-19T11:57:00.000Z"))
        val legacy = "<event version='2.0' uid='marker-1' type='a-f-G' time='$START' start='$START' stale='$STALE' how='h-g-i-g-o' access='Undefined'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><status readiness='true' battery='80'/><precisionlocation altsrc='SRTM1'/><link uid='watch-1' production_time='$START' type='a-f-G-U-C' parent_callsign='EAGLE' relation='p-p'/><color argb='-1'/><usericon iconsetpath='COT_MAPPING_2525C/a-f/a-f-G'/><remarks>Remark</remarks><contact callsign='Title'/></detail></event>"
        assertEquals(project(legacy), project(CotMessageBuilder.marker(marker, "Title", marker.markerType.icon)))
        assertEquals("2026-08-19T11:57:00.000Z", rootAttribute(CotMessageBuilder.marker(clear, "Title", clear.markerType.icon), "stale"))
    }

    @Test
    fun chatMatchesLegacyStructure() {
        val actual = CotMessageBuilder.chat(chatSnapshot(), "EAGLE", "watch-1", "message-1", 0)
        val legacy = "<event version='2.0' uid='GeoChat.watch-1.room-1.message-1' type='b-t-f' time='1970-01-01T00:00:00.000Z' start='1970-01-01T00:00:00.000Z' stale='1970-01-02T00:00:00.000Z' how='h-g-i-g-o' access='Undefined'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><__chat parent='RootContactGroup' groupOwner='false' messageId='message-1' chatroom='Room' id='room-1' senderCallsign='EAGLE'><chatgrp uid0='watch-1' uid1='room-1' id='room-1'/></__chat><link uid='watch-1' type='a-f-G-U-C' relation='p-p'/><remarks source='watch-1' to='room-1' time='1970-01-01T00:00:00.000Z'>Hello</remarks><marti><dest callsign='Room'/></marti></detail></event>"
        assertEquals(project(legacy), project(actual))
    }

            @Test
            fun chatStaysLiveForOneDaySoStaleFilteringServersRelayIt() {
                val now = Instant.parse("2026-10-21T14:18:18.469Z").toEpochMilli()
                val xml = CotMessageBuilder.chat(chatSnapshot(), "EAGLE", "watch-1", "message-1", now)
                assertEquals("2026-10-21T14:18:18.469Z", rootAttribute(xml, "time"))
                assertEquals("2026-10-22T14:18:18.469Z", rootAttribute(xml, "stale"))
            }
    private fun pliSnapshot() = DataRepository.PliSnapshot(location(), time(), physio(), system())
    private fun markerSnapshot(time: CotEventTime) = DataRepository.MarkerSnapshot(DataRepository.MarkerCotType.DROP, "marker-1", DataRepository.MarkerType.FRIENDLY, "Title", "Remark", DataRepository.MarkerType.FRIENDLY.icon, false, false, location(), time, physio(), system())
    private fun emergencySnapshot(state: DataRepository.AlertState) = DataRepository.EmergencyEventSnapshot("alert-1", state, "Alert", "Help", 1, false, time(), location(), physio(), system())
    private fun chatSnapshot() = DataRepository.ChatEventSnapshot("ignored", "room-1", "Room", "Hello", time(), location(), physio(), system())
    private fun location() = DataRepository.LocationSnapshot(0, 41.0, -87.0, 100.0, 4f, 5f, 45.9f, 3.8f)
    private fun time() = CotEventTime(0, 0, 0, START, START, STALE)
    private fun physio() = DataRepository.PhysioSnapshot(0, DataRepository.PhysioData(72, 98.6f, 30, 70, 60, .25, null, null))
    private fun system() = DataRepository.SystemSnapshot(0, 80, "EAGLE", "watch-1", "1", 10, DataRepository.SystemStates(0, DockType.NONE, false, true, 10, 10))

    private fun project(xml: String): String {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        return project(root)
    }

    private fun project(element: Element): String = buildString {
        append('<').append(element.tagName)
        (0 until element.attributes.length).map { element.attributes.item(it) }.sortedBy { it.nodeName }.forEach {
            append(' ').append(it.nodeName).append('=').append(it.nodeValue)
        }
        append('>')
        for (index in 0 until element.childNodes.length) {
            when (val child = element.childNodes.item(index)) {
                is Element -> append(project(child))
                else -> if (child.nodeType == Node.TEXT_NODE && child.nodeValue.isNotBlank()) append(child.nodeValue.trim())
            }
        }
        append("</").append(element.tagName).append('>')
    }

    private fun rootAttribute(xml: String, name: String): String =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement.getAttribute(name)

    private companion object {
        const val START = "2026-08-19T12:00:00.000Z"
        const val STALE = "2026-08-19T12:00:30.000Z"
    }
}
