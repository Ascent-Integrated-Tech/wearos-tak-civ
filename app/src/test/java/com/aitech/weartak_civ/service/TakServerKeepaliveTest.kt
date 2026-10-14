package com.aitech.weartak_civ.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakServerKeepaliveTest {
    private val keepalive = TakServerKeepalive(60)
    private val rx = 1_000_000L

    @Test
    fun staysQuietWhileTrafficIsRecent() {
        assertEquals(TakServerKeepalive.Action.NONE, keepalive.next(rx + 59_999, rx, 0))
    }

    @Test
    fun pingsOnceSilenceThresholdIsReached() {
        assertEquals(TakServerKeepalive.Action.PING, keepalive.next(rx + 60_000, rx, 0))
    }

    @Test
    fun repeatsPingOnlyAfterRepeatInterval() {
        val pingedAt = rx + 60_000
        assertEquals(TakServerKeepalive.Action.NONE, keepalive.next(pingedAt + 9_999, rx, pingedAt))
        assertEquals(TakServerKeepalive.Action.PING, keepalive.next(pingedAt + 10_000, rx, pingedAt))
    }

    @Test
    fun inboundTrafficAfterPingResetsSilence() {
        val pingedAt = rx + 60_000
        val replyAt = pingedAt + 500
        assertEquals(TakServerKeepalive.Action.NONE, keepalive.next(replyAt + 30_000, replyAt, pingedAt))
        assertEquals(TakServerKeepalive.Action.PING, keepalive.next(replyAt + 60_000, replyAt, pingedAt))
    }

    @Test
    fun reconnectsWhenPingGoesUnanswered() {
        assertEquals(TakServerKeepalive.Action.PING, keepalive.next(rx + 89_999, rx, rx + 70_000))
        assertEquals(TakServerKeepalive.Action.RECONNECT, keepalive.next(rx + 90_000, rx, rx + 80_000))
    }

    @Test
    fun zeroDisablesAndSmallValuesAreRaisedToFloor() {
        assertFalse(TakServerKeepalive(0).enabled)
        assertEquals(TakServerKeepalive.Action.NONE, TakServerKeepalive(0).next(rx + 10_000_000, rx, 0))
        assertEquals(0, TakServerKeepalive.normalize(0))
        assertEquals(0, TakServerKeepalive.normalize(-5))
        assertEquals(15, TakServerKeepalive.normalize(1))
        assertEquals(120, TakServerKeepalive.normalize(120))
        assertTrue(TakServerKeepalive(TakServerKeepalive.normalize(1)).enabled)
    }

    @Test
    fun pingCotMatchesAtakPingShape() {
        val xml = TakServerKeepalive.pingCot("ANDROID-abc", 0L)
        assertTrue(xml.contains("uid=\"ANDROID-abc-ping\""))
        assertTrue(xml.contains("type=\"t-x-c-t\""))
        assertTrue(xml.contains("time=\"1970-01-01T00:00:00.000Z\""))
        assertTrue(xml.contains("stale=\"1970-01-01T00:00:20.000Z\""))
        assertTrue(xml.endsWith("</event>"))
    }

    @Test
    fun pingCotEscapesUid() {
        val xml = TakServerKeepalive.pingCot("a\"<b>&", 0L)
        assertTrue(xml.contains("uid=\"a&quot;&lt;b&gt;&amp;-ping\""))
    }
}
