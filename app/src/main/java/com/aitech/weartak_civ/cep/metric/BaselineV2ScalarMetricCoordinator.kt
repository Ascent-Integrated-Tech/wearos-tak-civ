package com.aitech.weartak_civ.cep.metric

import kotlin.math.exp
import kotlin.math.ln

data class BaselineV2ScalarMetricConfig(
    val centreSamples: Int,
    val distributionSamples: Int,
    val minimumActiveDays: Int,
    val minimumCoveredSeconds: Long,
    val expectedLowerScale: Double = 1.0,
    val expectedUpperScale: Double = 1.0,
    val lowWarningScale: Double = 2.0,
    val lowAlertScale: Double = 3.0,
    val highWarningScale: Double = 2.0,
    val highAlertScale: Double = 3.0,
    val minimumScale: Double = 0.05,
    val logDomain: Boolean = false,
    val lowerQuantile: Double? = null
)

object BaselineV2MetricDefinitions {
    val RMSSD = MetricDefinitionVersion(
        metricId = "hrv.rmssd_ms.v2",
        version = "rmssd_v2_definition_v1",
        windowSec = 300L,
        artifactPolicy = "existing_hrv_payload_v1",
        normalization = "none",
        transform = "ln",
        contextKey = "AWAKE_REST_OR_SLEEP",
        emissionIdentity = "hrv_payload_timestamp"
    )
    val SDNN = MetricDefinitionVersion(
        metricId = "hrv.sdnn_ms.v2",
        version = "sdnn_v2_definition_v1",
        windowSec = 300L,
        artifactPolicy = "existing_hrv_payload_v1",
        normalization = "none",
        transform = "none",
        contextKey = "AWAKE_REST_OR_SLEEP",
        emissionIdentity = "sdnn_5min_ms"
    )
    val SPO2 = MetricDefinitionVersion(
        metricId = "spo2.pct.v2",
        version = "spo2_v2_definition_v1",
        windowSec = null,
        artifactPolicy = "sequencer_status_and_motion_v1",
        normalization = "family_altitude_profile",
        transform = "none",
        contextKey = "QUALIFIED_SPO2_READING",
        emissionIdentity = "spo2.pct"
    )
    val PPG = MetricDefinitionVersion(
        metricId = "ppg.amplitude.v2",
        version = "ppg_v2_definition_v1",
        windowSec = 20L,
        artifactPolicy = "quality_contact_wavelength_v1",
        normalization = "session_relative_pending",
        transform = "none",
        contextKey = "ON_WRIST_QUALIFIED_PPG",
        emissionIdentity = "ppg_amplitude_window"
    )
    val ACTIVE_HR = MetricDefinitionVersion(
        metricId = "hr.active_bpm.v2",
        version = "active_hr_v2_definition_v1",
        windowSec = null,
        artifactPolicy = "on_wrist_activity_context_v1",
        normalization = "context_grouped",
        transform = "none",
        contextKey = "ACTIVE_ACTIVITY_STATE",
        emissionIdentity = "heart_rate_timestamp"
    )
    val HRR = MetricDefinitionVersion(
        metricId = "hr.hrr_percent.v2",
        version = "hrr_v2_definition_v1",
        windowSec = null,
        artifactPolicy = "profile_versioned_hr_v1",
        normalization = "activity_response",
        transform = "none",
        contextKey = "ACTIVE_ACTIVITY_STATE",
        emissionIdentity = "heart_rate_timestamp"
    )
    val SKIN_TEMPERATURE = MetricDefinitionVersion(
        metricId = "skin_temp.peripheral_median_c.v2",
        version = "skin_temperature_v2_definition_v1",
        windowSec = null,
        artifactPolicy = "peripheral_sensor_finite_v1",
        normalization = "context_circadian_segment",
        transform = "none",
        contextKey = "ON_WRIST_CONTEXT",
        emissionIdentity = "skin_temp_timestamp"
    )
    val IBI = MetricDefinitionVersion(
        metricId = "hrv.ibi_ms.v2",
        version = "ibi_v2_definition_v1",
        windowSec = 300L,
        artifactPolicy = "status_zero_and_300_2000ms_v1",
        normalization = "none",
        transform = "none",
        contextKey = "AWAKE_REST_OR_SLEEP",
        emissionIdentity = "ibi_timestamp"
    )
}

