package com.aitech.weartak_civ.cep.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-(pluginId, topic) token bucket used by [com.aitech.weartak_civ.integration.plugin.PluginIngestReceiver]
 * to enforce the design §920 "leaky bucket default 5 Hz" sample-ingress invariant.
 *
 * Token bucket with capacity [DEFAULT_CAPACITY] and refill rate [DEFAULT_RATE_PER_SEC] is
 * the simplest implementation that matches "5 Hz sustained with brief burst tolerance".
 * One token is consumed per accepted sample; [tryAcquire] returns false (rate-limited) when
 * no token is available.
 */
object PluginIngestRateLimiter {
    const val DEFAULT_CAPACITY = 5.0
    const val DEFAULT_RATE_PER_SEC = 5.0
    private const val DEFAULT_IDLE_PRUNE_MS = 5L * 60L * 1000L

    private data class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /**
     * Attempt to admit one sample for the given (pluginId, topic). Returns true if a token
     * was available (admit) or false if the bucket is empty (drop with diagnostic).
     */
    fun tryAcquire(
        pluginId: String,
        topic: String,
        nowMs: Long,
        capacity: Double = DEFAULT_CAPACITY,
        ratePerSec: Double = DEFAULT_RATE_PER_SEC,
    ): Boolean {
        pruneIdleBuckets(nowMs)
        val key = "$pluginId|$topic"
        val b = buckets.computeIfAbsent(key) { Bucket(capacity, nowMs) }
        synchronized(b) {
            val elapsedSec = ((nowMs - b.lastRefillMs).coerceAtLeast(0L)) / 1000.0
            b.tokens = (b.tokens + elapsedSec * ratePerSec).coerceAtMost(capacity)
            b.lastRefillMs = nowMs
            return if (b.tokens >= 1.0) {
                b.tokens -= 1.0
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

    /** Test hook: drop accumulated bucket state. */
    fun resetForTest() {
        buckets.clear()
    }
}
