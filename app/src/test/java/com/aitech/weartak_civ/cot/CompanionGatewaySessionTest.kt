package com.aitech.weartak_civ.cot

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionGatewaySessionTest {
    private fun hello(token: String = "session-1", version: Int = 1) =
        JSONObject().put("session", token).put("version", version)
    private fun cot(type: String = "a-f-G-U-C") =
        """<event version="2.0" uid="watch-1" type="$type" time="2026-10-14T12:00:00Z" start="2026-10-14T12:00:00Z" stale="2026-10-14T12:02:00Z" how="h-g-i-g-o"><point lat="1" lon="2" hae="3" ce="4" le="5"/><detail><contact callsign="WATCH"/><__group name="Orange" role="Medic"/></detail></event>"""
    private fun chatCot(uid: String = "GeoChat.sender.watch-1.message-1") =
        """<event version="2.0" uid="$uid" type="b-t-f" time="2026-10-14T12:00:00Z" start="2026-10-14T12:00:00Z" stale="2026-10-14T12:15:00Z" how="h-g-i-g-o"><point lat="0" lon="0" hae="0" ce="999999" le="999999"/><detail><__chat parent="RootContactGroup" groupOwner="false" messageId="message-1" chatroom="WATCH" id="watch-1" senderCallsign="SENDER"><chatgrp uid0="sender" uid1="watch-1" id="watch-1"/></__chat><remarks source="sender" to="watch-1" time="2026-10-14T12:00:00Z">Hello</remarks><marti><dest uid="watch-1"/></marti></detail></event>"""
    private fun emergencyCot(type: String = "b-a-o") =
        """<event version="2.0" uid="alert-1" type="$type" time="2026-10-14T12:00:00Z" start="2026-10-14T12:00:00Z" stale="2026-10-14T12:05:00Z" how="h-e"><point lat="1" lon="2" hae="3" ce="4" le="5"/><detail><link uid="watch-1" type="a-f-G-U-C" relation="p-p"/><biometrics><device><uid>watch-1</uid></device></biometrics><contact callsign="WATCH"/><emergency type="TEST">WATCH</emergency></detail></event>"""

    @Test fun negotiationIsExplicitAndTransientWithoutProfileWrites() {
        val session = CompanionGatewaySession()
        assertNull(session.session)
        assertThrows(IllegalArgumentException::class.java) { session.negotiate(hello(version = 2)) }
        val ack = session.negotiate(hello())
        assertEquals("gateway_ready", ack.getString("msgType"))
        assertTrue(ack.getBoolean("mapPosition"))
        assertTrue(ack.getBoolean("emergency"))
        assertTrue(ack.getBoolean("chat"))
        assertFalse(ack.has("phoneManagedSettings"))
        assertFalse(session.ready)
        session.confirm("session-1")
        assertTrue(session.ready)
        assertThrows(IllegalArgumentException::class.java) { session.negotiate(hello("replacement")) }
        session.reset()
        assertNull(session.session)
        assertFalse(session.ready)
        session.negotiate(hello("replacement"))
        assertEquals("replacement", session.session)
    }
    @Test fun supportedMapPositionAlertAndChatKeepOriginalXml() {
        val session = CompanionGatewaySession()
        session.negotiate(hello())
        session.confirm("session-1")
        for (xml in listOf(cot("a-f-G-U-C"), cot("a-f-G-E"), cot("a-h-G"),
                cot("b-m-p-s-p-i"), emergencyCot(), chatCot())) {
            val envelope = JSONObject(session.envelope(xml))
            assertEquals(xml, session.accepts(envelope))
            assertEquals("session-1", envelope.getString("session"))
            assertEquals(xml, envelope.getString("cot"))
        }
    }
    @Test fun unsupportedCoTAndStaleSessionsAreRejected() {
        val session = CompanionGatewaySession()
        session.negotiate(hello())
        session.confirm("session-1")
        for (type in listOf("u-d-p", "t-x-g-c", "b-m-p")) {
            assertThrows(IllegalArgumentException::class.java) { session.envelope(cot(type)) }
        }
        val envelope = JSONObject(session.envelope(chatCot()))
        session.reset()
        assertThrows(IllegalArgumentException::class.java) { session.accepts(envelope) }
        session.negotiate(hello("new"))
        assertThrows(IllegalArgumentException::class.java) { session.accepts(envelope) }
    }
    @Test fun gatewayChatRequiresKnownRecipientsAndOnlyParsesForItsRecipient() {
        assertTrue(CotMessageParser.isValidGatewayChat(chatCot()))
        assertTrue(CotMessageParser.isChatForRecipient(chatCot(), "watch-1", "WATCH"))
        assertFalse(CotMessageParser.isChatForRecipient(chatCot(), "watch-2", "OTHER"))
        val broadcast = chatCot().replace("id=\"watch-1\"", "id=\"All Chat Rooms\"")
            .replace("to=\"watch-1\"", "to=\"All Chat Rooms\"")
        assertTrue(CotMessageParser.isValidGatewayChat(broadcast))
        val malformed = chatCot().replace("uid1=\"watch-1\"", "id=\"watch-1\"")
            .replace("<marti><dest uid=\"watch-1\"/></marti>", "")
            .replace(" to=\"watch-1\"", "")
        assertFalse(CotMessageParser.isValidGatewayChat(malformed))
    }
    @Test fun incomingGatewayEventsAreDeduplicatedByIdentityAndTime() {
        val session = CompanionGatewaySession()
        assertTrue(session.acceptEvent("event-1", 10))
        assertFalse(session.acceptEvent("event-1", 10))
        assertFalse(session.acceptEvent("event-1", 9))
        assertTrue(session.acceptEvent("event-1", 11))
        session.reset()
        assertTrue(session.acceptEvent("event-1", 10))
    }
    @Test fun utf8FramingAndResetPreserveIndependentCallsigns() {
        val framer = CompanionJsonFramer()
        val result = mutableListOf<String>()
        for (byte in "{\"cs\":\"Équipe 日本\"}\n".toByteArray()) result += framer.accept(byteArrayOf(byte))
        assertEquals(listOf("{\"cs\":\"Équipe 日本\"}"), result)
        framer.accept("stale".toByteArray())
        framer.clear()
        assertEquals(listOf("fresh"), framer.accept("fresh\n".toByteArray()))
    }
}
