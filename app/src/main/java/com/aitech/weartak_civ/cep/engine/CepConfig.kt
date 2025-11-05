package com.aitech.weartak_civ.cep.engine

data class CepConfig(
    val tickHz: Int,
    val features: List<FeatureDef>,
    val rules: List<RuleDef>
)

sealed interface FeatureDef {
    val id: String
    data class Window(
        override val id: String,
        val source: String,
        val op: String,
        val windowSec: Int
    ): FeatureDef
    data class MultiWindow(
        override val id: String,
        val source: String,
        val multiPolicy: String, // "min", "max"
        val op: String,
        val windowSec: Int
    ): FeatureDef
    data class SlopeMulti(
        override val id: String,
        val source: String,
        val multiPolicy: String, // "min_channel"
        val windowSec: Int,
        val unitsPerSec: String // "per_second" | "per_minute"
    ): FeatureDef
    data class Delta(
        override val id: String,
        val source: String,
        val horizonSec: Int
    ): FeatureDef
    data class Derived(
        override val id: String,
        val expr: org.json.JSONObject
    ): FeatureDef
}

data class RuleDef(
    val id: String,
    val description: String?,
    val condition: org.json.JSONObject,
    val sustainedForSec: Long,
    val holdDownSec: Long,
    val action: RuleAction,
    val snapshotKeys: List<String> = emptyList()
)

data class RuleAction(
    val type: String,
    val severity: String,
    val message: String,
    val tags: List<String> = emptyList(),
    val category: String? = null,     // e.g., "CAT_HR_EMERGENCY"
    val isTak: Boolean = false,
    val isLocal: Boolean = false,
    val priorityFrom: String? = "severity", // "severity" or null
    val priority: Int? = null,        // explicit fallback if not deriving
    val sendSms: Boolean = false,
    val timeoutSec: Long? = null,
    val uid: String? = ""
)


