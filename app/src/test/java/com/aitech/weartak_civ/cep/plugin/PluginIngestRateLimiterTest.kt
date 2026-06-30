package com.aitech.weartak_civ.cep.plugin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Token-bucket invariants for [PluginIngestRateLimiter] (design §920). */
class PluginIngestRateLimiterTest {

    @After fun resetBuckets() = PluginIngestRateLimiter.resetForTest()

    @Test fun burstFillsBucketThenDropsFurtherCalls() {
        // Capacity=5 → first 5 same-instant calls admit, 6th drops.
        val now = 100_000L
        repeat(5) { idx ->
            assertTrue("call $idx must admit (capacity=5)",
                PluginIngestRateLimiter.tryAcquire("p", "p.topic", now))
        }
        assertFalse(PluginIngestRateLimiter.tryAcquire("p", "p.topic", now))
    }

    @Test fun bucketRefillsAtFiveHzSustained() {
        val t0 = 100_000L
        // Drain
        repeat(5) { PluginIngestRateLimiter.tryAcquire("p", "p.topic", t0) }
        assertFalse(PluginIngestRateLimiter.tryAcquire("p", "p.topic", t0))
        // After 1 second, the bucket has refilled to full capacity.
        val admitted = (0 until 5).count {
            PluginIngestRateLimiter.tryAcquire("p", "p.topic", t0 + 1000L)
        }
        assertEquals(5, admitted)
    }

    @Test fun perTopicBucketsAreIndependent() {
        val now = 100_000L
        repeat(5) { PluginIngestRateLimiter.tryAcquire("p", "p.topicA", now) }
        // Bucket A drained, but bucket B is still full.
        assertFalse(PluginIngestRateLimiter.tryAcquire("p", "p.topicA", now))
        assertTrue(PluginIngestRateLimiter.tryAcquire("p", "p.topicB", now))
    }

    @Test fun perPluginIdBucketsAreIndependent() {
        val now = 100_000L
        repeat(5) { PluginIngestRateLimiter.tryAcquire("plugin.a", "shared.topic", now) }
        assertFalse(PluginIngestRateLimiter.tryAcquire("plugin.a", "shared.topic", now))
        // Different pluginId, same topic name → independent bucket.
        assertTrue(PluginIngestRateLimiter.tryAcquire("plugin.b", "shared.topic", now))
    }
}
