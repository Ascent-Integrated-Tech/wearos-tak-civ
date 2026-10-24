package com.aitech.weartak_civ.service

/**
 * Backoff for TAK Server sessions that connect successfully and then drop quickly.
 *
 * Connection failures are capped by `numConnAttempts`, but that counter resets on every
 * successful handshake, so a connect-then-reset loop reconnected immediately and indefinitely.
 * Short sessions now back off exponentially; a session that stays up resets the streak.
 */
object TakReconnectBackoff {
    const val STABLE_SESSION_MS = 120_000L
    const val BASE_DELAY_MS = 5_000L
    const val MAX_DELAY_MS = 120_000L

    /** Returns the updated short-session streak for a session that lasted [sessionMs]. */
    fun nextStreak(sessionMs: Long, previousStreak: Int): Int =
        if (sessionMs >= STABLE_SESSION_MS) 0 else (previousStreak + 1).coerceAtMost(16)

    /** Delay before reconnecting; the first short drop reconnects immediately. */
    fun delayMs(streak: Int): Long {
        if (streak <= 1) return 0L
        val shift = (streak - 2).coerceAtMost(10)
        return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }
}
