package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.BatteryService.DockType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class CotMessageBuilderTest {
    @Test
    fun pliMatchesLegacyStructure() {
        val actual = CotMessageBuilder.pli(pliSnapshot(), CotMessageBuilder.PliOptions("EAGLE", "Team Member", "Cyan", false, "<hail/>"))
        val legacy = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='watch-1' type='a-f-G-U-C' time='$START' start='$START' stale='$STALE' how='m-g'><point lat='41.000000' lon='-87.000000' hae='100.0' ce='4' le='5'/><detail><remarks>Exert:0.25%;HR:72;SkinTemp:98.6</remarks><contact endpoint='*:-1:stcp' callsign='EAGLE'/><__group role='Team Member' name='Cyan'/><track course='45' speed='3'/><hail/></detail></event>"
        assertEquals(project(legacy), project(actual))
    }

    @Test
    fun emergencyAlertAndCancelMatchLegacyStructure() {
        val alert = emergencySnapshot(DataRepository.AlertState.ALERT)
        val cancel = emergencySnapshot(DataRepository.AlertState.CANCEL)
        val legacyAlert = "<event version='2.0' uid='alert-1' type='b-a-o' time='$START' start='$START' stale='$STALE' how='h-e' access='Undefined'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><link uid='watch-1' type='a-f-G-U-C' relation='p-p'/><contact callsign='EAGLE&#10;Help'/><emergency type='Help'>EAGLE</emergency><usericon iconsetpath='911 Alert'/><color argb='-1'/></detail></event>"
        val legacyCancel = "<event version='2.0' uid='alert-1' type='b-a-o-can' time='$START' start='$START' stale='$STALE' how='h-e' access='Undefined'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><emergency cancel='true'>EAGLE</emergency></detail></event>"
        assertEquals(project(legacyAlert), project(CotMessageBuilder.emergency(alert)))
        assertEquals(project(legacyCancel), project(CotMessageBuilder.emergency(cancel)))
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
        val legacy = "<event version='2.0' uid='GeoChat.watch-1.room-1.message-1' type='b-t-f' time='1970-01-01T00:00:00.000Z' start='1970-01-01T00:00:00.000Z' stale='1970-01-01T00:00:00.000Z' how='h-g-i-g-o' access='Undefined'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><__chat parent='RootContactGroup' groupOwner='false' messageId='message-1' chatroom='Room' id='room-1' senderCallsign='EAGLE'><chatgrp uid0='watch-1' uid1='room-1' id='room-1'/></__chat><link uid='watch-1' type='a-f-G-U-C' relation='p-p'/><remarks source='watch-1' to='room-1' time='1970-01-01T00:00:00.000Z'>Hello</remarks><marti><dest callsign='Room'/></marti></detail></event>"
        assertEquals(project(legacy), project(actual))
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
