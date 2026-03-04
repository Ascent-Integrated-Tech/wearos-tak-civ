package com.aitech.weartak_civ.cep.engine.action

import com.aitech.weartak_civ.cep.engine.ExitCondition

enum class Severity { INFO, WARN, ALERT, EMERGENCY }

data class ActionDef(
    val type: String,
    val severity: Severity = Severity.INFO,
    val message: String = "",
    val tags: List<String> = emptyList(),
    val category: String? = null,          // TAK/alert category (e.g. CAT_HR_EMERGENCY)
    val isTak: Boolean = false,            // true → post CustomEmergencyEvent to TAK
    val isLocal: Boolean = false,          // true → trigger local on-watch feedback
    val priorityFrom: String? = "severity",// "severity" or null
    val priority: Int? = null,             // fallback explicit priority
    val sendSms: Boolean = false,          // optional SMS flag
    val timeoutSec: Long? = null,
    val uid: String? = "",
    val monitor: List<ExitCondition> = emptyList(),
    val exitLogic: String? = null,         // "AND" or "OR", default to "AND" if null,
    val exitSustainSec: Long? = null
)

data class TriggerContext(
    val ruleId: String,
    val action: ActionDef,
    val snapshot: Map<String, Any?>,
    val ts: Long = System.currentTimeMillis(),
    val isPreWarn: Boolean = false
)


