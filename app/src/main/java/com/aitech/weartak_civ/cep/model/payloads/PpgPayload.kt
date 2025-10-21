package com.aitech.weartak_civ.cep.model.payloads

import com.aitech.weartak_civ.cep.model.CepPayload

data class PpgPayload(
    val amplitude: Float,            // perfusion amplitude
    val quality: Float? = null,      // optional signal quality (0–1)
    val wavelength: String? = null   // "red", "ir", "green", etc.
) : CepPayload


