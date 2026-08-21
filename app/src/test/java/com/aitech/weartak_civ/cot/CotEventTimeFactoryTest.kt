package com.aitech.weartak_civ.cot

import org.junit.Assert.assertEquals
import org.junit.Test

class CotEventTimeFactoryTest {
    @Test
    fun appliesStartAndStaleOffsetsFromOneClockReading() {
        val time = CotEventTimeFactory.at(
            nowMillis = 0L,
            startOffset = CotTimeOffset(seconds = -4),
            staleOffset = CotTimeOffset(minutes = -3),
        )

        assertEquals(0L, time.time)
        assertEquals(-4_000L, time.start)
        assertEquals(-180_000L, time.stale)
        assertEquals("1970-01-01T00:00:00.000Z", time.timeCot)
        assertEquals("1969-12-31T23:59:56.000Z", time.startCot)
        assertEquals("1969-12-31T23:57:00.000Z", time.staleCot)
    }
}
