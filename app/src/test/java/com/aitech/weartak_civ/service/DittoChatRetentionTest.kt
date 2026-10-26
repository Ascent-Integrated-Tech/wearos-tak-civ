package com.aitech.weartak_civ.service

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoChatRetentionTest {
    private class Fixture {
        val preferences: SharedPreferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("retention-test", Context.MODE_PRIVATE)
        var wall = 1_800_000_000_000L
        var elapsed = 0L
        val events = mutableListOf<String>()
        val cutoffs = mutableListOf<Long>()
        val queries = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        var evictionFails = false
        var closeFails = false
        var subscribeFails = false

        fun policy(prefs: SharedPreferences = preferences) = DittoChatRetention(
            prefs,
            subscribe = { query ->
                events += "subscribe"
                if (subscribeFails) throw IllegalStateException("synthetic subscribe failure")
                queries += query
                Closeable {
                    events += "close"
                    if (closeFails) throw IllegalStateException("synthetic close failure")
                }
            },
            evict = { cutoff ->
                events += "evict"
                cutoffs += cutoff
                if (evictionFails) throw IllegalStateException("synthetic eviction failure")
            },
            onError = errors::add,
            wallClock = { wall },
            elapsedClock = { elapsed }
        )

        fun advance(millis: Long) {
            wall += millis
            elapsed += millis
        }
    }

    @Test fun startupAndDailyCleanupHaveNonoverlappingSubscriptionsAndNoTenSecondResyncLoop() {
        val f = Fixture()
        f.policy().use { policy ->
            policy.start()
            assertEquals(listOf("evict", "subscribe"), f.events)
            repeat(8_639) { f.advance(10_000); policy.refreshIfDue() }
            assertEquals(1, f.cutoffs.size)
            f.advance(10_000)
            policy.refreshIfDue()
            assertEquals(listOf("evict", "subscribe", "close", "evict", "subscribe"), f.events)
            assertEquals(listOf(f.wall - DittoChatRetention.INTERVAL_MS, f.wall), f.cutoffs)
            assertEquals(f.cutoffs.map(DittoChatRetention::subscriptionQuery), f.queries)
            assertTrue(f.errors.isEmpty())
        }
        assertEquals("close", f.events.last())
    }

    @Test fun serviceRestartRetainsCutoffAndRemainingDailyDeadline() {
        val f = Fixture()
        f.policy().use { it.start() }
        f.advance(3_600_000)
        f.policy().use {
            it.start()
            assertEquals(1, f.cutoffs.size)
            assertEquals(f.queries[0], f.queries[1])
            f.advance(DittoChatRetention.INTERVAL_MS - 3_600_001)
            it.refreshIfDue()
            assertEquals(1, f.cutoffs.size)
            f.advance(1)
            it.refreshIfDue()
            assertEquals(2, f.cutoffs.size)
        }
    }

    @Test fun evictionFailureRestoresFilteredSubscriptionAndCannotRetryUntilNextDayEvenAfterRestart() {
        val f = Fixture()
        f.evictionFails = true
        f.policy().use { policy ->
            policy.start()
            assertEquals(1, f.errors.size)
            assertEquals(listOf("evict", "subscribe"), f.events)
            repeat(360) { f.advance(10_000); policy.refreshIfDue() }
            assertEquals(1, f.cutoffs.size)
        }
        f.policy().use {
            it.start()
            assertEquals(1, f.cutoffs.size)
            assertEquals(f.queries[0], f.queries[1])
            f.evictionFails = false
            f.advance(DittoChatRetention.INTERVAL_MS)
            it.refreshIfDue()
            assertEquals(2, f.cutoffs.size)
        }
    }

    @Test fun failedPersistenceNeverEvictsAndReportsErrorInsteadOfSuccess() {
        val f = Fixture()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.getLong(any(), any()) } returns 0L
        every { prefs.edit() } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.commit() } returns false
        f.policy(prefs).use { policy ->
            policy.start()
            assertEquals(listOf("subscribe"), f.events)
            assertEquals(1, f.errors.size)
            repeat(360) { f.advance(10_000); policy.refreshIfDue() }
            assertEquals(1, f.errors.size)
        }
    }

    @Test fun failedUnsubscribeNeverEvictsAnOverlappingSubscription() {
        val f = Fixture()
        f.policy().use {
            it.start()
            f.closeFails = true
            f.advance(DittoChatRetention.INTERVAL_MS)
            it.refreshIfDue()
            assertEquals(1, f.cutoffs.size)
            assertEquals(1, f.errors.size)
            assertEquals(listOf("evict", "subscribe", "close"), f.events)
            f.closeFails = false
        }
    }

    @Test fun failedSubscriptionIsPropagatedSoTheServiceCannotRemainRunningWithoutChatSync() {
        val f = Fixture()
        f.policy().use {
            it.start()
            f.subscribeFails = true
            f.advance(DittoChatRetention.INTERVAL_MS)
            assertThrows(IllegalStateException::class.java) { it.refreshIfDue() }
        }
    }

    @Test fun wallClockChangesCannotCreateRepeatedEvictionsWithinARunningDay() {
        val f = Fixture()
        f.policy().use {
            it.start()
            f.wall += 5 * DittoChatRetention.INTERVAL_MS
            it.refreshIfDue()
            f.wall -= 10 * DittoChatRetention.INTERVAL_MS
            it.refreshIfDue()
            assertEquals(1, f.cutoffs.size)
            f.elapsed += DittoChatRetention.INTERVAL_MS
            it.refreshIfDue()
            assertEquals(2, f.cutoffs.size)
        }
    }

    @Test fun closeIsIdempotentAndFencesAllFutureMaintenance() {
        val f = Fixture()
        val policy = f.policy()
        policy.start()
        policy.close()
        policy.close()
        f.advance(DittoChatRetention.INTERVAL_MS)
        policy.refreshIfDue()
        assertEquals(listOf("evict", "subscribe", "close"), f.events)
    }
}
