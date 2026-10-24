package com.aitech.weartak_civ.service

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DataSyncSessionTest {
    @Test fun pauseStopsPollingResumeRestartsAndConnectionEndCleansUp() = runTest {
        val enabled = MutableStateFlow(false)
        var connected = true
        var starts = 0
        var stops = 0
        val session = backgroundScope.launch {
            runDataSyncSession(enabled, { connected }) {
                starts++
                try { awaitCancellation() } finally { stops++ }
            }
        }
        runCurrent()
        assertEquals(0, starts)
        enabled.value = true
        runCurrent()
        assertEquals(1, starts)
        enabled.value = false
        runCurrent()
        assertEquals(1, stops)
        enabled.value = true
        runCurrent()
        assertEquals(2, starts)
        connected = false
        advanceTimeBy(1001)
        runCurrent()
        assertTrue(session.isCompleted)
        assertEquals(2, stops)
        enabled.value = false
        enabled.value = true
        runCurrent()
        assertEquals(2, starts)
    }
}
