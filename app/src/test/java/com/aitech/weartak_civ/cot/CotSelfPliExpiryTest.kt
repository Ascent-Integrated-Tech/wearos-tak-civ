package com.aitech.weartak_civ.cot

import org.junit.Assert.assertTrue
import org.junit.Test

class CotSelfPliExpiryTest {
    @Test
    fun finalSelfPliUsesTheSameUidAndExpiresAtNow() {
        val now = 1_798_000_000_000L
        val expectedStale = CotEventTimeFactory.at(now, staleOffset = CotTimeOffset()).staleCot

        val xml = CotMessageBuilder.expiredSelfPli("WEAROS_watch", "WATCH", now)

        assertTrue(xml.contains("uid='WEAROS_watch'"))
        assertTrue(xml.contains("type='a-f-G-U-C'"))
        assertTrue(xml.contains("callsign='WATCH'"))
        assertTrue(xml.contains("time='$expectedStale'"))
        assertTrue(xml.contains("start='$expectedStale'"))
        assertTrue(xml.contains("stale='$expectedStale'"))
    }

    @Test
    fun finalSelfPliKeepsLastKnownPositionInsteadOfNullIsland() {
        val fix = com.aitech.weartak_civ.data.DataRepository.LocationSnapshot(
            timeMillis = 1L, lat = 38.123456, lon = -77.654321, hae = 12.0,
            ce = 5f, le = 5f, course = null, speed = null,
        )

        val xml = CotMessageBuilder.expiredSelfPli("WEAROS_watch", "WATCH", 1_798_000_000_000L, fix)

        assertTrue(xml.contains("lat='38.123456'"))
        assertTrue(xml.contains("lon='-77.654321'"))
    }
}
