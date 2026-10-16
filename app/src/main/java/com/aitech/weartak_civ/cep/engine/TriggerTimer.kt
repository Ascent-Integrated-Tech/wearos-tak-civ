package com.aitech.weartak_civ.cep.engine

data class TickResult(val warnFired: Boolean, val mainFired: Boolean)

class TriggerTimer(
    private val holdDownSec: Long
) {
    // Phase 1: wall-clock sustain — stores the ms when the condition first became true.
    // Replaces the tick-count `activeFor` field so sustain is rate-independent.
    private var conditionTrueStartMs: Long? = null
    private var lastFireAt: Long? = null
    private var warnFiredThisEpisode = false

    /**
     * Returns whole seconds elapsed since the condition most recently became true.
     *
     * This is observational only and does not mutate timer state.
     *
     * @param now Current epoch time in milliseconds.
     * @return Elapsed whole seconds while condition is continuously true, or 0 when false.
     */
    fun elapsedSec(now: Long = System.currentTimeMillis()): Long =
        conditionTrueStartMs?.let { (now - it) / 1000L } ?: 0L

    /**
     * @param effectiveSustainSec Sustain duration in real wall-clock seconds (rate-independent).
     * @param sustainWarnSec Optional warn threshold in seconds; must be in [1, effectiveSustainSec).
     */
    fun tick(isTrue: Boolean, now: Long, effectiveSustainSec: Long, sustainWarnSec: Long? = null): TickResult {
        if (isTrue) {
            if (conditionTrueStartMs == null) conditionTrueStartMs = now
        } else {
            conditionTrueStartMs = null
            warnFiredThisEpisode = false
        }

        val elapsedSec = conditionTrueStartMs?.let { (now - it) / 1000L } ?: 0L
        val mature = isTrue && elapsedSec >= effectiveSustainSec
        val cooldown = lastFireAt?.let { (now - it) >= holdDownSec * 1000 } ?: true
        val mainFired = mature && cooldown

        val warnSec = sustainWarnSec
        var warnFired = false
        if (warnSec != null && warnSec in 1 until effectiveSustainSec && elapsedSec >= warnSec && !warnFiredThisEpisode) {
            warnFired = true
        }

        return TickResult(warnFired = warnFired, mainFired = mainFired)
    }

    fun consumeWarn() {
        warnFiredThisEpisode = true
    }

    fun consumeMain(now: Long = System.currentTimeMillis()) {
        lastFireAt = now
    }

    fun reset() {
        conditionTrueStartMs = null
        lastFireAt = null
        warnFiredThisEpisode = false
    }
}
