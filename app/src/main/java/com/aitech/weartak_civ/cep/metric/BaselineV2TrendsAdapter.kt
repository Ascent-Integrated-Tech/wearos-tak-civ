package com.aitech.weartak_civ.cep.metric

/** UI-neutral representation of one V2 metric for the Baseline Trends screen. */
data class BaselineV2TrendItem(
    val metricId: String,
    val observedValue: Double?,
    val expectedValue: Double?,
    val lowerExpectedBound: Double?,
    val upperExpectedBound: Double?,
    val candidateBoundaries: InformationalCandidateBoundaries,
    val lifecycle: BaselineV2LifecycleState,
    val aggregateConfidence: Double,
    val confidence: ConfidenceBreakdown,
    val quality: QualityAssessment,
    val sampleCount: Long,
    val coveredSeconds: Long,
    val activeDays: Int,
    val generation: Long,
    val publishedAtMs: Long,
    val modelVersion: String,
    val definitionVersion: String,
    val thresholdFormulaVersion: String
)

object BaselineV2TrendsAdapter {
    fun toDisplayItems(batch: BaselineV2SnapshotBatch?): List<BaselineV2TrendItem> =
        batch?.snapshots?.values
            ?.map { it.toDisplayItem() }
            ?.sortedBy { it.metricId }
            .orEmpty()

    private fun BaselineV2MetricSnapshot.toDisplayItem(): BaselineV2TrendItem =
        BaselineV2TrendItem(
            metricId = model.metricId,
            observedValue = observation.observedValue,
            expectedValue = model.expectedValue,
            lowerExpectedBound = model.lowerExpectedBound,
            upperExpectedBound = model.upperExpectedBound,
            candidateBoundaries = candidateBoundaries,
            lifecycle = model.lifecycle,
            aggregateConfidence = model.confidence.aggregate,
            confidence = model.confidence,
            quality = observation.quality,
            sampleCount = model.sampleCount,
            coveredSeconds = model.coveredSeconds,
            activeDays = model.activeDays,
            generation = generation,
            publishedAtMs = observation.time?.publishedAtMs ?: 0L,
            modelVersion = modelVersion,
            definitionVersion = model.definition.version,
            thresholdFormulaVersion = thresholdFormulaVersion
        )
}
