package com.aitech.weartak_civ.cep.metric

/**
 * Development display policy only. These values have no CEP or alert authority.
 * They remain replaceable after replay review without changing the model state.
 */
object BaselineV2RestingHrDisplayPolicy {
    val runtimeConfig = BaselineV2RestingHrRuntimeConfig(
        model = RestingHrModelConfig(
            minimumScaleBpm = 2.0,
            expectedLowerMultiplier = 1.0,
            expectedUpperMultiplier = 1.0,
            lowWarningMultiplier = 2.0,
            lowAlertMultiplier = 3.0,
            highWarningMultiplier = 2.0,
            highAlertMultiplier = 3.0
        ),
        absoluteLowEmergencyBpm = 30.0,
        absoluteHighEmergencyBpm = 220.0
    )
}
