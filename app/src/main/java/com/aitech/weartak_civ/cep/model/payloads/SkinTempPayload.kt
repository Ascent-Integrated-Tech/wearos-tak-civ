package com.aitech.weartak_civ.cep.model.payloads
import com.aitech.weartak_civ.cep.model.CepPayload

data class SkinTempPayload(val c_peripheral: List<Double>, val labels: List<String>? = null, val ts: Long = System.currentTimeMillis()) : CepPayload


