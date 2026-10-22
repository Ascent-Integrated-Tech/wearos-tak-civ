package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.engine.FeatureStore

/** Quality must be supplied by the sensor/runtime integration; V2 does not guess it. */
fun interface RestingHrQualityProvider {
    fun assess(
        featureStore: FeatureStore,
        nowMs: Long,
        sourceKey: String
    ): QualityAssessment?
}

data class BaselineV2RestingHrRuntimeConfig(
    val model: RestingHrModelConfig,
    val absoluteLowEmergencyBpm: Double,
    val absoluteHighEmergencyBpm: Double,
    val requirements: RestingHrModelRequirements = RestingHrModelRequirements(),
    val checkpointEverySegments: Int = 5
)

object BaselineV2RestingHrRuntimeFactory {
    fun create(
        config: BaselineV2RestingHrRuntimeConfig,
        snapshotStore: BaselineV2SnapshotStore,
        checkpointStore: BaselineV2RestingHrCheckpointStore? = null
    ): BaselineV2RestingHrCoordinator = BaselineV2RestingHrCoordinator(
        snapshotStore = snapshotStore,
        segmentBuilder = RestingHrSegmentBuilder(),
        model = RestingHrModel(config.model),
        requirements = config.requirements,
        absoluteLowEmergencyBpm = config.absoluteLowEmergencyBpm,
        absoluteHighEmergencyBpm = config.absoluteHighEmergencyBpm,
        checkpointStore = checkpointStore,
        checkpointEverySegments = config.checkpointEverySegments
    )
}
