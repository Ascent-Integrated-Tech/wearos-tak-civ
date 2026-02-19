package com.aitech.weartak_civ.cep.model.payloads
import com.aitech.weartak_civ.cep.model.CepPayload

data class Spo2Payload(val pct: Double, val ts: Long = System.currentTimeMillis()) : CepPayload


