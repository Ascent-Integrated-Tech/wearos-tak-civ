package com.aitech.weartak_civ.cep.model.payloads
import com.aitech.weartak_civ.cep.model.CepPayload

data class HrvPayload(val rmssd_ms: Double? = null, val sdnn_ms: Double? = null, val ts: Long = System.currentTimeMillis()) : CepPayload


