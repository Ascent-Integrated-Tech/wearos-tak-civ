package com.aitech.weartak_civ.service

import org.junit.Assert.*
import org.junit.Test

class DittoSubscriptionsTest {
    @Test fun tracksUseInteropNumericTimestampsAndTenMinuteRecentWindow() {
        assertEquals("SELECT * FROM track WHERE o > 1800000000000 OR b > 1799999400000",
            DittoSubscriptions.query("track", 1_800_000_000_000))
    }

    @Test fun recentExpiredEventsStillIncludeCancellationsAndPointDeletionWithoutSchemaChanges() {
        listOf("mapitem", "alert").forEach {
            assertEquals("SELECT * FROM $it WHERE o > 1800000000000 OR b > 1799913600000",
                DittoSubscriptions.query(it, 1_800_000_000_000))
        }
        assertEquals(3_600_000, DittoSubscriptions.REFRESH_MS)
        assertThrows(IllegalArgumentException::class.java) { DittoSubscriptions.query("untrusted", 0) }
    }
}
