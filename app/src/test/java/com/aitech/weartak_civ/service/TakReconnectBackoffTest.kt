package com.aitech.weartak_civ.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TakReconnectBackoffTest {
    @Test fun connectThenDropLoopBacksOffAndStableSessionResets() {
        var streak = 0
        val delays = (1..8).map {
            streak = TakReconnectBackoff.nextStreak(25_000L, streak)
            TakReconnectBackoff.delayMs(streak)
        }
        assertEquals(listOf(0L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L), delays)
        streak = TakReconnectBackoff.nextStreak(TakReconnectBackoff.STABLE_SESSION_MS, streak)
        assertEquals(0, streak)
        assertEquals(0L, TakReconnectBackoff.delayMs(TakReconnectBackoff.nextStreak(1_000L, streak)))
    }

    @Test fun streakIsBounded() {
        var streak = 0
        repeat(100) { streak = TakReconnectBackoff.nextStreak(0L, streak) }
        assertEquals(TakReconnectBackoff.MAX_DELAY_MS, TakReconnectBackoff.delayMs(streak))
    }
}
