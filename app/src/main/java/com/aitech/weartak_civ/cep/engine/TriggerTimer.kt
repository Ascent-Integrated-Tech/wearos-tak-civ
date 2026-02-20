package com.aitech.weartak_civ.cep.engine

class TriggerTimer(
    private val sustainedForSec: Long,
    private val holdDownSec: Long
) {
    private var activeFor = 0L
    private var lastFireAt = 0L
    fun tick(isTrue: Boolean, now: Long): Boolean {
        if (isTrue) activeFor++ else activeFor = 0
        val mature = activeFor >= sustainedForSec
        val cooldown = (now - lastFireAt) >= holdDownSec * 1000
        val fire = mature && cooldown
        if (fire) lastFireAt = now
        return fire
    }
}


