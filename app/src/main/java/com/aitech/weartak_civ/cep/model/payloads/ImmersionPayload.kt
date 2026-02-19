package com.aitech.weartak_civ.cep.model.payloads
import com.aitech.weartak_civ.cep.model.CepPayload

data class ImmersionPayload(val wet_flag: Boolean = false, val immersion_flag: Boolean = false, val ts: Long = System.currentTimeMillis()) : CepPayload


