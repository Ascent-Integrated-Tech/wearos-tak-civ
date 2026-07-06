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
    }
}
