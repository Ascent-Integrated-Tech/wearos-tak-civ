package com.aitech.weartak_civ.cep.model.payloads
import com.aitech.weartak_civ.cep.model.CepPayload

data class HeartRatePayload(val bpm: Double, val ts: Long = System.currentTimeMillis()) : CepPayload


