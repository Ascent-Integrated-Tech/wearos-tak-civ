package com.aitech.weartak_civ.service

import org.junit.Assert.assertEquals
import org.junit.Test

class DittoTrackExpiryTest {
    @Test
    fun expiryKeepsSelfAndPeerIdentityButOverridesLongStaleTimeWithNow() {
        val oldTrack = mapOf<String, Any?>(
            "_id" to "WEAROS_watch",
            "a" to "watch-peer-key",
            "d" to "WEAROS_watch",
            "b" to 1_000L,
            "n" to 1_000L,
            "o" to 2_701_000L,
            "r" to "<detail/>",
        )

        val expired = DittoTrackExpiry.expire(oldTrack, nowMillis = 2_000L)

        assertEquals(oldTrack["_id"], expired["_id"])
        assertEquals(oldTrack["a"], expired["a"])
        assertEquals(oldTrack["d"], expired["d"])
        assertEquals(2_000L, expired["b"])
        assertEquals(2_000L, expired["n"])
        assertEquals(2_000L, expired["o"])
    }
}
