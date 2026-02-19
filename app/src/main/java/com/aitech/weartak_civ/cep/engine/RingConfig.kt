package com.aitech.weartak_civ.cep.engine

data class RingConfig(
    val maxSecondsNumeric: Int = 3600,
    val maxSecondsArray: Int = 3600
) {
    companion object {
        fun default() = RingConfig()
        fun constrained() = RingConfig(maxSecondsNumeric = 900, maxSecondsArray = 900)
    }
}


