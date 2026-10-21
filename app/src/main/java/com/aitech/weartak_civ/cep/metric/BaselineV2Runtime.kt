package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.engine.FeatureStore

/** Process-local V2 output holder. It has no CEP or FeatureStore ownership. */
object BaselineV2Runtime {
    val snapshotStore: BaselineV2SnapshotStore = BaselineV2SnapshotStore()

    @Volatile
    private var restingHrCoordinator: BaselineV2RestingHrCoordinator? = null
    @Volatile
    private var restingHrQualityProvider: RestingHrQualityProvider? = null
    @Volatile
    private var rmssdCoordinator: BaselineV2ScalarMetricCoordinator? = null
    @Volatile
    private var sdnnCoordinator: BaselineV2ScalarMetricCoordinator? = null
    @Volatile
    private var spo2Coordinator: BaselineV2ScalarMetricCoordinator? = null
    @Volatile
    private var ppgCoordinator: BaselineV2ScalarMetricCoordinator? = null
    @Volatile
    private var activeHrCoordinator: BaselineV2ScalarMetricCoordinator? = null
    @Volatile
    private var hrrCoordinator: BaselineV2ScalarMetricCoordinator? = null
    @Volatile
    private var skinTemperatureCoordinator: BaselineV2ScalarMetricCoordinator? = null
    @Volatile
    private var ibiCoordinator: BaselineV2ScalarMetricCoordinator? = null

    fun configureRestingHrCoordinator(
        coordinator: BaselineV2RestingHrCoordinator,
        qualityProvider: RestingHrQualityProvider
    ) {
        restingHrCoordinator = coordinator
        restingHrQualityProvider = qualityProvider
    }

    fun configureRmssdCoordinator(coordinator: BaselineV2ScalarMetricCoordinator) {
        rmssdCoordinator = coordinator
    }

    fun configureSdnnCoordinator(coordinator: BaselineV2ScalarMetricCoordinator) {
        sdnnCoordinator = coordinator
    }

    fun configureSpo2Coordinator(coordinator: BaselineV2ScalarMetricCoordinator) {
        spo2Coordinator = coordinator
    }

    fun configurePpgCoordinator(coordinator: BaselineV2ScalarMetricCoordinator) {
        ppgCoordinator = coordinator
    }

    fun configureActiveHrCoordinator(coordinator: BaselineV2ScalarMetricCoordinator) {
        activeHrCoordinator = coordinator
    }

    fun configureHrrCoordinator(coordinator: BaselineV2ScalarMetricCoordinator) {
        hrrCoordinator = coordinator
    }

    fun configureSkinTemperatureCoordinator(coordinator: BaselineV2ScalarMetricCoordinator) {
        skinTemperatureCoordinator = coordinator
    }

    fun configureIbiCoordinator(coordinator: BaselineV2ScalarMetricCoordinator) {
        ibiCoordinator = coordinator
    }

    fun observeFeatures(featureStore: FeatureStore, nowMs: Long = System.currentTimeMillis()) {
        restingHrCoordinator?.let { coordinator ->
            restingHrQualityProvider?.let { qualityProvider ->
                BaselineV2FeatureAdapter.restingHrInput(
                    featureStore = featureStore,
                    qualityProvider = qualityProvider,
                    nowMs = nowMs
                )?.let(coordinator::ingest)
            }
        }
        observeRmssd(featureStore, nowMs)
        observeSdnn(featureStore, nowMs)
        observeSpo2(featureStore, nowMs)
        observePpg(featureStore, nowMs)
        observeActiveHr(featureStore, nowMs)
        observeHrr(featureStore, nowMs)
        observeSkinTemperature(featureStore, nowMs)
        observeIbi(featureStore, nowMs)
    }

    private fun observeRmssd(featureStore: FeatureStore, nowMs: Long) {
        observeHrvScalar(featureStore, nowMs, "hrv.rmssd_ms", BaselineV2MetricDefinitions.RMSSD) {
            rmssdCoordinator?.ingest(it)
        }
    }

    private fun observeSdnn(featureStore: FeatureStore, nowMs: Long) {
        observeHrvScalar(featureStore, nowMs, "hrv.sdnn_ms", BaselineV2MetricDefinitions.SDNN) {
            sdnnCoordinator?.ingest(it)
        }
    }

