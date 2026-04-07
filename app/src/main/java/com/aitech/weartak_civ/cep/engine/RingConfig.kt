package com.aitech.weartak_civ.cep.engine

/**
 * Sizing configuration for WindowStore ring buffers.
 *
 * [maxSensorHz] is the highest sample rate any sensor in the system may produce
 * (e.g. 4 for a 4 Hz PPG burst, 1 for heart-rate, 0.1 for SpO2).
 * Ring capacity = maxSeconds * maxSensorHz, so the ring always holds at least
 * maxSeconds of history regardless of how fast a particular sensor fires.
 *
 * Using the engine tick rate here (as was done previously) under-sizes rings for
 * sensors faster than the tick — a 4 Hz sensor with tickHz=1 would only retain
 * 15 minutes of history instead of the intended 60.
 */
data class RingConfig(
    val maxSecondsNumeric: Int = 3600,
    val maxSecondsArray: Int = 3600,
    /** Highest expected sensor sample rate across all sensor types (Hz). */
    val maxSensorHz: Int = 4
) {
    companion object {
        fun default() = RingConfig()
        fun constrained() = RingConfig(maxSecondsNumeric = 900, maxSecondsArray = 900, maxSensorHz = 4)
    }
}


