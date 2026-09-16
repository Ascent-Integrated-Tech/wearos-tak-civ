package com.aitech.weartak_civ.cot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CotMessageParserTest {
    @Test
    fun parsesMarkerFieldsWithoutChangingTheirMeaning() {
        val event = CotMessageParser.parseMarkerOrNull(MARKER_XML)
        assertNotNull(event)

        assertEquals("marker-1", event!!.uid)
        assertEquals("a-h-G", event.type)
        assertEquals(1_777_636_800_000L, event.time)
        assertEquals(CotPoint(41.0, -87.0, 100.0, 4.0, 5.0), event.point)
        assertEquals("Target", event.detail.markerCallsign)
        assertEquals("watch-1", event.detail.link?.uid)
        assertEquals("Remark", event.detail.remarks)
    }

    @Test
    fun parsesPliAndRejectsItAsAMarker() {
        val event = CotMessageParser.parseUserPositionOrNull(PLI_XML)
        assertNotNull(event)

        assertEquals("watch-1", event!!.uid)
        assertEquals("Cyan", event.team)
        assertEquals("Team Member", event.role)
        assertEquals("EAGLE", event.detail.markerCallsign)
        assertNull(CotMessageParser.parseMarkerOrNull(PLI_XML))
    }

    @Test
    fun preservesChatAddressingAndParsedRoomShape() {
        assertTrue(CotMessageParser.isChatMessage(CHAT_XML))
        assertTrue(CotMessageParser.isChatForRecipient(CHAT_XML, "watch-1", "EAGLE"))
        assertFalse(CotMessageParser.isChatForRecipient(CHAT_XML, "another-watch", "OTHER"))

        val room = CotMessageParser.parseChatRooms(
            CHAT_XML,
            "watch-1",
            "EAGLE",
            sourceServerId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            receivedAt = 123L
        ).single()
        assertEquals("sender-1", room.uid)
        assertEquals("SENDER", room.title)
        assertEquals("Hello", room.messageList.single().message)
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", room.messageList.single().sourceServerId)
        assertEquals(123L, room.messageList.single().timeReceived)
    }

    private companion object {
        const val MARKER_XML = "<event version='2.0' uid='marker-1' type='a-h-G' time='2026-05-01T12:00:00.000Z' start='2026-05-01T12:00:00.000Z' stale='2027-05-01T12:00:00.000Z' how='h-g-i-g-o' access='Undefined'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><link uid='watch-1' production_time='2026-05-01T12:00:00.000Z' type='a-f-G-U-C' parent_callsign='EAGLE' relation='p-p'/><remarks>Remark</remarks><contact callsign='Target'/></detail></event>"
        const val PLI_XML = "<event version='2.0' uid='watch-1' type='a-f-G-U-C' time='2026-05-01T12:00:00.000Z' start='2026-05-01T12:00:00.000Z' stale='2026-05-01T12:00:30.000Z' how='m-g'><point lat='41.0' lon='-87.0' hae='100.0' ce='4.0' le='5.0'/><detail><contact callsign='EAGLE'/><__group role='Team Member' name='Cyan'/></detail></event>"
        const val CHAT_XML = "<event version='2.0' uid='chat-1' type='b-t-f' time='2026-05-01T12:00:00.000Z' start='2026-05-01T12:00:00.000Z' stale='2026-05-01T12:00:00.000Z' how='h-g-i-g-o'><point lat='0' lon='0' hae='0' ce='1' le='1'/><detail><__chat senderCallsign='SENDER'><chatgrp uid0='sender-1' uid1='watch-1' id='watch-1'/></__chat><remarks to='watch-1'>Hello</remarks></detail></event>"
    }
}
