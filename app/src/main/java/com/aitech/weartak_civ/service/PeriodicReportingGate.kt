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

    fun reported(nowElapsedNanos: Long) {
        lastReportedNanos = nowElapsedNanos
    }
}
