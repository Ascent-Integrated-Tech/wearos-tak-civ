package com.aitech.weartak_civ.cep.model.payloads

import com.aitech.weartak_civ.cep.model.CepPayload

data class LightPayload(
    val lux: Float,                  // ambient light
    val covered: Boolean? = null     // garment / ALS coverage flag
) : CepPayload


