package com.aitech.weartak_civ.data.model

data class PressureReading(
    val pressure: Float,    // The pressure reading in hPa (normal air = ~1013 hPa)
    val timestamp: Long     // When we took this reading (in milliseconds)
)