package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.engine.FeatureStore

/** Read-only feature input used by V2; it never writes back to FeatureStore. */
object BaselineV2FeatureAdapter {
    fun restingHrInput(
        bpm: Double,
        valueTimestampMs: Long,
        context: ContextInputSnapshot,
        quality: QualityAssessment
    ): RestingHrInput? {
        val activity = context.activityState ?: return null
        return RestingHrInput(
            bpm = bpm,
            timestampMs = valueTimestampMs,
            awake = !context.sleeping,
            onWrist = context.onWrist,
            still = activity == "STILL",
            quality = quality
        )
    }

    fun restingHrInput(
        featureStore: FeatureStore,
        qualityProvider: RestingHrQualityProvider,
        nowMs: Long
    ): RestingHrInput? {
        val bpm = featureStore.latestAsDouble("selected.metrics.hr.current_bpm")
            ?: featureStore.latestAsDouble("features.hr.current_bpm")
            ?: return null
        val valueTimestampMs = featureStore.timestampMs("selected.metrics.hr.current_bpm")
            ?: featureStore.timestampMs("features.hr.current_bpm")
            ?: return null
        val context = ContextInputSnapshot(
            activityState = featureStore.str("activity.state"),
            sleeping = featureStore.bool("sleep.is_sleeping") ?: return null,
            onWrist = featureStore.latestAsDouble("offbody.on_wrist")?.let { it >= 0.5 }
                ?: return null,
            motionScore = null,
            qualityScore = null,
            timestampMs = nowMs
        )
        val quality = qualityProvider.assess(featureStore, nowMs) ?: return null
        return restingHrInput(bpm, valueTimestampMs, context, quality)
    }
}
