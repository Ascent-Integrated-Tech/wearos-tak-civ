package com.aitech.weartak_civ.service

class PeriodicReportingGate {
    private var lastReportedNanos: Long? = null

    fun isDue(nowElapsedNanos: Long, intervalSeconds: Int): Boolean {
        require(intervalSeconds > 0)
        val last = lastReportedNanos ?: return true
        return nowElapsedNanos >= last &&
            nowElapsedNanos - last >= intervalSeconds * 1_000_000_000L
    }

    fun reset() { lastReportedNanos = null }

    fun delayMillis(nowElapsedNanos: Long, intervalSeconds: Int): Long {
        require(intervalSeconds > 0)
        val last = lastReportedNanos ?: return 1_000L
        val remainingNanos = intervalSeconds * 1_000_000_000L - (nowElapsedNanos - last)
        return ((remainingNanos + 999_999L) / 1_000_000L).coerceIn(1_000L, intervalSeconds * 1_000L)
    }

    fun reported(nowElapsedNanos: Long) {
        lastReportedNanos = nowElapsedNanos
    }
}
