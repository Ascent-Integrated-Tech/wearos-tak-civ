package com.aitech.weartak_civ.cep.engine

data class RingConfig(
    val maxSecondsNumeric: Int = 43200,
    val maxSecondsArray: Int = 43200
) {
    companion object {
        fun default() = RingConfig()
        fun constrained() = RingConfig(maxSecondsNumeric = 900, maxSecondsArray = 900)
    }
}


