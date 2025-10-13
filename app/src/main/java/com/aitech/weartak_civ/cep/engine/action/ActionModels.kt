package com.aitech.weartak_civ.cep.engine.action

enum class Severity { INFO, WARN, ALERT, EMERGENCY }

data class ActionDef(
    val type: String,
    val severity: Severity = Severity.INFO,
    val message: String = "",
    val tags: List<String> = emptyList()
)

data class TriggerContext(
    val ruleId: String,
    val action: ActionDef,
    val snapshot: Map<String, Any?>,
    val ts: Long = System.currentTimeMillis()
)


