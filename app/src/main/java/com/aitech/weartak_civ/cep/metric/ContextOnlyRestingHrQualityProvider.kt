package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.engine.FeatureStore

/**
 * Temporary watch-validation provider. It uses context and HR range only because
 * the current HeartRatePayload has no sensor-quality field. It must be replaced
 * before any production interpretation of signal quality.
 */
object ContextOnlyRestingHrQualityProvider : RestingHrQualityProvider {
    override fun assess(
        featureStore: FeatureStore,
        nowMs: Long
    ): QualityAssessment? {
        val bpm = featureStore.latestAsDouble("selected.metrics.hr.current_bpm")
            ?: featureStore.latestAsDouble("features.hr.current_bpm")
            ?: return null
        val onWrist = featureStore.latestAsDouble("offbody.on_wrist")?.let { it >= 0.5 } ?: return null
        val sleeping = featureStore.bool("sleep.is_sleeping") ?: return null
        val activity = featureStore.str("activity.state") ?: return null
        val valid = bpm in 20.0..240.0 && onWrist && !sleeping && activity == "STILL"
        return if (valid) {
            QualityAssessment(BaselineV2QualityState.VALID, score = 0.75)
        } else {
            QualityAssessment(
                state = BaselineV2QualityState.REJECTED,
                score = 0.0,
                rejectionReason = "context_only_quality_gate"
            )
        }
    }
}
