package com.aitech.weartak_civ.cep.engine

data class TickResult(val warnFired: Boolean, val mainFired: Boolean)

class TriggerTimer(
    private val holdDownSec: Long
) {
    private var activeFor = 0L
    private var lastFireAt = 0L
    private var warnFiredThisEpisode = false

    /**
     * @param effectiveSustainSec Sustain duration in seconds (1 Hz tick => activeFor counts seconds).
     * @param sustainWarnSec Optional warn threshold; must be in [1, effectiveSustainSec) to take effect.
     */
    fun tick(isTrue: Boolean, now: Long, effectiveSustainSec: Long, sustainWarnSec: Long? = null): TickResult {
        if (isTrue) {
            activeFor++
        } else {
            activeFor = 0
            warnFiredThisEpisode = false
        }
        val mature = activeFor >= effectiveSustainSec
        val cooldown = (now - lastFireAt) >= holdDownSec * 1000
        val mainFired = mature && cooldown
        if (mainFired) lastFireAt = now

        val warnSec = sustainWarnSec
        var warnFired = false
        if (warnSec != null && warnSec in 1 until effectiveSustainSec && activeFor >= warnSec && !warnFiredThisEpisode) {
            warnFiredThisEpisode = true
            warnFired = true
        }

        return TickResult(warnFired = warnFired, mainFired = mainFired)
    }
}


