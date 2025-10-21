package com.aitech.weartak_civ.cep.model

interface CepPayload

data class NumericSample(
    val ns: String,
    val key: String,
    val value: Double
) : CepPayload

data class BoolSample(
    val ns: String,
    val key: String,
    val value: Boolean
) : CepPayload