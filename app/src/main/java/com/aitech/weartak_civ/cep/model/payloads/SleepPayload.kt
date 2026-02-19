package com.aitech.weartak_civ.cep.model.payloads
import com.aitech.weartak_civ.cep.model.CepPayload

data class SleepPayload(val is_sleeping: Boolean, val ts: Long = System.currentTimeMillis()) : CepPayload


