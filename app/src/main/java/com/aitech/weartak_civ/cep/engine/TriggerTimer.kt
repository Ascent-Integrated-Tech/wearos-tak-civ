package com.aitech.weartak_civ.cep.engine

class TriggerTimer(
    private val holdDownSec: Long
) {
    private var activeFor = 0L
    private var lastFireAt = 0L

    /**
     * @param effectiveSustainSec Sustain duration in seconds (1 Hz tick => activeFor counts seconds).
     * Maturity uses this value; timer state (activeFor, lastFireAt) is preserved across override changes.
     */
    fun tick(isTrue: Boolean, now: Long, effectiveSustainSec: Long): Boolean {
        if (isTrue) activeFor++ else activeFor = 0
        val mature = activeFor >= effectiveSustainSec
        val cooldown = (now - lastFireAt) >= holdDownSec * 1000
        val fire = mature && cooldown
        if (fire) lastFireAt = now
        return fire
    }
}


