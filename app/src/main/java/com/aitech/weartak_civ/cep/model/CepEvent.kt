package com.aitech.weartak_civ.cep.model

data class CepEvent<out P : CepPayload>(
    val topic: String,
    val payload: P,
    val ts: Long = System.currentTimeMillis()
)