    private fun observeSpo2(featureStore: FeatureStore, nowMs: Long) {
        val value = featureStore.latestAsDouble("spo2.pct") ?: return
        val timestamp = featureStore.timestampMs("spo2.pct") ?: return
        val statusOk = featureStore.latestAsDouble("spo2.status_ok")?.let { it >= 0.5 } ?: return
        val onWrist = featureStore.latestAsDouble("offbody.on_wrist")?.let { it >= 0.5 } ?: return
        val activity = featureStore.str("activity.state") ?: return
        val family = when (activity) {
            "WALKING", "LIGHT_ACTIVITY" -> "walk"
            "STILL" -> "resting"
            else -> return
        }
        if (!statusOk || !onWrist || !value.isFinite() || value !in 85.0..100.0) return
        spo2Coordinator?.ingest(
            QualifiedMetricObservation(
                metricId = BaselineV2MetricDefinitions.SPO2.metricId,
                definition = BaselineV2MetricDefinitions.SPO2,
                value = value,
                time = ObservationTime(timestamp, timestamp, timestamp, nowMs),
                context = if (family == "resting") MetricActivityContext.RESTING else MetricActivityContext.ACTIVE,
                quality = QualityAssessment(BaselineV2QualityState.VALID, 0.85),
                sourceMetadata = TypedSourceMetadata.Spo2(
                    motionRejected = false,
                    family = family,
                    altitudeBucket = featureStore.latestAsDouble("profile.spo2_active_baseline_altitude_bucket")?.toInt()
                )
            )
        )
    }

    private fun observePpg(featureStore: FeatureStore, nowMs: Long) {
        val value = featureStore.latestAsDouble("ppg.amplitude") ?: return
        val timestamp = featureStore.timestampMs("ppg.amplitude") ?: return
        val quality = featureStore.latestAsDouble("ppg.quality") ?: return
        val wavelength = featureStore.str("ppg.wavelength") ?: return
        val onWrist = featureStore.latestAsDouble("offbody.on_wrist")?.let { it >= 0.5 } ?: return
        val activity = featureStore.str("activity.state") ?: return
        if (!onWrist || !value.isFinite() || value <= 0.0 || quality < 0.5 || wavelength.isBlank()) return
        ppgCoordinator?.ingest(
            QualifiedMetricObservation(
                metricId = BaselineV2MetricDefinitions.PPG.metricId,
                definition = BaselineV2MetricDefinitions.PPG,
                value = value,
                time = ObservationTime(timestamp - 20_000L, timestamp, timestamp, nowMs),
                context = if (activity == "STILL") MetricActivityContext.RESTING else
                    if (activity == "UNKNOWN") MetricActivityContext.UNKNOWN else MetricActivityContext.ACTIVE,
                quality = QualityAssessment(BaselineV2QualityState.VALID, quality.coerceIn(0.0, 1.0)),
                sourceMetadata = TypedSourceMetadata.Ppg(
                    wavelength = wavelength,
                    contactScore = quality,
                    waveformQuality = quality
                )
            )
        )
    }

    private fun observeActiveHr(featureStore: FeatureStore, nowMs: Long) {
        val value = featureStore.latestAsDouble("heart_rate") ?: return
        val timestamp = featureStore.timestampMs("heart_rate") ?: return
        val onWrist = featureStore.latestAsDouble("offbody.on_wrist")?.let { it >= 0.5 } ?: return
        val activity = featureStore.str("activity.state") ?: return
        if (!onWrist || !value.isFinite() || value !in 25.0..240.0 || activity == "STILL" || activity == "UNKNOWN") return
        activeHrCoordinator?.ingest(
            QualifiedMetricObservation(
                metricId = BaselineV2MetricDefinitions.ACTIVE_HR.metricId,
                definition = BaselineV2MetricDefinitions.ACTIVE_HR,
                value = value,
                time = ObservationTime(timestamp, timestamp, timestamp, nowMs),
                context = MetricActivityContext.ACTIVE,
                quality = QualityAssessment(BaselineV2QualityState.VALID, 0.8),
                sourceMetadata = TypedSourceMetadata.HeartRate(activity, still = false)
            )
        )
    }

    private fun observeHrr(featureStore: FeatureStore, nowMs: Long) {
        val heartRate = featureStore.latestAsDouble("heart_rate") ?: return
        val timestamp = featureStore.timestampMs("heart_rate") ?: return
        val rest = featureStore.latestAsDouble("profile.hr_rest_bpm") ?: return
        val maximum = featureStore.latestAsDouble("profile.hr_max_bpm") ?: return
        val activity = featureStore.str("activity.state") ?: return
        val onWrist = featureStore.latestAsDouble("offbody.on_wrist")?.let { it >= 0.5 } ?: return
        val reserve = maximum - rest
        if (!onWrist || reserve <= 0.0 || !heartRate.isFinite() || activity == "STILL" || activity == "UNKNOWN") return
        hrrCoordinator?.ingest(
            QualifiedMetricObservation(
                metricId = BaselineV2MetricDefinitions.HRR.metricId,
                definition = BaselineV2MetricDefinitions.HRR,
                value = ((heartRate - rest) / reserve) * 100.0,
                time = ObservationTime(timestamp, timestamp, timestamp, nowMs),
                context = MetricActivityContext.ACTIVE,
                quality = QualityAssessment(BaselineV2QualityState.VALID, 0.75),
                sourceMetadata = TypedSourceMetadata.HeartRate(activity, still = false)
            )
        )
    }

