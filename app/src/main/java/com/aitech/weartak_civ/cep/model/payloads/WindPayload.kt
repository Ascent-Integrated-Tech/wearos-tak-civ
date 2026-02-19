package com.aitech.weartak_civ.cep.model.payloads

import com.aitech.weartak_civ.cep.model.CepPayload

data class WindPayload(
    val speedMps: Float? = null,     // meters per second
    val directionDeg: Float? = null  // optional, 0–360
) : CepPayload


