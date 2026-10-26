package com.aitech.weartak_civ.service

import android.content.SharedPreferences
import android.os.SystemClock
import com.aitech.weartak_civ.cot.DittoChatDocument
import java.io.Closeable
import java.io.IOException

/**
 * V4 eviction resets every peer's replication epoch. Never run it in the publication
 * retry loop without this daily gate, or while an overlapping chat subscription exists.
 * The fixed subscription cutoff prevents refetching evicted history; ingress still
 * checks the current time, including chat's optional shorter `o` and native 24-hour TTL.
 */
internal class DittoChatRetention(
    private val preferences: SharedPreferences,
    private val subscribe: (String) -> Closeable,
    private val evict: (Long) -> Unit,
    private val onError: (Exception) -> Unit,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime
) : Closeable {
    private var subscription: Closeable? = null
    private var nextAttempt = Long.MAX_VALUE
    private var closed = false

    fun start() {
        check(!closed && subscription == null)
        val now = wallClock()
        val previous = preferences.getLong(CUTOFF_KEY, 0)
        // Persist the attempt, not just success: a failing cleanup must not retry every 10s
        // or on each service restart. A backwards wall clock cannot defer cleanup forever.
        val age = if (previous > 0 && now >= previous) now - previous else INTERVAL_MS
        if (age >= INTERVAL_MS) refresh(now)
        else {
            nextAttempt = elapsedClock() + INTERVAL_MS - age
            subscription = subscribe(subscriptionQuery(previous))
        }
    }

    fun refreshIfDue() {
        if (!closed && elapsedClock() >= nextAttempt) refresh(wallClock())
    }

    private fun refresh(cutoff: Long) {
        nextAttempt = elapsedClock() + INTERVAL_MS
        try {
            if (!preferences.edit().putLong(CUTOFF_KEY, cutoff).commit()) {
                throw IOException("Could not persist Ditto chat retention cutoff")
            }
            subscription?.close()
            subscription = null
            evict(cutoff)
        } catch (error: Exception) {
            onError(error)
        } finally {
            // Even on cleanup failure, exclude stale history from the new subscription.
            if (subscription == null) subscription = subscribe(subscriptionQuery(cutoff))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        subscription?.close()
        subscription = null
    }

    companion object {
        const val INTERVAL_MS = DittoChatDocument.TTL_MILLIS
        private const val CUTOFF_KEY = "chat-retention-cutoff-v1"

        fun expiredQuery(cutoff: Long) =
            "o <= $cutoff || b <= ${cutoff - DittoChatDocument.TTL_MILLIS}"

        fun subscriptionQuery(cutoff: Long) =
            "SELECT * FROM chat WHERE (o IS MISSING OR o IS NULL OR o > $cutoff) " +
                "AND b > ${cutoff - DittoChatDocument.TTL_MILLIS}"
    }
}
