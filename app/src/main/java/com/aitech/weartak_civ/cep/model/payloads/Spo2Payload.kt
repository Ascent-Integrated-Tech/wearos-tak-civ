package com.aitech.weartak_civ.cep.model.payloads
import com.aitech.weartak_civ.cep.model.CepPayload

data class Spo2Payload(
    val pct: Double,
    val ts: Long = System.currentTimeMillis(),
    val statusOk: Boolean = true,
    val motionOk: Boolean = true,
    val ppgRedSustainedMs: Long = 0L,
    val ppgAmplitude: Float? = null,
    val ppgQuality: Float? = null,
) : CepPayload


