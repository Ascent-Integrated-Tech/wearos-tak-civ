package com.aitech.weartak_civ.cep.model.payloads
import com.aitech.weartak_civ.cep.model.CepPayload

data class OffBodyPayload(val on_wrist: Int, val ts: Long = System.currentTimeMillis()) : CepPayload


