package com.aitech.weartak_civ.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DittoBleDiscoveryTest {
    private class Fixture(scope: TestScope) {
        val changes = mutableListOf<Boolean>()
        val events = mutableListOf<Pair<BleDiscoveryAction, BleDiscoveryReason>>()
        var succeeds = true
        val discovery = DittoBleDiscovery(scope, {
            if (succeeds) changes += it
            succeeds
        }) { action, reason, _ -> events += action to reason }

        init {
            discovery.peersChanged(emptySet(), 0)
        }
    }

    @Test fun emptyScanStopsAtExactlyThirtySecondsAndNeverSchedulesAnotherWindow() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(listOf(true), f.changes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(true, false), f.changes)
        assertEquals(BleDiscoveryAction.BACKOFF to BleDiscoveryReason.WINDOW_EXPIRED, f.events.last())
        advanceTimeBy(24 * 60 * 60 * 1000L)
        advanceUntilIdle()
        assertEquals(listOf(true, false), f.changes)
        f.discovery.close()
    }

    @Test fun everyApprovedEventCanReopenExactlyOneBoundedWindow() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        advanceUntilIdle()
        listOf(BleDiscoveryReason.UI, BleDiscoveryReason.WRIST_ON, BleDiscoveryReason.SETTINGS).forEach { reason ->
            f.discovery.request(reason)
            runCurrent()
            assertTrue(f.changes.last())
            advanceTimeBy(30_000)
            runCurrent()
            assertFalse(f.changes.last())
        }
        f.discovery.peersChanged(setOf("lan-1"), 0)
        runCurrent()
        assertTrue(f.changes.last())
        advanceUntilIdle()
        assertFalse(f.changes.last())
        assertEquals(10, f.changes.size)
        f.discovery.close()
    }

    @Test fun repeatedEventsAndPresenceSnapshotsCannotExtendAnOpenWindow() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        repeat(30) {
            advanceTimeBy(1_000)
            f.discovery.request(BleDiscoveryReason.UI)
            f.discovery.peersChanged(emptySet(), 0)
            runCurrent()
        }
        assertEquals(listOf(true, false), f.changes)
        repeat(20) { f.discovery.peersChanged(emptySet(), 0) }
        advanceUntilIdle()
        assertEquals(listOf(true, false), f.changes)
        f.discovery.close()
    }

    @Test fun aBleConnectionCancelsExpiryUntilTheLastBlePeerDisconnects() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        advanceTimeBy(29_999)
        f.discovery.peersChanged(setOf("ble-1", "ble-2", "lan"), 2)
        advanceUntilIdle()
        assertEquals(listOf(true), f.changes)
        f.discovery.peersChanged(setOf("ble-2", "lan"), 1)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(listOf(true), f.changes)
        f.discovery.peersChanged(setOf("lan"), 0)
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(listOf(true), f.changes)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(true, false), f.changes)
        f.discovery.close()
    }

    @Test fun lanPeersDoNotKeepBleOnOrReopenItForIdenticalSnapshots() = runTest {
        val f = Fixture(this)
        f.discovery.peersChanged(setOf("lan"), 0)
        f.discovery.setAllowed(true)
        advanceUntilIdle()
        repeat(10) { f.discovery.peersChanged(setOf("lan"), 0) }
        advanceUntilIdle()
        assertEquals(listOf(true, false), f.changes)
        f.discovery.close()
    }

    @Test fun gatingImmediatelyStopsConnectedBleAndTriggersCannotOverrideIt() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        f.discovery.peersChanged(setOf("ble"), 1)
        f.discovery.setAllowed(false)
        f.discovery.peersChanged(emptySet(), 0)
        BleDiscoveryReason.entries.forEach { f.discovery.request(it) }
        advanceUntilIdle()
        assertEquals(listOf(true, false), f.changes)
        f.discovery.setAllowed(true)
        advanceUntilIdle()
        assertEquals(listOf(true, false, true, false), f.changes)
        f.discovery.close()
    }

    @Test fun closingCancelsExpiryAndRejectsLateCallbacksAndTriggers() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        f.discovery.close()
        f.discovery.close()
        f.discovery.setAllowed(true)
        f.discovery.peersChanged(setOf("ble"), 1)
        f.discovery.request(BleDiscoveryReason.UI)
        advanceUntilIdle()
        assertEquals(listOf(true, false), f.changes)
        assertEquals(BleDiscoveryAction.STOP to BleDiscoveryReason.CLOSED, f.events.last())
        assertFalse(f.events.any { it.first == BleDiscoveryAction.BACKOFF })
    }

    @Test fun aCancelledOldDeadlineCannotStopAReplacementWindow() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        runCurrent()
        advanceTimeBy(20_000)
        f.discovery.setAllowed(false)
        f.discovery.setAllowed(true)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(true, false, true), f.changes)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(listOf(true, false, true, false), f.changes)
        f.discovery.close()
    }

    @Test fun rejectedConfigIsNotReportedAsSuccessfulStopOrBackoff() = runTest {
        val f = Fixture(this)
        f.discovery.setAllowed(true)
        f.succeeds = false
        advanceUntilIdle()
        assertEquals(listOf(true), f.changes)
        assertEquals(listOf(BleDiscoveryAction.START to BleDiscoveryReason.STARTUP), f.events)
        f.succeeds = true
        f.discovery.close()
        assertEquals(listOf(true, false), f.changes)
    }

    @Test fun diagnosticIdIsAppendedWithoutChangingMotionConfirmation() {
        assertEquals(41, DiagnosticEvent.MOTION_CONFIRMATION.ordinal)
        assertEquals(42, DiagnosticEvent.POWER_CONTEXT.ordinal)
        assertEquals(43, DiagnosticEvent.DEVICE_SETTING.ordinal)
        assertEquals(44, DiagnosticEvent.RESOURCE_LIFETIME.ordinal)
        assertEquals(45, DiagnosticEvent.MISSION_LIST.ordinal)
        assertEquals(46, DiagnosticEvent.OFF_BODY_TAK.ordinal)
        assertEquals(47, DiagnosticEvent.BLE_DISCOVERY.ordinal)
    }
}
