package com.aitech.weartak_civ.cep.model.payloads

import com.aitech.weartak_civ.cep.model.CepPayload

/**
 * Payload emitted by PressureService for each barometric pressure sample.
 *
 * @param hpa          Raw pressure reading in hectopascals.
 * @param accuracy     Sensor accuracy level (0-3, per SensorManager.SENSOR_STATUS_*).
 * @param sampleIndex  Monotonically increasing counter from PressureService; reset to 0
 *                     on service restart. Used by consumers to detect restarts/gaps.
 * @param sourceId     Identifies the physical sensor, default "baro_0".
 */
data class PressurePayload(
    val hpa: Float,
    val accuracy: Int,
    val sampleIndex: Long,
    val sourceId: String = "baro_0"
) : CepPayload
