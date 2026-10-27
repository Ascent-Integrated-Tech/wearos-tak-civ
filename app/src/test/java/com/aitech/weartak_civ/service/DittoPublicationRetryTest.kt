package com.aitech.weartak_civ.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DittoPublicationRetryTest {
    @Test fun emptyLedgerHasNoTimerAndNewIntentStartsAtTenSeconds() = runTest {
        val changes = MutableStateFlow(0L)
        var pending = false
        val attempts = mutableListOf<Long>()
        DittoPublicationRetry(this, changes, { pending }, {
            attempts += testScheduler.currentTime
            pending = false
            changes.value++
        }).use {
            runCurrent()
            advanceUntilIdle()
            assertEquals(0, testScheduler.currentTime)
            pending = true
            changes.value++
            runCurrent()
            advanceTimeBy(9_999)
            assertTrue(attempts.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(10_000L), attempts)
            advanceUntilIdle()
            assertEquals(10_000, testScheduler.currentTime)
        }
    }

    @Test fun failuresBackOffToFiveMinutesAndNewPublicationResetsTheDelay() = runTest {
        val changes = MutableStateFlow(0L)
        val attempts = mutableListOf<Long>()
        DittoPublicationRetry(this, changes, { true }, { attempts += testScheduler.currentTime }).use {
            runCurrent()
            advanceTimeBy(1_000_001)
            assertEquals(listOf(10_000L, 30_000L, 70_000L, 150_000L, 310_000L,
                610_000L, 910_000L), attempts)
            changes.value++
            runCurrent()
            advanceTimeBy(10_001)
            assertEquals(1_010_001L, attempts.last())
        }
    }

    @Test fun powerPauseCancelsRetryTimerAndResumeAndPartialSuccessResetBackoff() = runTest {
        val changes = MutableStateFlow(0L)
        val allowed = MutableStateFlow(true)
        val attempts = mutableListOf<Long>()
        DittoPublicationRetry(this, changes, { true }, {
            attempts += testScheduler.currentTime
            if (attempts.size == 2) changes.value++
        }, allowed).use {
            runCurrent()
            advanceTimeBy(10_001)
            allowed.value = false
            runCurrent()
            advanceTimeBy(1_000_000)
            assertEquals(listOf(10_000L), attempts)
            allowed.value = true
            runCurrent()
            advanceTimeBy(20_001)
            assertEquals(listOf(10_000L, 1_020_001L, 1_030_001L), attempts)
        }
        advanceUntilIdle()
        assertEquals(3, attempts.size)
    }
}