/** Reusable scalar distribution model for non-segmented V2 metrics. */
class BaselineV2ScalarMetricCoordinator(
    private val snapshotStore: BaselineV2SnapshotStore,
    private val metricId: String,
    private val definition: MetricDefinitionVersion,
    private val strategy: BaselineV2Strategy,
    private val config: BaselineV2ScalarMetricConfig,
    private val absoluteLowGuardrail: Double? = null,
    private val absoluteHighGuardrail: Double? = null,
    private val checkpointStore: BaselineV2ScalarCheckpointStore? = null
) {
    private val restoredCheckpoint = checkpointStore?.restore(definition)
    private val observations = BaselineV2Retention
        .retainObservations(restoredCheckpoint?.observations ?: emptyList())
        .toMutableList()
    private var generation = restoredCheckpoint?.generation ?: 0L

    init {
        require(config.centreSamples > 0)
        require(config.distributionSamples >= config.centreSamples)
        require(config.minimumActiveDays >= 0)
        require(config.minimumCoveredSeconds >= 0L)
        require(config.lowerQuantile == null || config.lowerQuantile in 0.0..1.0)
    }

    @Synchronized
    fun ingest(observation: QualifiedMetricObservation): BaselineV2MetricSnapshot? {
        if (observation.metricId != metricId || observation.quality.state != BaselineV2QualityState.VALID) {
            return null
        }
        if (observations.lastOrNull()?.time?.representativeTimeMs == observation.time.representativeTimeMs) {
            return latestSnapshot()
        }
        observations += observation
        val retained = BaselineV2Retention.retainObservations(observations)
        observations.clear()
        observations.addAll(retained)
        return publish(observation)
    }

    @Synchronized
    fun latestSnapshot(): BaselineV2MetricSnapshot? =
        snapshotStore.snapshot(metricId)

    @Synchronized
    fun observationCount(): Int = observations.size

    @Synchronized
    fun reset() {
        observations.clear()
        generation = 0L
        checkpointStore?.clear()
    }

    private fun publish(latest: QualifiedMetricObservation): BaselineV2MetricSnapshot? {
        val rawValues = observations.map { it.value }
        val transformed = rawValues.map(::transform)
        val scaleEstimate = RobustStatistics.robustScale(transformed, config.minimumScale)
        val transformedCentre = scaleEstimate.centre ?: return null
        val scale = scaleEstimate.scale ?: return null
        val centre = inverseTransform(transformedCentre)
        val lowerBound = config.lowerQuantile?.let { quantile(transformed, it) }?.let(::inverseTransform)
            ?: inverseTransform(transformedCentre - config.expectedLowerScale * scale)
        val upperBound = inverseTransform(transformedCentre + config.expectedUpperScale * scale)
        val boundaries = InformationalCandidateBoundaries(
            lowWarning = inverseTransform(transformedCentre - config.lowWarningScale * scale),
            lowAlert = inverseTransform(transformedCentre - config.lowAlertScale * scale),
            highWarning = inverseTransform(transformedCentre + config.highWarningScale * scale),
            highAlert = inverseTransform(transformedCentre + config.highAlertScale * scale),
            source = "MODEL_ONLY"
        )
        val coveredSeconds = observations.sumOf { definition.windowSec ?: 0L }
        val activeDays = observations.map {
            Math.floorDiv(it.time.representativeTimeMs, MILLIS_PER_DAY)
        }.distinct().size
        val confidence = ConfidenceBreakdown(
            sampleConfidence = (observations.size.toDouble() / config.distributionSamples).coerceAtMost(1.0),
            coverageConfidence = if (config.minimumCoveredSeconds == 0L) 1.0 else
                (coveredSeconds.toDouble() / config.minimumCoveredSeconds).coerceAtMost(1.0),
            contextConfidence = latest.quality.score,
            modelConfidence = (observations.size.toDouble() / config.distributionSamples).coerceAtMost(1.0),
            ageConfidence = 1.0
        )
        val lifecycle = when {
            observations.size >= config.distributionSamples &&
                activeDays >= config.minimumActiveDays &&
                coveredSeconds >= config.minimumCoveredSeconds &&
                confidence.passesHardGates() && confidence.aggregate >= 0.80 ->
                BaselineV2LifecycleState.MODEL_ESTABLISHED
            observations.size >= config.distributionSamples -> BaselineV2LifecycleState.DISTRIBUTION_READY
            observations.size >= config.centreSamples -> BaselineV2LifecycleState.CENTRE_READY
            else -> BaselineV2LifecycleState.COLLECTING
        }
        val bounded = validateBoundaries(boundaries, centre)
        generation = maxOf(generation + 1L, (snapshotStore.current()?.generation ?: 0L) + 1L)
        val estimate = BaselineV2ModelEstimate(
            metricId = metricId,
            definition = definition,
            strategy = strategy,
            expectedValue = centre,
            lowerExpectedBound = lowerBound,
            upperExpectedBound = upperBound,
            lowerScale = scale,
            upperScale = scale,
            lifecycle = lifecycle,
            confidence = confidence,
            sampleCount = observations.size.toLong(),
            coveredSeconds = coveredSeconds,
            activeDays = activeDays,
            temporalDiversity = activeDays,
            contextDiversity = observations.map { it.context }.distinct().size
        )
        val observed = latest.value
        val lowerDeviation = (lowerBound - observed).coerceAtLeast(0.0)
        val upperDeviation = (observed - upperBound).coerceAtLeast(0.0)
        val snapshot = BaselineV2MetricSnapshot(
            model = estimate,
            observation = BaselineV2ObservationAssessment(
                metricId = metricId,
                observedValue = observed,
                time = latest.time,
                context = latest.context,
                quality = latest.quality,
                signedDeviation = observed - centre,
                lowerTailDeviation = lowerDeviation,
                upperTailDeviation = upperDeviation,
                normalizedLowerTailDeviation = lowerDeviation / scale,
                normalizedUpperTailDeviation = upperDeviation / scale
            ),
            candidateBoundaries = bounded,
            generation = generation,
            modelVersion = "${metricId}_v2_model_1",
            thresholdFormulaVersion = "${metricId}_candidate_formula_1"
        )
        checkpointStore?.commit(generation, definition, observations)
        snapshotStore.publish(
            BaselineV2SnapshotBatch(
                generation = generation,
                publishedAtMs = latest.time.publishedAtMs,
                snapshots = mapOf(metricId to snapshot)
            )
        )
        return snapshot
    }

    private fun transform(value: Double): Double = if (config.logDomain) ln(value) else value

    private fun inverseTransform(value: Double): Double = if (config.logDomain) exp(value) else value

    private fun quantile(values: List<Double>, probability: Double): Double {
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.single()
        val index = probability * (sorted.lastIndex)
        val lower = index.toInt()
        val upper = index.toInt().coerceAtMost(sorted.lastIndex)
        val fraction = index - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }

    private fun validateBoundaries(
        boundaries: InformationalCandidateBoundaries,
        centre: Double
    ): InformationalCandidateBoundaries = runCatching {
        if (absoluteLowGuardrail != null) require(absoluteLowGuardrail < boundaries.lowAlert!!)
        require(boundaries.lowAlert!! < boundaries.lowWarning!!)
        require(boundaries.lowWarning < centre)
        require(centre < boundaries.highWarning!!)
        require(boundaries.highWarning < boundaries.highAlert!!)
        if (absoluteHighGuardrail != null) require(boundaries.highAlert < absoluteHighGuardrail)
        boundaries
    }.getOrDefault(InformationalCandidateBoundaries())

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
