package com.aitech.weartak_civ.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TakServerKeepaliveTest {
    private val keepalive = TakServerKeepalive(60)
    private val rx = 1_000_000L

    @Test fun computesOnlySilenceRepeatAndResponseDeadlines() {
        assertEquals(rx + 60_000, keepalive.nextDeadlineMs(rx, rx, 0))
        assertEquals(rx + 70_000, keepalive.nextDeadlineMs(rx + 60_001, rx, rx + 60_000))
        assertEquals(rx + 90_000, keepalive.nextDeadlineMs(rx + 89_999, rx, rx + 85_000))
        assertEquals(rx + 90_000, keepalive.nextDeadlineMs(rx + 90_000, rx, rx + 85_000))
        assertEquals(null, TakServerKeepalive(0).nextDeadlineMs(rx, rx, 0))
    }

    @Test fun watchdogSleepsUntilExactDeadlinesWithoutFiveSecondPolling() = runTest {
        val actions = mutableListOf<Pair<TakServerKeepalive.Action, Long>>()
        var clockReads = 0
        val job = backgroundScope.launch {
            keepalive.watch(AtomicLong(rx), Channel(Channel.CONFLATED), { true },
                { clockReads++; rx + testScheduler.currentTime }) { action, at -> actions.add(action to at) }
        }
        runCurrent()
        advanceTimeBy(59_999)
        runCurrent()
        assertTrue(actions.isEmpty())
        assertEquals(1, clockReads)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(TakServerKeepalive.Action.PING to rx + 60_000), actions)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(
            TakServerKeepalive.Action.PING to rx + 60_000,
            TakServerKeepalive.Action.PING to rx + 70_000,
            TakServerKeepalive.Action.PING to rx + 80_000,
            TakServerKeepalive.Action.RECONNECT to rx + 90_000
        ), actions)
        assertTrue(job.isCompleted)
    }

    @Test fun receivedBytesCancelOldPingAndReconnectDeadlines() = runTest {
        val lastRx = AtomicLong(rx)
        val activity = Channel<Unit>(Channel.CONFLATED)
        val actions = mutableListOf<Pair<TakServerKeepalive.Action, Long>>()
        backgroundScope.launch {
            keepalive.watch(lastRx, activity, { true }, { rx + testScheduler.currentTime }) { action, at ->
                actions.add(action to at)
            }
        }
        runCurrent()
        advanceTimeBy(65_000)
        runCurrent()
        lastRx.set(rx + 65_000)
        activity.trySend(Unit)
        runCurrent()
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(listOf(TakServerKeepalive.Action.PING to rx + 60_000), actions)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(TakServerKeepalive.Action.PING to rx + 125_000, actions.last())
        assertEquals(2, actions.size)
    }

    @Test fun disabledAndInvalidatedWatchdogsCannotPerformActions() = runTest {
        val activity = Channel<Unit>(Channel.CONFLATED)
        val actions = mutableListOf<TakServerKeepalive.Action>()
        TakServerKeepalive(0).watch(AtomicLong(rx), activity, { true }, { rx }) { action, _ -> actions.add(action) }
        var current = true
        val job = backgroundScope.launch {
            keepalive.watch(AtomicLong(rx), activity, { current }, { rx + testScheduler.currentTime }) { action, _ ->
                actions.add(action)
            }
        }
        runCurrent()
        current = false
        activity.trySend(Unit)
        runCurrent()
        advanceTimeBy(90_000)
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun staysQuietWhileTrafficIsRecent() {
        assertEquals(TakServerKeepalive.Action.NONE, keepalive.next(rx + 59_999, rx, 0))
    }

    @Test
    fun pingsOnceSilenceThresholdIsReached() {
        assertEquals(TakServerKeepalive.Action.PING, keepalive.next(rx + 60_000, rx, 0))
    }

    @Test
    fun repeatsPingOnlyAfterRepeatInterval() {
        val pingedAt = rx + 60_000
        assertEquals(TakServerKeepalive.Action.NONE, keepalive.next(pingedAt + 9_999, rx, pingedAt))
        assertEquals(TakServerKeepalive.Action.PING, keepalive.next(pingedAt + 10_000, rx, pingedAt))
    }

    @Test
    fun inboundTrafficAfterPingResetsSilence() {
        val pingedAt = rx + 60_000
        val replyAt = pingedAt + 500
        assertEquals(TakServerKeepalive.Action.NONE, keepalive.next(replyAt + 30_000, replyAt, pingedAt))
        assertEquals(TakServerKeepalive.Action.PING, keepalive.next(replyAt + 60_000, replyAt, pingedAt))
    }

    @Test
    fun reconnectsWhenPingGoesUnanswered() {
        assertEquals(TakServerKeepalive.Action.PING, keepalive.next(rx + 89_999, rx, rx + 70_000))
        assertEquals(TakServerKeepalive.Action.RECONNECT, keepalive.next(rx + 90_000, rx, rx + 80_000))
    }

    @Test
    fun zeroDisablesAndSmallValuesAreRaisedToFloor() {
        assertFalse(TakServerKeepalive(0).enabled)
        assertEquals(TakServerKeepalive.Action.NONE, TakServerKeepalive(0).next(rx + 10_000_000, rx, 0))
        assertEquals(0, TakServerKeepalive.normalize(0))
        assertEquals(0, TakServerKeepalive.normalize(-5))
        assertEquals(15, TakServerKeepalive.normalize(1))
        assertEquals(120, TakServerKeepalive.normalize(120))
        assertTrue(TakServerKeepalive(TakServerKeepalive.normalize(1)).enabled)
    }

    @Test
    fun pingCotMatchesAtakPingShape() {
        val xml = TakServerKeepalive.pingCot("ANDROID-abc", 0L)
        assertTrue(xml.contains("uid=\"ANDROID-abc-ping\""))
        assertTrue(xml.contains("type=\"t-x-c-t\""))
        assertTrue(xml.contains("time=\"1970-01-01T00:00:00.000Z\""))
        assertTrue(xml.contains("stale=\"1970-01-01T00:00:20.000Z\""))
        assertTrue(xml.endsWith("</event>"))
    }

    @Test
    fun pingCotEscapesUid() {
        val xml = TakServerKeepalive.pingCot("a\"<b>&", 0L)
        assertTrue(xml.contains("uid=\"a&quot;&lt;b&gt;&amp;-ping\""))
    }
}
