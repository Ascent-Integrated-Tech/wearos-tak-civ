package com.aitech.weartak_civ.cep.model.payloads

import com.aitech.weartak_civ.cep.model.CepPayload

data class WaterPayload(
    val wet: Boolean,                // contact / wetness flag
    val temperatureC: Float? = null  // optional water temp in °C
) : CepPayload


