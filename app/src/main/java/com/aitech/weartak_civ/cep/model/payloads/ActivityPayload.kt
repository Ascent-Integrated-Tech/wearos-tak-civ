package com.aitech.weartak_civ.cep.model.payloads
import com.aitech.weartak_civ.cep.model.CepPayload

data class ActivityPayload(
    val state: String,
    val confidence: Int = 100,
    val ts: Long = System.currentTimeMillis(),
) : CepPayload


