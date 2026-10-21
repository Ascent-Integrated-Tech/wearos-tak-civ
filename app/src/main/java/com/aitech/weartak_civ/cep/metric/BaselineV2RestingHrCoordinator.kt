package com.aitech.weartak_civ.cep.metric

data class RestingHrModelRequirements(
    val centreSegments: Int = 10,
    val distributionSegments: Int = 30,
    val minimumActiveDays: Int = 3,
    val minimumCoveredSeconds: Long = 21_600L
)

/**
 * Pure V2 resting-HR coordinator. It accepts already-qualified feature-derived
 * inputs and publishes only V2 snapshots for Trends.
 */
class BaselineV2RestingHrCoordinator(
    private val snapshotStore: BaselineV2SnapshotStore,
    private val segmentBuilder: RestingHrSegmentBuilder,
    private val model: RestingHrModel,
    private val requirements: RestingHrModelRequirements = RestingHrModelRequirements(),
    private val definition: MetricDefinitionVersion = DEFAULT_DEFINITION,
    private val absoluteLowEmergencyBpm: Double,
    private val absoluteHighEmergencyBpm: Double,
    private val checkpointStore: BaselineV2RestingHrCheckpointStore? = null,
    private val checkpointEverySegments: Int = 5
) {
    private val restoredCheckpoint = checkpointStore?.restoreSegments()
    private val segments = BaselineV2Retention
        .retainSegments(restoredCheckpoint?.segments ?: emptyList())
        .toMutableList()
    private var generation = restoredCheckpoint?.generation ?: 0L
    private var latestSnapshot: BaselineV2MetricSnapshot? = null

    init {
        require(checkpointEverySegments > 0) { "Checkpoint interval must be positive" }
    }

    @Synchronized
    fun ingest(input: RestingHrInput): List<RestingHrSegment> {
        val completed = segmentBuilder.ingest(input)
        if (completed.isNotEmpty()) {
            segments += completed
            val retained = BaselineV2Retention.retainSegments(segments)
            segments.clear()
            segments.addAll(retained)
            publishSnapshot(completed.last(), input.timestampMs)
        }
        return completed
    }

    @Synchronized
    fun finish(timestampMs: Long): List<RestingHrSegment> {
        val completed = segmentBuilder.finish()
        if (completed.isNotEmpty()) {
            segments += completed
            val retained = BaselineV2Retention.retainSegments(segments)
            segments.clear()
            segments.addAll(retained)
            publishSnapshot(completed.last(), timestampMs)
        }
        return completed
    }

    @Synchronized
    fun latestSnapshot(): BaselineV2MetricSnapshot? = latestSnapshot

    @Synchronized
    fun segmentCount(): Int = segments.size

    @Synchronized
    fun reset() {
        segmentBuilder.clear()
        segments.clear()
        generation = 0L
        latestSnapshot = null
        checkpointStore?.clear()
    }

    @Synchronized
    fun recalculate(publishedAtMs: Long = System.currentTimeMillis()): BaselineV2MetricSnapshot? {
        val latestSegment = segments.lastOrNull() ?: return null
        publishSnapshot(latestSegment, publishedAtMs)
        return latestSnapshot
    }

    private fun publishSnapshot(segment: RestingHrSegment, publishedAtMs: Long) {
        val estimate = model.estimate(segments.map { it.medianBpm }) ?: return
        val confidence = confidenceFor(segments, estimate.sampleCount)
        val lifecycle = lifecycleFor(estimate.sampleCount, confidence)
        val modelEstimate = BaselineV2ModelEstimate(
            metricId = definition.metricId,
            definition = definition,
            strategy = BaselineV2Strategy.DIST_ROBUST,
            expectedValue = estimate.centreBpm,
            lowerExpectedBound = estimate.lowerExpectedBoundBpm,
            upperExpectedBound = estimate.upperExpectedBoundBpm,
            lowerScale = estimate.lowerScaleBpm,
            upperScale = estimate.upperScaleBpm,
            lifecycle = lifecycle,
            confidence = confidence,
            sampleCount = estimate.sampleCount.toLong(),
            coveredSeconds = coverageSeconds(segments),
            activeDays = activeDays(segments),
            temporalDiversity = temporalDiversity(segments),
            contextDiversity = 1
        )
        val boundaries = runCatching {
            model.candidateBoundaries(estimate, absoluteLowEmergencyBpm, absoluteHighEmergencyBpm)
        }.getOrDefault(InformationalCandidateBoundaries())
        generation = maxOf(generation + 1L, (snapshotStore.current()?.generation ?: 0L) + 1L)
        latestSnapshot = BaselineV2MetricSnapshot(
            model = modelEstimate,
            observation = observationFor(segment, estimate, publishedAtMs),
            candidateBoundaries = boundaries,
            generation = generation,
            modelVersion = MODEL_VERSION,
            thresholdFormulaVersion = THRESHOLD_FORMULA_VERSION
        )
        val shouldCheckpoint = checkpointStore != null &&
            (segments.size % checkpointEverySegments == 0)
        if (shouldCheckpoint) {
            checkpointStore?.commit(generation, segments, latestSnapshot!!)
        }
        snapshotStore.publish(
            BaselineV2SnapshotBatch(
                generation = generation,
                publishedAtMs = publishedAtMs,
                snapshots = mapOf(definition.metricId to latestSnapshot!!)
            )
        )
    }

    private fun observationFor(
        segment: RestingHrSegment,
        estimate: RestingHrDistributionEstimate,
        publishedAtMs: Long
    ): BaselineV2ObservationAssessment {
        val observed = segment.medianBpm
        val lowerDeviation = (estimate.lowerExpectedBoundBpm - observed).coerceAtLeast(0.0)
        val upperDeviation = (observed - estimate.upperExpectedBoundBpm).coerceAtLeast(0.0)
        return BaselineV2ObservationAssessment(
            metricId = definition.metricId,
            observedValue = observed,
            time = ObservationTime(
                observationStartMs = segment.startMs,
                observationEndMs = segment.endMs,
                representativeTimeMs = segment.representativeTimeMs,
                publishedAtMs = publishedAtMs
            ),
            context = MetricActivityContext.RESTING,
            quality = QualityAssessment(BaselineV2QualityState.VALID, 1.0),
            signedDeviation = observed - estimate.centreBpm,
            lowerTailDeviation = lowerDeviation,
            upperTailDeviation = upperDeviation,
            normalizedLowerTailDeviation = lowerDeviation / estimate.lowerScaleBpm,
            normalizedUpperTailDeviation = upperDeviation / estimate.upperScaleBpm
        )
    }

    private fun confidenceFor(
        completed: List<RestingHrSegment>,
        sampleCount: Int
    ): ConfidenceBreakdown {
        val coverage = coverageSeconds(completed)
        return ConfidenceBreakdown(
            sampleConfidence = (sampleCount.toDouble() / requirements.distributionSegments).coerceAtMost(1.0),
            coverageConfidence = (coverage.toDouble() / requirements.minimumCoveredSeconds).coerceAtMost(1.0),
            contextConfidence = 1.0,
            modelConfidence = (sampleCount.toDouble() / requirements.distributionSegments).coerceAtMost(1.0),
            ageConfidence = 1.0
        )
    }

    private fun lifecycleFor(sampleCount: Int, confidence: ConfidenceBreakdown): BaselineV2LifecycleState = when {
        sampleCount >= requirements.distributionSegments &&
            activeDays(segments) >= requirements.minimumActiveDays &&
            coverageSeconds(segments) >= requirements.minimumCoveredSeconds &&
            confidence.passesHardGates() &&
            confidence.aggregate >= 0.80 -> BaselineV2LifecycleState.MODEL_ESTABLISHED
        sampleCount >= requirements.distributionSegments -> BaselineV2LifecycleState.DISTRIBUTION_READY
        sampleCount >= requirements.centreSegments -> BaselineV2LifecycleState.CENTRE_READY
        else -> BaselineV2LifecycleState.COLLECTING
    }

    private fun coverageSeconds(values: List<RestingHrSegment>): Long =
        values.sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) / 1_000L }

    private fun activeDays(values: List<RestingHrSegment>): Int =
        values.map { Math.floorDiv(it.representativeTimeMs, MILLIS_PER_DAY) }.distinct().size

    private fun temporalDiversity(values: List<RestingHrSegment>): Int =
        values.map { it.representativeTimeMs / MILLIS_PER_DAY }.distinct().size

    private companion object {
        const val MODEL_VERSION = "hr_rest_v2_model_1"
        const val THRESHOLD_FORMULA_VERSION = "hr_rest_candidate_formula_1"
        const val MILLIS_PER_DAY = 86_400_000L
        val DEFAULT_DEFINITION = MetricDefinitionVersion(
            metricId = "hr.resting_bpm.v2",
            version = "hr_rest_definition_v1",
            windowSec = 600L,
            artifactPolicy = "awake_on_wrist_still_quality_v1",
            normalization = "none",
            transform = "none",
            contextKey = "AWAKE_REST",
            emissionIdentity = "segment_midpoint"
        )
    }
}
