package com.aitech.weartak_civ.cep.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerTimerTest {

    @Test fun mainFireIsOnlyConsumedExplicitly() {
        val timer = TriggerTimer(holdDownSec = 10)

        assertTrue(timer.tick(isTrue = true, now = 1_000L, effectiveSustainSec = 0).mainFired)
        assertTrue(timer.tick(isTrue = true, now = 2_000L, effectiveSustainSec = 0).mainFired)

        timer.consumeMain(now = 2_000L)

        assertFalse(timer.tick(isTrue = true, now = 3_000L, effectiveSustainSec = 0).mainFired)
        assertFalse(timer.tick(isTrue = true, now = 11_999L, effectiveSustainSec = 0).mainFired)
        assertTrue(timer.tick(isTrue = true, now = 12_000L, effectiveSustainSec = 0).mainFired)
    }

    @Test fun firstFireAtZeroAndResetDoNotRequireCooldown() {
        val timer = TriggerTimer(holdDownSec = 10)

        assertTrue(timer.tick(isTrue = true, now = 0L, effectiveSustainSec = 0).mainFired)
        timer.consumeMain(now = 0L)
        assertFalse(timer.tick(isTrue = true, now = 1L, effectiveSustainSec = 0).mainFired)

        timer.reset()

        assertTrue(timer.tick(isTrue = true, now = 2L, effectiveSustainSec = 0).mainFired)
    }

    @Test fun falseConditionCannotFireWithZeroSustain() {
        val timer = TriggerTimer(holdDownSec = 10)

        assertFalse(timer.tick(isTrue = false, now = 0L, effectiveSustainSec = 0).mainFired)
        assertTrue(timer.tick(isTrue = true, now = 1L, effectiveSustainSec = 0).mainFired)
        assertFalse(timer.tick(isTrue = false, now = 2L, effectiveSustainSec = 0).mainFired)
    }

    @Test fun firstFireStillRequiresContinuousSustain() {
        val timer = TriggerTimer(holdDownSec = 10)

        assertFalse(timer.tick(isTrue = true, now = 0L, effectiveSustainSec = 2).mainFired)
        assertFalse(timer.tick(isTrue = true, now = 1_999L, effectiveSustainSec = 2).mainFired)
        assertFalse(timer.tick(isTrue = false, now = 2_000L, effectiveSustainSec = 2).mainFired)
        assertFalse(timer.tick(isTrue = true, now = 3_000L, effectiveSustainSec = 2).mainFired)
        assertTrue(timer.tick(isTrue = true, now = 5_000L, effectiveSustainSec = 2).mainFired)
    }
}
