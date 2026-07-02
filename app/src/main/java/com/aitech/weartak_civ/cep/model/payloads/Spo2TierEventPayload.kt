package com.aitech.weartak_civ.cep.model.payloads

import com.aitech.weartak_civ.cep.model.CepPayload

enum class Spo2Tier { NONE, WARN, ALERT, EMERGENCY }

enum class Spo2EdgeState { ENTER, HELD, CLEAR }

data class Spo2TierEventPayload(
    val tier: Spo2Tier,
    val state: Spo2EdgeState,
    val pct: Double,
    val ts: Long,
    val reason: String? = null,
) : CepPayload
