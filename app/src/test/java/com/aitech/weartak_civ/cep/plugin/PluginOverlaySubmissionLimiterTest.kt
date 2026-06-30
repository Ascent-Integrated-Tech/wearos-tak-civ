package com.aitech.weartak_civ.cep.plugin

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginOverlaySubmissionLimiterTest {

    @After
    fun resetLimiter() = PluginOverlaySubmissionLimiter.resetForTest()

    @Test
    fun perPluginBucketsAreIndependent() {
        val now = 100_000L
        repeat(5) { PluginOverlaySubmissionLimiter.tryAcquire("plugin.a", now) }
        assertFalse(PluginOverlaySubmissionLimiter.tryAcquire("plugin.a", now))
        assertTrue(PluginOverlaySubmissionLimiter.tryAcquire("plugin.b", now))
    }

    @Test
    fun pruneDropsIdleBuckets() {
        val now = 100_000L
        assertTrue(PluginOverlaySubmissionLimiter.tryAcquire("plugin.a", now))
        PluginOverlaySubmissionLimiter.pruneIdleBuckets(now + 6L * 60L * 1000L)
        assertTrue(PluginOverlaySubmissionLimiter.tryAcquire("plugin.a", now + 6L * 60L * 1000L))
    }
}
