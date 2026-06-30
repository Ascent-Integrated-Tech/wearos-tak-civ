package com.aitech.weartak_civ.cep.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-plugin submission limiter for `PLUGIN_OVERLAY` broadcasts.
 *
 * Design intent: keep overlay submissions bounded even when multiple apps can still
 * discover the exported receiver. This is intentionally separate from the sample-ingest
 * limiter because overlay submissions are keyed only by plugin id, not by topic.
 */
object PluginOverlaySubmissionLimiter {
    const val DEFAULT_CAPACITY = 5.0
    const val DEFAULT_RATE_PER_SEC = 1.0
    private const val DEFAULT_IDLE_PRUNE_MS = 5L * 60L * 1000L

    private data class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(
        pluginId: String,
        nowMs: Long,
        capacity: Double = DEFAULT_CAPACITY,
        ratePerSec: Double = DEFAULT_RATE_PER_SEC,
    ): Boolean {
        pruneIdleBuckets(nowMs)
        val bucket = buckets.computeIfAbsent(pluginId) { Bucket(capacity, nowMs) }
        synchronized(bucket) {
            val elapsedSec = ((nowMs - bucket.lastRefillMs).coerceAtLeast(0L)) / 1000.0
            bucket.tokens = (bucket.tokens + elapsedSec * ratePerSec).coerceAtMost(capacity)
            bucket.lastRefillMs = nowMs
            return if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }

    fun pruneIdleBuckets(
        nowMs: Long = System.currentTimeMillis(),
        idleMs: Long = DEFAULT_IDLE_PRUNE_MS,
    ) {
        buckets.entries.removeIf { nowMs - it.value.lastRefillMs > idleMs }
    }

    fun resetForTest() {
        buckets.clear()
    }
}
