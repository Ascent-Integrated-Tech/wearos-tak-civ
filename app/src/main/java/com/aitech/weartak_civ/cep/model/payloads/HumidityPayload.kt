package com.aitech.weartak_civ.cep.model.payloads

import com.aitech.weartak_civ.cep.model.CepPayload

data class HumidityPayload(
    val relativePercent: Float       // 0–100 %
) : CepPayload


