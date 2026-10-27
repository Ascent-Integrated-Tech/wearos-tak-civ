package com.aitech.weartak_civ.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionTransportDebounceTest {
    @Test
    fun briefDropKeepsDirectTransportGateClosedAndReconnectCancelsResume() = runTest {
        val debounce = CompanionTransportDebounce(this)
        debounce.connected()

        debounce.disconnected(wasConnected = true)
        assertTrue(debounce.graceActive.value)
        advanceTimeBy(CompanionTransportDebounce.DISCONNECT_GRACE_MILLIS - 1)
        runCurrent()
        assertTrue("The direct-transport gate remains active during the grace window",
            debounce.graceActive.value)

        debounce.connected()
        advanceTimeBy(CompanionTransportDebounce.DISCONNECT_GRACE_MILLIS)
        runCurrent()
        assertFalse("A reconnect cancels the pending direct-transport resume", debounce.graceActive.value)
    }

    @Test
    fun sustainedDropResumesAfterGraceAndUrgentOutputCanResumeImmediately() = runTest {
        val debounce = CompanionTransportDebounce(this)
        debounce.connected()
        debounce.disconnected(wasConnected = true)

        advanceTimeBy(CompanionTransportDebounce.DISCONNECT_GRACE_MILLIS)
        runCurrent()
        assertFalse("Direct transports resume after a sustained drop", debounce.graceActive.value)

        debounce.connected()
        debounce.disconnected(wasConnected = true)
        assertTrue(debounce.resumeImmediately())
        assertFalse(debounce.graceActive.value)
    }

    @Test
    fun coldStartWithBondedCompanionWaitsForReadyOrGraceExpiry() = runTest {
        val debounce = CompanionTransportDebounce(this)

        debounce.awaitingInitialConnection()
        assertTrue("A bonded Companion pending its first ready handshake suppresses transports",
            debounce.graceActive.value)
        advanceTimeBy(CompanionTransportDebounce.DISCONNECT_GRACE_MILLIS - 1)
        runCurrent()
        assertTrue(debounce.graceActive.value)

        debounce.connected()
        assertFalse("Companion readiness cancels the startup grace", debounce.graceActive.value)
        debounce.awaitingInitialConnection()
        advanceTimeBy(CompanionTransportDebounce.DISCONNECT_GRACE_MILLIS)
        runCurrent()
        assertFalse("Direct transports resume when the startup grace expires", debounce.graceActive.value)
    }
}
