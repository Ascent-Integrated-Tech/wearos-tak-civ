package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.events.PliRefreshReason
import org.junit.Assert.*
import org.junit.Test

class InteractionPliRefreshGateTest {
    @Test fun identityCanPublishExistingFreshFixButNeverStaleUnknownOrFutureFix() {
        val fix = 1_000_000_000L
        assertTrue(InteractionPliRefreshGate.freshForIdentity(fix, fix, true))
        assertTrue(InteractionPliRefreshGate.freshForIdentity(fix, fix + 3_000_000_000L, true))
        assertFalse(InteractionPliRefreshGate.freshForIdentity(fix, fix + 3_000_000_001L, true))
        assertFalse(InteractionPliRefreshGate.freshForIdentity(0, fix, true))
        assertFalse(InteractionPliRefreshGate.freshForIdentity(fix, fix - 1, true))
        assertFalse(InteractionPliRefreshGate.freshForIdentity(fix, fix, false))
    }

    @Test fun overlappingReasonsShareAcquisitionAndRequireFixAfterLatestTrigger() {
        val gate = InteractionPliRefreshGate()
        assertTrue(gate.request(PliRefreshReason.MAP_OPENED, 100))
        assertFalse(gate.request(PliRefreshReason.CHAT_SENT, 200))
        assertNull(gate.consume(150, 300, true))
        assertEquals(setOf(PliRefreshReason.MAP_OPENED, PliRefreshReason.CHAT_SENT), gate.consume(250, 300, true))
        assertNull(gate.consume(250, 300, true))
        assertTrue(gate.request(PliRefreshReason.ROLE_CHANGED, 400))
    }

    @Test fun failedOrCancelledRequestCannotForcePeriodicFix() {
        val gate = InteractionPliRefreshGate()
        gate.request(PliRefreshReason.MANUAL_ALERT_CLEARED, 100)
        assertNull(gate.consume(200, 300, false))
        assertNull(gate.consume(400, 300, true))
        assertNull(gate.consume(200, 16_000_000_300L, true))
        gate.cancel()
        assertNull(gate.consume(200, 300, true))
        assertTrue(gate.request(PliRefreshReason.MANUAL_ALERT_ACTIVATED, 400))
    }

    @Test fun usableFixRejectsMissingAccuracyAndInvalidCoordinatesButAllowsEquator() {
        assertTrue(InteractionPliRefreshGate.usable(0.0, 0.0, 5f, true))
        assertFalse(InteractionPliRefreshGate.usable(Double.NaN, 0.0, 5f, true))
        assertFalse(InteractionPliRefreshGate.usable(91.0, 0.0, 5f, true))
        assertFalse(InteractionPliRefreshGate.usable(0.0, 181.0, 5f, true))
        assertFalse(InteractionPliRefreshGate.usable(0.0, 0.0, Float.NaN, true))
        assertFalse(InteractionPliRefreshGate.usable(0.0, 0.0, -1f, true))
        assertFalse(InteractionPliRefreshGate.usable(0.0, 0.0, 5f, false))
    }

    @Test fun newTriggersDoNotExtendBoundedAcquisitionAndUnrequestedPeriodicFixCannotForcePli() {
        val gate = InteractionPliRefreshGate()
        assertNull(gate.consume(100, 200, true))
        assertTrue(gate.request(PliRefreshReason.MAP_OPENED, 1_000))
        assertFalse(gate.request(PliRefreshReason.ROLE_CHANGED, 14_000_001_000))
        assertNull(gate.consume(15_000_001_500, 15_000_002_000, true))
        gate.cancel()
        assertNull(gate.consume(16_000_000_000, 16_000_000_000, true))
    }
}
