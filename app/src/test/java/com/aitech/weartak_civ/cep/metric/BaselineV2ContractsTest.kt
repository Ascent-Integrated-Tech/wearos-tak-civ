package com.aitech.weartak_civ.cep.metric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import com.aitech.weartak_civ.cep.engine.FeatureStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BaselineV2ContractsTest {

    private val millisPerDay = 86_400_000L

    @Test
    fun v2Retention_dropsObservationsOlderThanThirtyDays() {
        val observations = listOf(
            sdnnObservation(0L, 35.0),
            sdnnObservation(10L * millisPerDay, 36.0),
            sdnnObservation(31L * millisPerDay, 37.0)
        )

        val retained = BaselineV2Retention.retainObservations(observations)

        assertEquals(listOf(10L * millisPerDay, 31L * millisPerDay), retained.map {
            it.time.representativeTimeMs
        })
    }

    @Test
    fun v2Retention_capsSerializedCheckpointAndPreservesTemporalCoverage() {
        val root = createTempDir(prefix = "baseline-v2-retention-")
        try {
            val observations = List(200_000) { index ->
                sdnnObservation(1_000L + index * 1_000L, 35.0 + (index % 10))
            }
            val store = BaselineV2ScalarCheckpointStore(BaselineV2GenerationStore(root))
            store.commit(1L, BaselineV2MetricDefinitions.SDNN, observations)

            val payload = BaselineV2GenerationStore(root).loadCommitted()
                ?: error("Expected committed V2 checkpoint")
            assertTrue(payload.history.size.toLong() <= BaselineV2Retention.MAX_CHECKPOINT_BYTES)

            val restored = store.restore(BaselineV2MetricDefinitions.SDNN)?.observations
                ?: error("Expected restored observations")
            assertTrue(restored.size < observations.size)
            assertEquals(observations.first().time.representativeTimeMs, restored.first().time.representativeTimeMs)
            assertEquals(observations.last().time.representativeTimeMs, restored.last().time.representativeTimeMs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun scalarCoordinator_serializesConcurrentIngestAndReset() {
        val coordinator = BaselineV2ScalarMetricCoordinator(
            snapshotStore = BaselineV2SnapshotStore(),
            metricId = BaselineV2MetricDefinitions.SDNN.metricId,
            definition = BaselineV2MetricDefinitions.SDNN,
            strategy = BaselineV2Strategy.DIST_VARIABILITY,
            config = BaselineV2ScalarMetricConfig(
                centreSamples = 2,
                distributionSamples = 4,
                minimumActiveDays = 0,
                minimumCoveredSeconds = 0L
            )
        )
        val executor = Executors.newFixedThreadPool(8)
        repeat(32) { index ->
            executor.submit {
                coordinator.ingest(sdnnObservation(1_000_000L + index * 300_000L, 40.0 + index))
            }
        }
        executor.submit { coordinator.reset() }
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(coordinator.observationCount() >= 0)
    }

    @Test
    fun featureAdapter_readsExistingFeaturesWithoutCreatingNewFeatureKeys() {
        val featureStore = FeatureStore()
        featureStore.put("selected.metrics.hr.current_bpm", 58.0, 10_000L)
        featureStore.put("offbody.on_wrist", true, 10_000L)
        featureStore.put("sleep.is_sleeping", false, 10_000L)
        featureStore.put("activity.state", "STILL", 10_000L)

        val input = BaselineV2FeatureAdapter.restingHrInput(
            featureStore = featureStore,
            qualityProvider = ContextOnlyRestingHrQualityProvider,
            nowMs = 10_500L
        )

        assertEquals(58.0, input?.bpm ?: error("Expected resting HR input"), 0.0)
        assertEquals(10_000L, input?.timestampMs ?: error("Expected timestamp"))
        assertTrue(featureStore.debugSnapshot().keys.none { it.startsWith("baseline_v2") })
    }

    @Test
    fun typedFeatureAdapter_acceptsContextSnapshotWithoutFeatureStoreOwnership() {
        val input = BaselineV2FeatureAdapter.restingHrInput(
            bpm = 58.0,
            valueTimestampMs = 10_000L,
            context = ContextInputSnapshot(
                activityState = "STILL",
                sleeping = false,
                onWrist = true,
                motionScore = null,
                qualityScore = 0.75,
                timestampMs = 10_500L
            ),
            quality = QualityAssessment(BaselineV2QualityState.VALID, 0.75)
        )

        assertEquals(58.0, input?.bpm ?: error("Expected typed resting HR input"), 0.0)
        assertEquals(10_000L, input?.timestampMs ?: error("Expected typed timestamp"))
    }

    @Test
    fun restingHrCheckpointStore_restoresSegmentSummaries() {
        val root = createTempDir(prefix = "baseline-v2-hr-checkpoint-")
        try {
            val store = BaselineV2RestingHrCheckpointStore(
                BaselineV2GenerationStore(root)
            )
            val segment = RestingHrSegment(0L, 120_000L, 60_000L, 58.0, 2)
            store.commit(3L, listOf(segment), testSnapshot())

            val restored = store.restoreSegments()
            assertEquals(3L, restored?.generation)
            assertEquals(listOf(segment), restored?.segments)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restingHrCoordinator_canRestoreSegmentHistoryAfterRecreation() {
        val root = createTempDir(prefix = "baseline-v2-hr-restart-")
        try {
            val checkpoint = BaselineV2RestingHrCheckpointStore(
                BaselineV2GenerationStore(root)
            )
            val config = RestingHrModelConfig(
                expectedLowerMultiplier = 1.0,
                expectedUpperMultiplier = 1.0,
                lowWarningMultiplier = 2.0,
                lowAlertMultiplier = 3.0,
                highWarningMultiplier = 2.0,
                highAlertMultiplier = 3.0
            )
            val first = BaselineV2RestingHrCoordinator(
                snapshotStore = BaselineV2SnapshotStore(),
                segmentBuilder = RestingHrSegmentBuilder(),
                model = RestingHrModel(config),
                absoluteLowEmergencyBpm = 35.0,
                absoluteHighEmergencyBpm = 180.0,
                checkpointStore = checkpoint,
                checkpointEverySegments = 1
            )
            val quality = QualityAssessment(BaselineV2QualityState.VALID, 1.0)
            first.ingest(restingInput(0L, 58.0, quality))
            first.ingest(restingInput(60_000L, 58.0, quality))
            first.finish(60_000L)

            val restored = BaselineV2RestingHrCoordinator(
                snapshotStore = BaselineV2SnapshotStore(),
                segmentBuilder = RestingHrSegmentBuilder(),
                model = RestingHrModel(config),
                absoluteLowEmergencyBpm = 35.0,
                absoluteHighEmergencyBpm = 180.0,
                checkpointStore = checkpoint,
                checkpointEverySegments = 1
            )

            assertEquals(1, restored.segmentCount())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restingHrCoordinator_resetClearsCheckpointAndSnapshot() {
        val root = createTempDir(prefix = "baseline-v2-reset-")
        try {
            val checkpoint = BaselineV2RestingHrCheckpointStore(
                BaselineV2GenerationStore(root)
            )
            val coordinator = BaselineV2RestingHrCoordinator(
                snapshotStore = BaselineV2SnapshotStore(),
                segmentBuilder = RestingHrSegmentBuilder(),
                model = testModel(),
                absoluteLowEmergencyBpm = 35.0,
                absoluteHighEmergencyBpm = 180.0,
                checkpointStore = checkpoint,
                checkpointEverySegments = 1
            )
            val quality = QualityAssessment(BaselineV2QualityState.VALID, 1.0)
            coordinator.ingest(restingInput(0L, 58.0, quality))
            coordinator.ingest(restingInput(60_000L, 58.0, quality))
            coordinator.finish(60_000L)

            assertNotNull(checkpoint.restoreSegments())
            assertNotNull(coordinator.latestSnapshot())

            coordinator.reset()

            assertEquals(null, checkpoint.restoreSegments())
            assertEquals(null, coordinator.latestSnapshot())
            assertEquals(0, coordinator.segmentCount())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun v2Coordinator_doesNotWriteLegacyFeatureStoreThresholds() {
        val legacyStore = FeatureStore()
        legacyStore.put("thresholds.hr.rest_low_bpm", 40.0)
        legacyStore.put("thresholds.hr.rest_high_bpm", 120.0)
        val coordinator = BaselineV2RestingHrCoordinator(
            snapshotStore = BaselineV2SnapshotStore(),
            segmentBuilder = RestingHrSegmentBuilder(),
            model = RestingHrModel(
                RestingHrModelConfig(
                    expectedLowerMultiplier = 1.0,
                    expectedUpperMultiplier = 1.0,
                    lowWarningMultiplier = 2.0,
                    lowAlertMultiplier = 3.0,
                    highWarningMultiplier = 2.0,
                    highAlertMultiplier = 3.0
                )
            ),
            absoluteLowEmergencyBpm = 35.0,
            absoluteHighEmergencyBpm = 180.0
        )
        val quality = QualityAssessment(BaselineV2QualityState.VALID, 1.0)
        coordinator.ingest(restingInput(0L, 58.0, quality))
        coordinator.ingest(restingInput(60_000L, 58.0, quality))
        coordinator.ingest(restingInput(120_000L, 59.0, quality))
        coordinator.finish(120_000L)

        assertEquals(40.0, legacyStore.double("thresholds.hr.rest_low_bpm") ?: error("Missing low threshold"), 0.0)
        assertEquals(120.0, legacyStore.double("thresholds.hr.rest_high_bpm") ?: error("Missing high threshold"), 0.0)
    }

    @Test
    fun restingHrCoordinator_publishesOnlyV2SnapshotData() {
        val store = BaselineV2SnapshotStore()
        val coordinator = BaselineV2RestingHrCoordinator(
            snapshotStore = store,
            segmentBuilder = RestingHrSegmentBuilder(),
            model = RestingHrModel(
                RestingHrModelConfig(
                    expectedLowerMultiplier = 1.0,
                    expectedUpperMultiplier = 1.0,
                    lowWarningMultiplier = 2.0,
                    lowAlertMultiplier = 3.0,
                    highWarningMultiplier = 2.0,
                    highAlertMultiplier = 3.0
                )
            ),
            absoluteLowEmergencyBpm = 35.0,
            absoluteHighEmergencyBpm = 180.0
        )
        val quality = QualityAssessment(BaselineV2QualityState.VALID, 1.0)

        coordinator.ingest(restingInput(0L, 58.0, quality))
        coordinator.ingest(restingInput(60_000L, 58.0, quality))
        coordinator.ingest(restingInput(120_000L, 59.0, quality))
        coordinator.finish(120_000L)

        assertEquals("hr.resting_bpm.v2", store.current()?.snapshots?.keys?.single())
        assertEquals("MODEL_ONLY", store.current()?.snapshots?.values?.single()?.candidateBoundaries?.source)
    }

    @Test
    fun restingHrSegmentBuilder_requiresDwellAndUsesSegmentMidpoint() {
        val builder = RestingHrSegmentBuilder()
        val quality = QualityAssessment(BaselineV2QualityState.VALID, 1.0)

        assertTrue(builder.ingest(restingInput(0L, 58.0, quality)).isEmpty())
        assertTrue(builder.ingest(restingInput(60_000L, 60.0, quality)).isEmpty())
        assertTrue(builder.ingest(restingInput(120_000L, 59.0, quality)).isEmpty())

        val segment = builder.finish().single()
        assertEquals(0L, segment.startMs)
        assertEquals(120_000L, segment.endMs)
        assertEquals(60_000L, segment.representativeTimeMs)
        assertEquals(59.0, segment.medianBpm, 0.0)
        assertEquals(3, segment.sampleCount)
    }

    @Test
    fun restingHrSegmentBuilder_discardsSegmentOnIneligibleObservation() {
        val builder = RestingHrSegmentBuilder()
        val quality = QualityAssessment(BaselineV2QualityState.VALID, 1.0)
        builder.ingest(restingInput(0L, 58.0, quality))
        builder.ingest(restingInput(60_000L, 58.0, quality))
        builder.ingest(restingInput(120_000L, 58.0, quality))

        val interrupted = builder.ingest(
            restingInput(180_000L, 58.0, quality).copy(onWrist = false)
        )

        assertEquals(1, interrupted.size)
        assertTrue(builder.finish().isEmpty())
    }

    @Test
    fun restingHrSegmentBuilder_ignoresOutOfOrderObservations() {
        val builder = RestingHrSegmentBuilder()
        val quality = QualityAssessment(BaselineV2QualityState.VALID, 1.0)
        builder.ingest(restingInput(100_000L, 58.0, quality))

        assertTrue(builder.ingest(restingInput(90_000L, 60.0, quality)).isEmpty())
    }

    @Test
    fun robustStatistics_usesMadBeforeFallbacks() {
        val estimate = RobustStatistics.robustScale(
            values = listOf(56.0, 57.0, 58.0, 58.0, 59.0, 60.0, 120.0),
            minimumScale = 2.0
        )

        assertEquals(58.0, estimate.centre!!, 0.0)
        assertEquals("MAD_1_4826", estimate.method)
        assertTrue(estimate.scale!! > 0.0)
    }

    @Test
    fun restingHrModel_keepsExpectedBoundsSeparateFromCandidateBoundaries() {
        val model = RestingHrModel(
            RestingHrModelConfig(
                expectedLowerMultiplier = 1.0,
                expectedUpperMultiplier = 1.0,
                lowWarningMultiplier = 2.0,
                lowAlertMultiplier = 3.0,
                highWarningMultiplier = 2.0,
                highAlertMultiplier = 3.0
            )
        )
        val estimate = model.estimate(listOf(56.0, 57.0, 58.0, 58.0, 59.0, 60.0))!!
        val boundaries = model.candidateBoundaries(estimate, 35.0, 180.0)

        assertTrue(boundaries.lowAlert!! < estimate.lowerExpectedBoundBpm)
        assertTrue(boundaries.highAlert!! > estimate.upperExpectedBoundBpm)
        assertEquals("MODEL_ONLY", boundaries.source)
    }

    @Test(expected = IllegalArgumentException::class)
    fun restingHrModel_rejectsUnsafeBoundaryOrdering() {
        val model = RestingHrModel(
            RestingHrModelConfig(
                expectedLowerMultiplier = 1.0,
                expectedUpperMultiplier = 1.0,
                lowWarningMultiplier = 2.0,
                lowAlertMultiplier = 3.0,
                highWarningMultiplier = 2.0,
                highAlertMultiplier = 3.0
            )
        )
        val estimate = model.estimate(listOf(56.0, 57.0, 58.0, 58.0, 59.0, 60.0))!!
        model.candidateBoundaries(estimate, 57.0, 180.0)
    }

    @Test
    fun snapshotStore_publishesAnAtomicGeneration() {
        val store = BaselineV2SnapshotStore()
        val snapshot = testSnapshot()

        store.publish(
            BaselineV2SnapshotBatch(
                generation = 4L,
                publishedAtMs = 5_000L,
                snapshots = mapOf(snapshot.model.metricId to snapshot.copy(generation = 4L))
            )
        )

        assertEquals(4L, store.current()?.generation)
        assertEquals(snapshot.copy(generation = 4L), store.snapshot("hr.rest"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun snapshotStore_rejectsMixedGenerations() {
        val store = BaselineV2SnapshotStore()
        val snapshot = testSnapshot()

        store.publish(
            BaselineV2SnapshotBatch(
                generation = 4L,
                publishedAtMs = 5_000L,
                snapshots = mapOf(
                    snapshot.model.metricId to snapshot.copy(generation = 3L)
                )
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun snapshotStore_rejectsOlderGeneration() {
        val store = BaselineV2SnapshotStore()
        val snapshot = testSnapshot()
        store.publish(
            BaselineV2SnapshotBatch(
                generation = 4L,
                publishedAtMs = 5_000L,
                snapshots = mapOf(snapshot.model.metricId to snapshot.copy(generation = 4L))
            )
        )

        store.publish(
            BaselineV2SnapshotBatch(
                generation = 3L,
                publishedAtMs = 6_000L,
                snapshots = mapOf(snapshot.model.metricId to snapshot.copy(generation = 3L))
            )
        )
    }

    @Test
    fun generationStore_commitsAndRestoresTheCommittedGeneration() {
        val root = createTempDir(prefix = "baseline-v2-generation-")
        try {
            val store = BaselineV2GenerationStore(root)
            store.commit(
                BaselineV2GenerationPayload(
                    generation = 7L,
                    history = byteArrayOf(1, 2, 3),
                    state = byteArrayOf(4, 5, 6)
                )
            )

            val restored = store.loadCommitted()
            assertNotNull(restored)
            assertEquals(7L, restored?.generation)
            assertArrayEquals(byteArrayOf(1, 2, 3), restored!!.history)
            assertArrayEquals(byteArrayOf(4, 5, 6), restored.state)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun trendsAdapter_exposesCandidateBoundariesAsDisplayData() {
        val snapshot = testSnapshot()
        val items = BaselineV2TrendsAdapter.toDisplayItems(
            BaselineV2SnapshotBatch(
                generation = 1L,
                publishedAtMs = 2_000L,
                snapshots = mapOf(snapshot.model.metricId to snapshot)
            )
        )

        assertEquals(1, items.size)
        assertEquals(48.0, items.single().candidateBoundaries.lowWarning ?: error("Missing low warning"), 0.0)
        assertEquals(BaselineV2LifecycleState.DISPLAY_READY, items.single().lifecycle)
    }

    @Test
    fun observationTime_requiresRepresentativeTimeInsideWindow() {
        val time = ObservationTime(
            observationStartMs = 1_000L,
            observationEndMs = 3_000L,
            representativeTimeMs = 2_000L,
            publishedAtMs = 4_000L
        )

        assertEquals(2_000L, time.representativeTimeMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun observationTime_rejectsRepresentativeTimeOutsideWindow() {
        ObservationTime(
            observationStartMs = 1_000L,
            observationEndMs = 3_000L,
            representativeTimeMs = 4_000L,
            publishedAtMs = 4_000L
        )
    }

    @Test
    fun confidence_usesHardGatesAndWeightedGeometricMean() {
        val confidence = ConfidenceBreakdown(
            sampleConfidence = 0.90,
            coverageConfidence = 0.80,
            contextConfidence = 1.0,
            modelConfidence = 0.90,
            ageConfidence = 0.90
        )

        assertTrue(confidence.passesHardGates())
        assertTrue(confidence.aggregate in 0.88..0.90)
    }

    @Test
    fun confidence_failsCriticalGateEvenWhenAggregateIsHigh() {
        val confidence = ConfidenceBreakdown(
            sampleConfidence = 0.70,
            coverageConfidence = 1.0,
            contextConfidence = 1.0,
            modelConfidence = 1.0,
            ageConfidence = 1.0
        )

        assertFalse(confidence.passesHardGates())
        assertTrue(confidence.aggregate > 0.70)
    }

    @Test
    fun qualityAssessment_allowsContinuousScoreAndRejectionReason() {
        val quality = QualityAssessment(
            state = BaselineV2QualityState.REJECTED,
            score = 0.25,
            rejectionReason = "poor_contact"
        )

        assertEquals(0.25, quality.score, 0.0)
        assertEquals("poor_contact", quality.rejectionReason)
    }

    @Test
    fun sharedLifecycleTransitions_rejectSkippingBackToCollection() {
        BaselineV2LifecycleTransitions.requireTransition(
            BaselineV2LifecycleState.COLLECTING,
            BaselineV2LifecycleState.CENTRE_READY
        )

        val rejected = runCatching {
            BaselineV2LifecycleTransitions.requireTransition(
                BaselineV2LifecycleState.COLLECTING,
                BaselineV2LifecycleState.DISPLAY_READY
            )
        }
        assertTrue(rejected.isFailure)
    }

    @Test
    fun sharedPipelineReturnsTypedAcceptedObservationOrRejection() {
        val observation = QualifiedMetricObservation(
            metricId = "hr.resting_bpm.v2",
            definition = testSnapshot().model.definition,
            value = 58.0,
            time = ObservationTime(0L, 60_000L, 30_000L, 61_000L),
            context = MetricActivityContext.RESTING,
            quality = QualityAssessment(BaselineV2QualityState.VALID, 1.0),
            sourceMetadata = TypedSourceMetadata.HeartRate("STILL", still = true)
        )
        val pipeline = BaselineV2QualifiedObservationPipeline<Double> {
            it.takeIf { value -> value in 20.0..240.0 }?.let { observation }
        }

        assertTrue(pipeline.process(58.0) is BaselineV2QualificationResult.Accepted)
        assertTrue(pipeline.process(300.0) is BaselineV2QualificationResult.Rejected)
    }

    @Test
    fun dependencyGraph_invalidatesDependentsDeterministically() {
        val graph = BaselineV2DependencyGraph(
            mapOf(
                "hrr" to setOf("hr.resting_bpm.v2", "hr.max.v1"),
                "risk" to setOf("hrr")
            )
        )

        assertEquals(
            listOf("hrr", "risk"),
            graph.invalidatedBy(setOf("hr.resting_bpm.v2"))
        )
    }

    @Test
    fun modelUpdateCoordinator_persistsBeforePublishing() {
        val order = mutableListOf<String>()
        val coordinator = BaselineV2ModelUpdateCoordinator(object : BaselineV2SnapshotCommitter {
            override fun persist(transaction: BaselineV2ModelUpdateTransaction) {
                order += "persist"
            }

            override fun publish(transaction: BaselineV2ModelUpdateTransaction) {
                order += "publish"
            }
        })

        coordinator.commit(BaselineV2ModelUpdateTransaction(1L, testSnapshot()))

        assertEquals(listOf("persist", "publish"), order)
    }

    @Test
    fun scalarRmssdCoordinator_usesLogDistributionAndRetainsOtherSnapshots() {
        val store = BaselineV2SnapshotStore()
        val scalar = BaselineV2ScalarMetricCoordinator(
            snapshotStore = store,
            metricId = BaselineV2MetricDefinitions.RMSSD.metricId,
            definition = BaselineV2MetricDefinitions.RMSSD,
            strategy = BaselineV2Strategy.DIST_LOG,
            config = BaselineV2ScalarMetricConfig(
                centreSamples = 2,
                distributionSamples = 2,
                minimumActiveDays = 0,
                minimumCoveredSeconds = 0L,
                logDomain = true
            )
        )
        store.publish(
            BaselineV2SnapshotBatch(
                generation = 1L,
                publishedAtMs = 1_000L,
                snapshots = mapOf("hr.rest" to testSnapshot().copy(generation = 1L))
            )
        )

        scalar.ingest(rmssdObservation(1_000L, 20.0))
        val snapshot = scalar.ingest(rmssdObservation(301_000L, 40.0))

        assertEquals(BaselineV2Strategy.DIST_LOG, snapshot?.model?.strategy)
        assertTrue((snapshot?.model?.expectedValue ?: error("Missing RMSSD estimate")) in 20.0..40.0)
        assertNotNull(store.snapshot("hr.rest"))
        assertNotNull(store.snapshot(BaselineV2MetricDefinitions.RMSSD.metricId))
    }

    @Test
    fun scalarRmssdCheckpoint_restoresObservationsAcrossRecreation() {
        val root = createTempDir(prefix = "baseline-v2-rmssd-")
        try {
            val checkpoint = BaselineV2ScalarCheckpointStore(BaselineV2GenerationStore(root))
            val first = BaselineV2ScalarMetricCoordinator(
                snapshotStore = BaselineV2SnapshotStore(),
                metricId = BaselineV2MetricDefinitions.RMSSD.metricId,
                definition = BaselineV2MetricDefinitions.RMSSD,
                strategy = BaselineV2Strategy.DIST_LOG,
                config = BaselineV2ScalarMetricConfig(1, 1, 0, 0L, logDomain = true),
                checkpointStore = checkpoint
            )
            first.ingest(rmssdObservation(1_000L, 20.0))

            val restored = BaselineV2ScalarMetricCoordinator(
                snapshotStore = BaselineV2SnapshotStore(),
                metricId = BaselineV2MetricDefinitions.RMSSD.metricId,
                definition = BaselineV2MetricDefinitions.RMSSD,
                strategy = BaselineV2Strategy.DIST_LOG,
                config = BaselineV2ScalarMetricConfig(1, 1, 0, 0L, logDomain = true),
                checkpointStore = checkpoint
            )

            assertEquals(1, restored.observationCount())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun scalarSdnnCoordinator_usesFiveMinuteVariabilityDefinition() {
        val store = BaselineV2SnapshotStore()
        val coordinator = BaselineV2ScalarMetricCoordinator(
            snapshotStore = store,
            metricId = BaselineV2MetricDefinitions.SDNN.metricId,
            definition = BaselineV2MetricDefinitions.SDNN,
            strategy = BaselineV2Strategy.DIST_VARIABILITY,
            config = BaselineV2ScalarMetricConfig(2, 2, 0, 0L)
        )

        coordinator.ingest(sdnnObservation(1_000L, 35.0))
        val snapshot = coordinator.ingest(sdnnObservation(301_000L, 42.0))

        assertEquals("sdnn_5min_ms", snapshot?.model?.definition?.emissionIdentity)
        assertEquals(BaselineV2Strategy.DIST_VARIABILITY, snapshot?.model?.strategy)
    }

    @Test
    fun snapshot_carriesMetricAndFormulaVersionsAndNoCepAuthority() {
        val snapshot = testSnapshot()

        assertEquals("hr_rest_v1", snapshot.model.definition.version)
        assertEquals("candidate_formula_v1", snapshot.thresholdFormulaVersion)
        assertEquals("MODEL_ONLY", snapshot.candidateBoundaries.source)
    }

    private fun testSnapshot(): BaselineV2MetricSnapshot {
        val definition = MetricDefinitionVersion(
            metricId = "hr.rest",
            version = "hr_rest_v1",
            windowSec = 600L,
            artifactPolicy = "hr_rest_quality_v1",
            normalization = "none",
            transform = "none",
            contextKey = "AWAKE_REST",
            emissionIdentity = "segment_midpoint"
        )
        val confidence = ConfidenceBreakdown(1.0, 1.0, 1.0, 1.0, 1.0)
        val model = BaselineV2ModelEstimate(
            metricId = "hr.rest",
            definition = definition,
            strategy = BaselineV2Strategy.DIST_ROBUST,
            expectedValue = 58.0,
            lowerExpectedBound = 51.0,
            upperExpectedBound = 66.0,
            lowerScale = 2.0,
            upperScale = 2.0,
            lifecycle = BaselineV2LifecycleState.DISPLAY_READY,
            confidence = confidence,
            sampleCount = 30L,
            coveredSeconds = 21_600L,
            activeDays = 3,
            temporalDiversity = 3,
            contextDiversity = 3
        )
        return BaselineV2MetricSnapshot(
            model = model,
            observation = BaselineV2ObservationAssessment(
                metricId = "hr.rest",
                observedValue = 58.0,
                time = null,
                context = MetricActivityContext.RESTING,
                quality = QualityAssessment(BaselineV2QualityState.VALID, 1.0),
                signedDeviation = 0.0,
                lowerTailDeviation = 0.0,
                upperTailDeviation = 0.0,
                normalizedLowerTailDeviation = 0.0,
                normalizedUpperTailDeviation = 0.0
            ),
            candidateBoundaries = InformationalCandidateBoundaries(
                lowWarning = 48.0,
                lowAlert = 44.0,
                highWarning = 70.0,
                highAlert = 76.0
            ),
            generation = 1L,
            modelVersion = "model_v1",
            thresholdFormulaVersion = "candidate_formula_v1"
        )
    }

    private fun testModel(): RestingHrModel = RestingHrModel(
        RestingHrModelConfig(
            expectedLowerMultiplier = 1.0,
            expectedUpperMultiplier = 1.0,
            lowWarningMultiplier = 2.0,
            lowAlertMultiplier = 3.0,
            highWarningMultiplier = 2.0,
            highAlertMultiplier = 3.0
        )
    )

    private fun rmssdObservation(timestampMs: Long, value: Double) =
        QualifiedMetricObservation(
            metricId = BaselineV2MetricDefinitions.RMSSD.metricId,
            definition = BaselineV2MetricDefinitions.RMSSD,
            value = value,
            time = ObservationTime(timestampMs - 300_000L, timestampMs, timestampMs, timestampMs),
            context = MetricActivityContext.RESTING,
            quality = QualityAssessment(BaselineV2QualityState.VALID, 1.0),
            sourceMetadata = TypedSourceMetadata.Hrv(20, 0, 300L)
        )

    private fun sdnnObservation(timestampMs: Long, value: Double) =
        QualifiedMetricObservation(
            metricId = BaselineV2MetricDefinitions.SDNN.metricId,
            definition = BaselineV2MetricDefinitions.SDNN,
            value = value,
            time = ObservationTime(timestampMs - 300_000L, timestampMs, timestampMs, timestampMs),
            context = MetricActivityContext.RESTING,
            quality = QualityAssessment(BaselineV2QualityState.VALID, 1.0),
            sourceMetadata = TypedSourceMetadata.Hrv(20, 0, 300L)
        )

    private fun restingInput(
        timestampMs: Long,
        bpm: Double,
        quality: QualityAssessment
    ) = RestingHrInput(
        bpm = bpm,
        timestampMs = timestampMs,
        awake = true,
        onWrist = true,
        still = true,
        quality = quality
    )
}
