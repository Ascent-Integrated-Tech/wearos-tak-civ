package com.aitech.weartak_civ.cep.model.payloads
import com.aitech.weartak_civ.cep.model.CepPayload

data class AmbientPayload(val temp_c: Double, val humidity_pct: Double? = null, val wind_mps: Double? = null, val ts: Long = System.currentTimeMillis()) : CepPayload