    private fun observeSkinTemperature(featureStore: FeatureStore, nowMs: Long) {
        val value = featureStore.latestAsDouble("skin_temp") ?: return
        val timestamp = featureStore.timestampMs("skin_temp") ?: return
        val onWrist = featureStore.latestAsDouble("offbody.on_wrist")?.let { it >= 0.5 } ?: return
        val activity = featureStore.str("activity.state") ?: return
        if (!onWrist || !value.isFinite() || value !in 0.0..50.0 || activity == "UNKNOWN") return
        skinTemperatureCoordinator?.ingest(
            QualifiedMetricObservation(
                metricId = BaselineV2MetricDefinitions.SKIN_TEMPERATURE.metricId,
                definition = BaselineV2MetricDefinitions.SKIN_TEMPERATURE,
                value = value,
                time = ObservationTime(timestamp, timestamp, timestamp, nowMs),
                context = when (activity) {
                    "STILL" -> MetricActivityContext.RESTING
                    else -> MetricActivityContext.ACTIVE
                },
                quality = QualityAssessment(BaselineV2QualityState.VALID, 0.8),
                sourceMetadata = TypedSourceMetadata.SkinTemperature(
                    sensorLocation = "peripheral",
                    ambientCelsius = null
                )
            )
        )
    }

    private fun observeIbi(featureStore: FeatureStore, nowMs: Long) {
        val value = featureStore.latestAsDouble("hrv.ibi_ms") ?: return
        val timestamp = featureStore.timestampMs("hrv.ibi_ms") ?: return
        val onWrist = featureStore.latestAsDouble("offbody.on_wrist")?.let { it >= 0.5 } ?: return
        val sleeping = featureStore.bool("sleep.is_sleeping") ?: return
        val activity = featureStore.str("activity.state") ?: return
        if (!onWrist || !value.isFinite() || value !in 300.0..2_000.0 || (!sleeping && activity != "STILL")) return
        ibiCoordinator?.ingest(
            QualifiedMetricObservation(
                metricId = BaselineV2MetricDefinitions.IBI.metricId,
                definition = BaselineV2MetricDefinitions.IBI,
                value = value,
                time = ObservationTime(timestamp, timestamp, timestamp, nowMs),
                context = if (sleeping) MetricActivityContext.ANY else MetricActivityContext.RESTING,
                quality = QualityAssessment(BaselineV2QualityState.VALID, 0.85),
                sourceMetadata = TypedSourceMetadata.Hrv(1, 0, 300L)
            )
        )
    }

    private fun observeHrvScalar(
        featureStore: FeatureStore,
        nowMs: Long,
        sourceKey: String,
        definition: MetricDefinitionVersion,
        sink: (QualifiedMetricObservation) -> Unit
    ) {
        val value = featureStore.latestAsDouble(sourceKey) ?: return
        val timestamp = featureStore.timestampMs(sourceKey) ?: return
        val onWrist = featureStore.latestAsDouble("offbody.on_wrist")?.let { it >= 0.5 } ?: return
        val sleeping = featureStore.bool("sleep.is_sleeping") ?: return
        val activity = featureStore.str("activity.state") ?: return
        val quality = QualityAssessment(BaselineV2QualityState.VALID, 0.75)
        val observation = QualifiedMetricObservation(
            metricId = definition.metricId,
            definition = definition,
            value = value,
            time = ObservationTime(
                observationStartMs = timestamp - (definition.windowSec ?: 300L) * 1_000L,
                observationEndMs = timestamp,
                representativeTimeMs = timestamp,
                publishedAtMs = nowMs
            ),
            context = if (sleeping) MetricActivityContext.ANY else
                if (activity == "STILL") MetricActivityContext.RESTING else MetricActivityContext.UNKNOWN,
            quality = quality,
            sourceMetadata = TypedSourceMetadata.Hrv(
                validNnCount = 0,
                rejectedNnCount = 0,
                windowSec = definition.windowSec ?: 300L
            )
        )
        if (onWrist && (sleeping || activity == "STILL")) {
            sink(observation)
        }
    }


    fun recalculateRestingHr(nowMs: Long = System.currentTimeMillis()): BaselineV2MetricSnapshot? =
        restingHrCoordinator?.recalculate(nowMs)

    /** Clears V2 resting-HR state while keeping the configured observer active. */
    fun resetRestingHr() {
        restingHrCoordinator?.reset()
        snapshotStore.clear()
    }

    fun snapshotExportText(): String =
        BaselineV2SnapshotFormatter.format(snapshotStore.current())

    fun reset() {
        resetRestingHr()
        restingHrCoordinator = null
        restingHrQualityProvider = null
        rmssdCoordinator?.reset()
        rmssdCoordinator = null
        sdnnCoordinator?.reset()
        sdnnCoordinator = null
        spo2Coordinator?.reset()
        spo2Coordinator = null
        ppgCoordinator?.reset()
        ppgCoordinator = null
        activeHrCoordinator?.reset()
        activeHrCoordinator = null
        hrrCoordinator?.reset()
        hrrCoordinator = null
        skinTemperatureCoordinator?.reset()
        skinTemperatureCoordinator = null
        ibiCoordinator?.reset()
        ibiCoordinator = null
    }

}
