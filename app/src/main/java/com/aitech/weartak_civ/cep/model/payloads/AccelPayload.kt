package com.aitech.weartak_civ.cep.model.payloads
import com.aitech.weartak_civ.cep.model.CepPayload

data class AccelPayload(val magnitude_g: Double, val cadence_spm: Double? = null, val ts: Long = System.currentTimeMillis()) : CepPayload


