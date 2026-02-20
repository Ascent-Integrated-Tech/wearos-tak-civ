package com.aitech.weartak_civ.cep.model.payloads

import com.aitech.weartak_civ.cep.model.CepPayload

data class GyroPayload(
    val x: Float,
    val y: Float,
    val z: Float,
    val rms: Float? = null           // optional RMS magnitude
) : CepPayload


