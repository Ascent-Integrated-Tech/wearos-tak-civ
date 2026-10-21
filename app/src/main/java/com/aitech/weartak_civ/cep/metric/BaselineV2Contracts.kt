package com.aitech.weartak_civ.cep.metric

import kotlin.math.exp
import kotlin.math.ln

/** Immutable identity for one physiological definition. Incompatible versions must not mix state. */
data class MetricDefinitionVersion(
    val metricId: String,
    val version: String,
    val windowSec: Long?,
    val artifactPolicy: String,
    val normalization: String,
    val transform: String,
    val contextKey: String,
    val emissionIdentity: String
)

/** The four times needed to replay asynchronous sensor observations correctly. */
data class ObservationTime(
    val observationStartMs: Long,
    val observationEndMs: Long,
    val representativeTimeMs: Long,
    val publishedAtMs: Long
) {
    init {
        require(observationStartMs <= observationEndMs) {
            "Observation start must not be after observation end"
        }
        require(representativeTimeMs in observationStartMs..observationEndMs) {
            "Representative time must be inside the observation interval"
        }
    }
}

enum class BaselineV2QualityState {
    VALID,
    INVALID,
    HELD,
    STALE,
    REJECTED
}

data class QualityAssessment(
    val state: BaselineV2QualityState,
    val score: Double,
    val rejectionReason: String? = null
) {
    init {
        require(score in 0.0..1.0) { "Quality score must be between 0 and 1" }
        require(state == BaselineV2QualityState.REJECTED || rejectionReason == null) {
            "Only rejected observations may carry a rejection reason"
        }
    }
}

data class ConfidenceBreakdown(
    val sampleConfidence: Double,
    val coverageConfidence: Double,
    val contextConfidence: Double,
    val modelConfidence: Double,
    val ageConfidence: Double,
    val weights: ConfidenceWeights = ConfidenceWeights()
) {
    val aggregate: Double = weightedGeometricMean(
        values = listOf(
            sampleConfidence,
            coverageConfidence,
            contextConfidence,
            modelConfidence,
            ageConfidence
        ),
        weights = weights.asList()
    )

    fun passesHardGates(
        minimumSample: Double = 0.80,
        minimumContext: Double = 0.90,
        minimumAge: Double = 0.80
    ): Boolean = sampleConfidence >= minimumSample &&
        contextConfidence >= minimumContext &&
        ageConfidence >= minimumAge

    init {
        listOf(
            sampleConfidence,
            coverageConfidence,
            contextConfidence,
            modelConfidence,
            ageConfidence
        ).forEach { require(it in 0.0..1.0) { "Confidence must be between 0 and 1" } }
    }
}

data class ConfidenceWeights(
    val sample: Double = 1.0,
    val coverage: Double = 1.0,
    val context: Double = 1.0,
    val model: Double = 1.0,
    val age: Double = 1.0
) {
    init {
        asList().forEach { require(it >= 0.0) { "Confidence weights cannot be negative" } }
        require(asList().sum() > 0.0) { "At least one confidence weight is required" }
    }

    fun asList(): List<Double> = listOf(sample, coverage, context, model, age)
}

private fun weightedGeometricMean(values: List<Double>, weights: List<Double>): Double {
    require(values.size == weights.size) { "Values and weights must have equal size" }
    if (values.zip(weights).any { (value, weight) -> weight > 0.0 && value <= 0.0 }) return 0.0
    val totalWeight = weights.sum()
    return exp(values.zip(weights).sumOf { (value, weight) -> weight * ln(value) } / totalWeight)
}

data class ContextInputSnapshot(
    val activityState: String?,
    val sleeping: Boolean,
    val onWrist: Boolean,
    val motionScore: Double?,
    val qualityScore: Double?,
    val timestampMs: Long
)

enum class BaselineV2LifecycleState {
    NEW,
    COLLECTING,
    CENTRE_READY,
    DISTRIBUTION_READY,
    MODEL_ESTABLISHED,
    DISPLAY_READY,
    FROZEN,
    STALE,
    INVALIDATED,
    RESET
}

enum class BaselineV2Strategy {
    DIST_ROBUST,
    DIST_LOG,
    DIST_VARIABILITY,
    DIST_CONTEXT,
    EMA_PERCENTILE,
    DIST_RELATIVE
}

/** Required source metadata is typed at the contract boundary. */
sealed interface TypedSourceMetadata {
    data class HeartRate(val activityState: String?, val still: Boolean) : TypedSourceMetadata
    data class Hrv(val validNnCount: Int, val rejectedNnCount: Int, val windowSec: Long) : TypedSourceMetadata
    data class Spo2(val motionRejected: Boolean, val family: String, val altitudeBucket: Int?) : TypedSourceMetadata
    data class Ppg(val wavelength: String, val contactScore: Double?, val waveformQuality: Double?) : TypedSourceMetadata
    data class SkinTemperature(val sensorLocation: String?, val ambientCelsius: Double?) : TypedSourceMetadata
    data class Activity(val state: String, val dwellSec: Long) : TypedSourceMetadata
}

data class QualifiedMetricObservation(
    val metricId: String,
    val definition: MetricDefinitionVersion,
    val value: Double,
    val time: ObservationTime,
    val context: MetricActivityContext,
    val quality: QualityAssessment,
    val sourceMetadata: TypedSourceMetadata
)

data class InformationalCandidateBoundaries(
    val lowWarning: Double? = null,
    val lowAlert: Double? = null,
    val highWarning: Double? = null,
    val highAlert: Double? = null,
    val source: String = "MODEL_ONLY"
)

data class BaselineV2ModelEstimate(
    val metricId: String,
    val definition: MetricDefinitionVersion,
    val strategy: BaselineV2Strategy,
    val expectedValue: Double?,
    val lowerExpectedBound: Double?,
    val upperExpectedBound: Double?,
    val lowerScale: Double?,
    val upperScale: Double?,
    val lifecycle: BaselineV2LifecycleState,
    val confidence: ConfidenceBreakdown,
    val sampleCount: Long,
    val coveredSeconds: Long,
    val activeDays: Int,
    val temporalDiversity: Int,
    val contextDiversity: Int
)

data class BaselineV2ObservationAssessment(
    val metricId: String,
    val observedValue: Double?,
    val time: ObservationTime?,
    val context: MetricActivityContext,
    val quality: QualityAssessment,
    val signedDeviation: Double?,
    val lowerTailDeviation: Double?,
    val upperTailDeviation: Double?,
    val normalizedLowerTailDeviation: Double?,
    val normalizedUpperTailDeviation: Double?,
    val missingReason: String? = null
)

data class BaselineV2MetricSnapshot(
    val model: BaselineV2ModelEstimate,
    val observation: BaselineV2ObservationAssessment,
    val candidateBoundaries: InformationalCandidateBoundaries,
    val generation: Long,
    val modelVersion: String,
    val thresholdFormulaVersion: String,
    val dependencyVersions: Map<String, String> = emptyMap()
)

/** V2 candidate policy. Outputs are informational until future CEP authorization. */
interface BaselineV2MetricThresholdPolicy {
    fun evaluate(model: BaselineV2ModelEstimate): InformationalCandidateBoundaries
}
