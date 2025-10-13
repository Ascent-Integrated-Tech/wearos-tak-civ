package com.aitech.weartak_civ.cep.model.payloads
import com.aitech.weartak_civ.cep.model.CepPayload

data class OffBodyPayload(val on_wrist: Boolean, val ts: Long = System.currentTimeMillis()) : CepPayload


