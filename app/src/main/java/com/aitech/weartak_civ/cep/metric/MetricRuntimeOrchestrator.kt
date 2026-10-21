package com.aitech.weartak_civ.cep.metric

import android.content.Context
import com.aitech.weartak_civ.cep.engine.FeatureStore
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.NumericSample
import com.aitech.weartak_civ.cep.model.payloads.HeartRatePayload
import com.aitech.weartak_civ.data.DataRepository
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MetricRuntimeOrchestrator(
    private val dataRepository: DataRepository?,
    metricSpecs: List<MetricSpec>,
    private val appContext: Context? = null,
    private val legacyBaselineEnabled: Boolean = true,
    private val historyStore: MetricHistoryStore = FileBackedMetricHistoryStore(
        baseDir = if (appContext != null) {
            File(appContext.filesDir, "metric_history")
        } else {
            File(System.getProperty("java.io.tmpdir"), "metric_history_${System.nanoTime()}")
        }
    ),
    private val calculationCacheStore: MetricCalculationCacheStore = FileBackedMetricCalculationCacheStore(
        baseDir = if (appContext != null) {
            File(appContext.filesDir, "metric_results")
        } else {
            File(System.getProperty("java.io.tmpdir"), "metric_results_${System.nanoTime()}")
        }
    )
) {
    private val v2CheckpointStore = BaselineV2RestingHrCheckpointStore(
        BaselineV2GenerationStore(
            File(
                appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir")),
                "baseline_v2_resting_hr"
            )
        )
    )
    private val v2RestingHrCoordinator = BaselineV2RestingHrRuntimeFactory.create(
        config = BaselineV2RestingHrDisplayPolicy.runtimeConfig,
        snapshotStore = BaselineV2Runtime.snapshotStore,
        checkpointStore = v2CheckpointStore
    )
    private val v2RmssdCoordinator = BaselineV2ScalarMetricCoordinator(
        snapshotStore = BaselineV2Runtime.snapshotStore,
        metricId = BaselineV2MetricDefinitions.RMSSD.metricId,
        definition = BaselineV2MetricDefinitions.RMSSD,
        strategy = BaselineV2Strategy.DIST_LOG,
        config = BaselineV2ScalarMetricConfig(
            centreSamples = 10,
            distributionSamples = 20,
            minimumActiveDays = 3,
            minimumCoveredSeconds = 86_400L,
            logDomain = true
        ),
        checkpointStore = BaselineV2ScalarCheckpointStore(
            BaselineV2GenerationStore(
                File(
                    appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir")),
                    "baseline_v2_rmssd"
                )
            )
        )
    )
    private val v2SdnnCoordinator = BaselineV2ScalarMetricCoordinator(
        snapshotStore = BaselineV2Runtime.snapshotStore,
        metricId = BaselineV2MetricDefinitions.SDNN.metricId,
        definition = BaselineV2MetricDefinitions.SDNN,
        strategy = BaselineV2Strategy.DIST_VARIABILITY,
        config = BaselineV2ScalarMetricConfig(
            centreSamples = 10,
            distributionSamples = 20,
            minimumActiveDays = 3,
            minimumCoveredSeconds = 86_400L
        ),
        checkpointStore = BaselineV2ScalarCheckpointStore(
            BaselineV2GenerationStore(
                File(
                    appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir")),
                    "baseline_v2_sdnn"
                )
            )
        )
    )
    private val v2Spo2Coordinator = BaselineV2ScalarMetricCoordinator(
        snapshotStore = BaselineV2Runtime.snapshotStore,
        metricId = BaselineV2MetricDefinitions.SPO2.metricId,
        definition = BaselineV2MetricDefinitions.SPO2,
        strategy = BaselineV2Strategy.EMA_PERCENTILE,
        config = BaselineV2ScalarMetricConfig(
            centreSamples = 20,
            distributionSamples = 40,
            minimumActiveDays = 3,
            minimumCoveredSeconds = 0L,
            expectedLowerScale = 1.0,
            expectedUpperScale = 1.0,
            lowWarningScale = 1.0,
            lowAlertScale = 2.0,
            highWarningScale = 2.0,
            highAlertScale = 3.0,
            minimumScale = 0.05,
            lowerQuantile = 0.05
        ),
        absoluteLowGuardrail = 88.0,
        checkpointStore = BaselineV2ScalarCheckpointStore(
            BaselineV2GenerationStore(
                File(
                    appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir")),
                    "baseline_v2_spo2"
                )
            )
        )
    )
    private val v2PpgCoordinator = BaselineV2ScalarMetricCoordinator(
        snapshotStore = BaselineV2Runtime.snapshotStore,
        metricId = BaselineV2MetricDefinitions.PPG.metricId,
        definition = BaselineV2MetricDefinitions.PPG,
        strategy = BaselineV2Strategy.DIST_RELATIVE,
        config = BaselineV2ScalarMetricConfig(
            centreSamples = 10,
            distributionSamples = 20,
            minimumActiveDays = 3,
            minimumCoveredSeconds = 3_600L,
            minimumScale = 0.05
        ),
        checkpointStore = BaselineV2ScalarCheckpointStore(
            BaselineV2GenerationStore(
                File(
                    appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir")),
                    "baseline_v2_ppg"
                )
            )
        )
    )
    private val v2ActiveHrCoordinator = BaselineV2ScalarMetricCoordinator(
        snapshotStore = BaselineV2Runtime.snapshotStore,
        metricId = BaselineV2MetricDefinitions.ACTIVE_HR.metricId,
        definition = BaselineV2MetricDefinitions.ACTIVE_HR,
        strategy = BaselineV2Strategy.DIST_CONTEXT,
        config = BaselineV2ScalarMetricConfig(
            centreSamples = 10,
            distributionSamples = 20,
            minimumActiveDays = 3,
            minimumCoveredSeconds = 3_600L
        ),
        checkpointStore = BaselineV2ScalarCheckpointStore(
            BaselineV2GenerationStore(
                File(
                    appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir")),
                    "baseline_v2_active_hr"
                )
            )
        )
    )
    private val v2HrrCoordinator = BaselineV2ScalarMetricCoordinator(
        snapshotStore = BaselineV2Runtime.snapshotStore,
        metricId = BaselineV2MetricDefinitions.HRR.metricId,
        definition = BaselineV2MetricDefinitions.HRR,
        strategy = BaselineV2Strategy.DIST_CONTEXT,
        config = BaselineV2ScalarMetricConfig(
            centreSamples = 10,
            distributionSamples = 20,
            minimumActiveDays = 3,
            minimumCoveredSeconds = 3_600L
        ),
        checkpointStore = BaselineV2ScalarCheckpointStore(
            BaselineV2GenerationStore(
                File(
                    appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir")),
                    "baseline_v2_hrr"
                )
            )
        )
    )
    private val v2SkinTemperatureCoordinator = BaselineV2ScalarMetricCoordinator(
        snapshotStore = BaselineV2Runtime.snapshotStore,
        metricId = BaselineV2MetricDefinitions.SKIN_TEMPERATURE.metricId,
        definition = BaselineV2MetricDefinitions.SKIN_TEMPERATURE,
        strategy = BaselineV2Strategy.DIST_CONTEXT,
        config = BaselineV2ScalarMetricConfig(
            centreSamples = 10,
            distributionSamples = 20,
            minimumActiveDays = 3,
            minimumCoveredSeconds = 3_600L
        ),
        checkpointStore = BaselineV2ScalarCheckpointStore(
            BaselineV2GenerationStore(
                File(
                    appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir")),
                    "baseline_v2_skin_temperature"
                )
            )
        )
    )
    private val v2IbiCoordinator = BaselineV2ScalarMetricCoordinator(
        snapshotStore = BaselineV2Runtime.snapshotStore,
        metricId = BaselineV2MetricDefinitions.IBI.metricId,
        definition = BaselineV2MetricDefinitions.IBI,
        strategy = BaselineV2Strategy.DIST_VARIABILITY,
        config = BaselineV2ScalarMetricConfig(
            centreSamples = 10,
            distributionSamples = 20,
            minimumActiveDays = 3,
            minimumCoveredSeconds = 3_600L
        ),
        checkpointStore = BaselineV2ScalarCheckpointStore(
            BaselineV2GenerationStore(
                File(
                    appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir")),
                    "baseline_v2_ibi"
                )
            )
        )
    )
    private data class CachedHistory(val version: Long, val fullView: MetricHistoryView)

    private val runtimeLock = Any()
    private var observedActivityContext = MetricActivityContext.UNKNOWN
    private var contextSinceMs: Long? = null
    private var lastActiveAtMs: Long? = null
    private var unknownOffWristSinceMs: Long? = null
    private val historyCache = mutableMapOf<String, CachedHistory>()
    private val lastCompactionMs = mutableMapOf<String, Long>()

    companion object {
        val BASELINE_TREND_CANONICAL_SLOTS: Set<String> = setOf(
            "trend_direction",
            "current_value",
            "trend_short_term",
            "trend_long_term",
            "trend_deviation",
            "trend_percent_deviation",
            "low_threshold",
            "low_threshold_warn",
            "high_threshold",
            "high_threshold_warn",
            "baseline_confidence",
            "baseline_state",
            "baseline_sample_count",
            "baseline_coverage_sec",
            "baseline_covered_sec",
            "sampling_state"
        )
        private const val HOT_WINDOW_MS: Long = 72L * 60L * 60L * 1000L
        private const val WARM_WINDOW_MS: Long = 7L * 24L * 60L * 60L * 1000L
        private const val COLD_WINDOW_MS: Long = 30L * 24L * 60L * 60L * 1000L
        private const val UNKNOWN_OFF_WRIST_GRACE_MS: Long = 10_000L
        private const val COMPACTION_INTERVAL_MS: Long = 60L * 60L * 1000L
    }

    constructor(metricSpecs: List<MetricSpec>) : this(
        dataRepository = null,
        metricSpecs = metricSpecs
    )

    private val hookRegistry = MetricHookRegistry(
        listOf(
            SamsungHrvRmssdHook(),
            HeartRateReservePercentHook(),
            PeripheralSkinTemperatureMedianHook()
        )
    )
    private val processorRegistry = ProcessorRegistry(
        mapOf(MetricProcessorType.BASELINE_TREND to BaselineTrendProcessor())
    )
    private val outputContracts = mapOf(
        MetricProcessorType.BASELINE_TREND to MetricOutputContract(
            processorType = MetricProcessorType.BASELINE_TREND,
            canonicalSlots = BASELINE_TREND_CANONICAL_SLOTS
        )
    )

    private var registry: MetricRegistry = MetricRegistry(emptyList())
    private var dispatchRouter = MetricDispatchRouter(emptyList())
    private val specs = metricSpecs

    suspend fun initialize() {
        BaselineV2Runtime.configureRestingHrCoordinator(
            coordinator = v2RestingHrCoordinator,
            qualityProvider = ContextOnlyRestingHrQualityProvider
        )
        BaselineV2Runtime.configureRmssdCoordinator(v2RmssdCoordinator)
        BaselineV2Runtime.configureSdnnCoordinator(v2SdnnCoordinator)
        BaselineV2Runtime.configureSpo2Coordinator(v2Spo2Coordinator)
        BaselineV2Runtime.configurePpgCoordinator(v2PpgCoordinator)
        BaselineV2Runtime.configureActiveHrCoordinator(v2ActiveHrCoordinator)
        BaselineV2Runtime.configureHrrCoordinator(v2HrrCoordinator)
        BaselineV2Runtime.configureSkinTemperatureCoordinator(v2SkinTemperatureCoordinator)
        BaselineV2Runtime.configureIbiCoordinator(v2IbiCoordinator)
        val merged = mergeRuntimeOverrides(specs)
        validateSpecs(merged)
        dispatchRouter = MetricDispatchRouter(merged)
        val bindings = merged.map { spec ->
            val hook = spec.sourceHookId?.let { hookRegistry.resolveOrNull(it) }
                ?: if (spec.sourceHookId != null) error("Unknown hook id=${spec.sourceHookId}") else null
            val processor = processorRegistry.resolveOrThrow(spec.processor)
            val contract = outputContracts[spec.processor]
                ?: error("Missing output contract for processor=${spec.processor}")
            MetricSourceHookBinding(spec, hook, processor, contract)
        }
        registry = MetricRegistry(bindings)
    }

    /**
     * Destructively resets all metric state for a manually confirmed user change,
     * then rebuilds the metric registry. The caller must invoke this explicitly;
     * no user-change detection is performed by the runtime.
     */
    suspend fun resetAndReinitializeForUserChange(featureStore: FeatureStore, nowMs: Long) {
        synchronized(runtimeLock) {
            historyStore.clearAll()
            calculationCacheStore.clearAll()
            historyCache.clear()
            lastCompactionMs.clear()
            observedActivityContext = MetricActivityContext.UNKNOWN
            contextSinceMs = null
            lastActiveAtMs = null
            unknownOffWristSinceMs = null
            registry.allBindings().forEach { binding ->
                binding.metricSpec.selectedOutputs.slotsToSelectedKeys.values
                    .forEach(featureStore::clear)
            }
            v2RestingHrCoordinator.reset()
            v2CheckpointStore.clear()
            BaselineV2Runtime.reset()
        }
        initialize()
    }

    fun onEvent(evt: CepEvent<*>, featureStore: FeatureStore) {
        synchronized(runtimeLock) {
            if (legacyBaselineEnabled) {
                val nowMs = evt.ts
                observeActivityContext(featureStore, nowMs)
                dispatchRouter.sourcesForEvent(evt).forEach { source ->
                    val bindings = registry.findBySource(source)
                    bindings.forEach { binding ->
                        processLegacyBinding(evt, featureStore, binding, nowMs)
                    }
                }
            }
        }
        runCatching { BaselineV2Runtime.observeFeatures(featureStore, evt.ts) }
    }

    private fun processLegacyBinding(
        evt: CepEvent<*>,
        featureStore: FeatureStore,
        binding: MetricSourceHookBinding,
        nowMs: Long
    ) {
        val context = buildGatingContext(binding.metricSpec, featureStore, nowMs)
        val state = registry.stateFor(binding.metricSpec.id)
        if (context.activityContext == MetricActivityContext.UNKNOWN &&
            binding.metricSpec.gating.activityContext != MetricActivityContext.ANY
        ) {
            state.unknownContextSamples += 1
        }
        if (!contextEligibleForIngestion(binding.metricSpec, context)) {
            state.gatedSamples += 1
            val result = evaluate(binding, context, nowMs, forceRecalculate = false)
            MetricOutputMapper.publish(featureStore, binding.metricSpec.selectedOutputs, result, nowMs)
            return
        }
        val eventContext = context.copy(isFresh = true, lastSampleAgeMs = 0L)
        val normalized = when {
            binding.sourceHook != null -> binding.sourceHook.normalize(evt.payload as Any, eventContext)
            else -> dispatchRouter.extractScalar(evt, binding.metricSpec)?.let {
                NormalizedMetricSample(it, nowMs)
            }
        }
        val valid = normalized?.takeIf { isValidValue(binding.metricSpec, it.value) }
        if (normalized != null && valid == null) state.invalidSamples += 1
        if (valid != null) {
            state.acceptedSamples += 1
            state.latestSample = valid
            historyStore.appendHotSample(binding.metricSpec.id, valid)
        }
        val result = evaluate(binding, eventContext, nowMs, forceRecalculate = false)
        MetricOutputMapper.publish(featureStore, binding.metricSpec.selectedOutputs, result, nowMs)
    }

    internal fun onTick(nowMs: Long, featureStore: FeatureStore) {
        if (!legacyBaselineEnabled) return
        synchronized(runtimeLock) {
            observeActivityContext(featureStore, nowMs)
            registry.allBindings().forEach { binding ->
            compactHistoryIfDue(binding.metricSpec.id, nowMs)
            val context = buildGatingContext(binding.metricSpec, featureStore, nowMs)
            val result = evaluate(binding, context, nowMs, forceRecalculate = false)
            MetricOutputMapper.publish(featureStore, binding.metricSpec.selectedOutputs, result, nowMs)
            }
        }
    }

    /**
     * Allows the existing CEP HR rules to use personal resting-HR thresholds after the
     * baseline is established. Profile/static thresholds remain in place beforehand.
     */
    fun applyRestingHrThresholds(featureStore: FeatureStore, nowMs: Long) {
        if (!legacyBaselineEnabled) return
        synchronized(runtimeLock) {
            if (featureStore.str("selected.baseline.hr.state") != MetricEstablishmentState.ESTABLISHED.name) {
                return
            }
            val high = featureStore.latestAsDouble("selected.thresholds.hr.high_bpm") ?: return
            val low = featureStore.latestAsDouble("selected.thresholds.hr.low_bpm") ?: return
            featureStore.put("thresholds.hr.rest_high_bpm", high, nowMs)
            featureStore.put("thresholds.hr.rest_low_bpm", low, nowMs)
        }
    }

    fun calculateNow(metricId: String, nowMs: Long, featureStore: FeatureStore): MetricComputationResult? {
        if (!legacyBaselineEnabled) return null
        synchronized(runtimeLock) {
            observeActivityContext(featureStore, nowMs)
            val binding = registry.findById(metricId) ?: return null
            val context = buildGatingContext(binding.metricSpec, featureStore, nowMs)
            val result = evaluate(binding, context, nowMs, forceRecalculate = true)
            MetricOutputMapper.publish(featureStore, binding.metricSpec.selectedOutputs, result, nowMs)
            return result
        }
    }

    fun calculateAllNow(nowMs: Long, featureStore: FeatureStore): Map<String, MetricComputationResult> {
        if (!legacyBaselineEnabled) return emptyMap()
        synchronized(runtimeLock) {
            observeActivityContext(featureStore, nowMs)
            return registry.allBindings().associate { binding ->
                val context = buildGatingContext(binding.metricSpec, featureStore, nowMs)
                val result = evaluate(binding, context, nowMs, forceRecalculate = true)
                MetricOutputMapper.publish(featureStore, binding.metricSpec.selectedOutputs, result, nowMs)
                binding.metricSpec.id to result
            }
        }
    }

    /**
     * Reads hydrated state and already-published outputs only. This never recalculates
     * or persists data when the inspection screen is opened.
     */
    fun inspectAll(nowMs: Long, featureStore: FeatureStore): Map<String, MetricInspectionSnapshot> {
        if (!legacyBaselineEnabled) return emptyMap()
        synchronized(runtimeLock) {
            return registry.allBindings().associate { binding ->
                val spec = binding.metricSpec
                val state = registry.stateFor(spec.id)
                restoreCachedCore(
                    spec.id,
                    FileBackedMetricCalculationCacheStore.configHash(spec),
                    state
                )
                val core = state.cachedCore
                val mapping = spec.selectedOutputs.slotsToSelectedKeys
                val selectedState = mapping["baseline_state"]?.let(featureStore::any)
                    ?.toString()
                    ?.let { value -> runCatching { MetricEstablishmentState.valueOf(value) }.getOrNull() }
                val establishmentState = selectedState ?: if (core == null) {
                    MetricEstablishmentState.NO_DATA
                } else {
                    MetricEstablishmentState.WARMING
                }
                spec.id to MetricInspectionSnapshot(
                    metricId = spec.id,
                    displayName = displayNameFor(spec.id),
                    unit = unitFor(spec.id),
                    currentValue = mapping["current_value"]?.let(featureStore::latestAsDouble)
                        ?: core?.latestValue,
                    baseline = core?.baseline,
                    state = establishmentState,
                    confidence = mapping["baseline_confidence"]?.let(featureStore::latestAsDouble),
                    calculatedAtMs = state.coreCalculatedAtMs.takeIf { it > 0L },
                    dataThroughTsMs = core?.latestTsMs,
                    coveredSeconds = core?.coveredSeconds ?: 0L,
                    activeDays = core?.activeDays ?: 0,
                    trendDirection = mapping["trend_direction"]?.let(featureStore::any)?.toString()
                )
            }
        }
    }

    /** Copies the authoritative metric files into a ZIP without changing them. */
    fun exportFiles(destination: File) {
        val stagingDir = appContext?.cacheDir?.let {
            File(it, "metric_export_stage_${System.currentTimeMillis()}").also(File::mkdirs)
        }
        try {
            synchronized(runtimeLock) {
                snapshotExportFiles(stagingDir)
            }
            destination.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(destination)).use { zip ->
                addStagedFiles(zip, stagingDir)
                zip.putNextEntry(ZipEntry("export_metadata.txt"))
                zip.write(
                    buildString {
                        appendLine("exported_at_ms=${System.currentTimeMillis()}")
                        appendLine("metric_ids=${specs.joinToString(",") { it.id }}")
                    }.toByteArray()
                )
                zip.closeEntry()
            }
        } finally {
            stagingDir?.deleteRecursively()
        }
    }

    private fun snapshotExportFiles(stagingDir: File?) {
        if (stagingDir == null) return
        listOf(
            "metric_history" to appContext?.let { File(it.filesDir, "metric_history") },
            "metric_results" to appContext?.let { File(it.filesDir, "metric_results") }
        ).forEach { (entryRoot, directory) ->
            directory?.listFiles()?.filter { it.isFile }?.forEach { file ->
                val target = File(stagingDir, "$entryRoot/${file.name}")
                target.parentFile?.mkdirs()
                file.inputStream().use { input -> target.outputStream().use(input::copyTo) }
            }
        }
    }

    private fun addStagedFiles(zip: ZipOutputStream, stagingDir: File?) {
        stagingDir?.walkTopDown()?.filter { it.isFile }?.forEach { file ->
            val entryName = file.relativeTo(stagingDir).path.replace(File.separatorChar, '/')
            zip.putNextEntry(ZipEntry(entryName))
            file.inputStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun displayNameFor(metricId: String): String = when (metricId) {
        "hrv.rmssd_ms" -> "HRV RMSSD"
        "hr.resting_bpm.resting" -> "Resting Heart Rate"
        "hr.active_bpm" -> "Active Heart Rate"
        "skin_temp.peripheral_median_c" -> "Peripheral Skin Temperature"
        else -> metricId
    }

    private fun unitFor(metricId: String): String = when (metricId) {
        "hrv.rmssd_ms" -> "ms"
        "skin_temp.peripheral_median_c" -> "°C"
        else -> "bpm"
    }

    private fun compactHistoryIfDue(metricId: String, nowMs: Long) {
        val lastRun = lastCompactionMs[metricId]
        if (lastRun != null && nowMs - lastRun < COMPACTION_INTERVAL_MS) return
        lastCompactionMs[metricId] = nowMs
        val hotCutoff = nowMs - HOT_WINDOW_MS
        val warmCutoff = nowMs - WARM_WINDOW_MS
        val coldCutoff = nowMs - COLD_WINDOW_MS
        historyStore.compactHotToWarm(metricId, hotCutoff)
        historyStore.compactWarmToCold(metricId, warmCutoff)
        historyStore.evictOlderThan(metricId, coldCutoff)
    }

    private fun assembleHistoryView(metricId: String, nowMs: Long): MetricHistoryView {
        val version = historyStore.historyVersion(metricId)
        val cached = historyCache[metricId]?.takeIf { it.version == version }
        val fullView = cached?.fullView ?: MetricHistoryView(
            hotSamples = historyStore.loadHotSamples(metricId, Long.MIN_VALUE),
            warmBuckets = historyStore.loadWarmBuckets(metricId, Long.MIN_VALUE),
            coldBuckets = historyStore.loadColdBuckets(metricId, Long.MIN_VALUE),
            hotWindowSec = HOT_WINDOW_MS / 1000L,
            warmWindowSec = WARM_WINDOW_MS / 1000L,
            coldWindowSec = COLD_WINDOW_MS / 1000L
        )
        if (cached == null) historyCache[metricId] = CachedHistory(version, fullView)
        return MetricHistoryView(
            hotSamples = fullView.hotSamples.filter { it.tsMs >= nowMs - HOT_WINDOW_MS },
            warmBuckets = fullView.warmBuckets.filter { it.bucketStartTs >= nowMs - WARM_WINDOW_MS },
            coldBuckets = fullView.coldBuckets.filter { it.bucketStartTs >= nowMs - COLD_WINDOW_MS },
            hotWindowSec = fullView.hotWindowSec,
            warmWindowSec = fullView.warmWindowSec,
            coldWindowSec = fullView.coldWindowSec
        )
    }

    private fun evaluate(
        binding: MetricSourceHookBinding,
        context: MetricGatingContext,
        nowMs: Long,
        forceRecalculate: Boolean
    ): MetricComputationResult {
        val state = registry.stateFor(binding.metricSpec.id)
        val processor = binding.processor as? BaselineTrendProcessor
            ?: return binding.processor.compute(binding.metricSpec, null, context, state, null)
        val configHash = FileBackedMetricCalculationCacheStore.configHash(binding.metricSpec)
        restoreCachedCore(binding.metricSpec.id, configHash, state)
        val policy = binding.metricSpec.baseline.calculation
        val coreAgeSec = if (state.coreCalculatedAtMs == 0L) Long.MAX_VALUE else {
            ((nowMs - state.coreCalculatedAtMs).coerceAtLeast(0L)) / 1000L
        }
        val warming = !state.establishedLatch
        val ageLimit = if (warming) policy.warmingMaxAgeSec else policy.establishedMaxAgeSec
        val newSamples = state.acceptedSamples - state.samplesAtLastCoreCalculation
        val refresh = forceRecalculate || state.cachedCore == null ||
            coreAgeSec >= ageLimit || (warming && newSamples >= policy.warmingSampleTrigger)
        if (refresh) {
            val history = assembleHistoryView(binding.metricSpec.id, nowMs)
            val core = processor.computeCore(binding.metricSpec, history)
            state.cachedCore = core
            state.coreCalculatedAtMs = nowMs
            state.samplesAtLastCoreCalculation = state.acceptedSamples
            state.latestSample = state.latestSample ?: core.latestTsMs?.let {
                core.latestValue?.let { value -> NormalizedMetricSample(value, it) }
            }
            calculationCacheStore.save(
                binding.metricSpec.id,
                configHash,
                CachedMetricCore(nowMs, state.samplesAtLastCoreCalculation, core)
            )
        }
        return processor.overlay(binding.metricSpec, state.cachedCore ?: MetricBaselineCore(), context, state)
    }

    private fun restoreCachedCore(metricId: String, configHash: String, state: MetricRuntimeState) {
        if (state.cachedCore != null) return
        val cached = calculationCacheStore.load(metricId, configHash) ?: return
        state.cachedCore = cached.core
        state.coreCalculatedAtMs = cached.calculatedAtMs
        state.samplesAtLastCoreCalculation = cached.samplesAtCalculation
        state.acceptedSamples = maxOf(state.acceptedSamples, cached.samplesAtCalculation)
        if (state.latestSample == null && cached.core.latestTsMs != null && cached.core.latestValue != null) {
            state.latestSample = NormalizedMetricSample(cached.core.latestValue, cached.core.latestTsMs)
        }
    }

    private fun buildGatingContext(spec: MetricSpec, featureStore: FeatureStore, nowMs: Long): MetricGatingContext {
        val onWrist = (featureStore.latestAsDouble("offbody.on_wrist") ?: 0.0) >= 0.5
        val still = (featureStore.any("activity.state") as? String) == "STILL"
        val sleeping = (featureStore.any("sleep.is_sleeping") as? Boolean) == true
        val freshnessLimitSec = spec.gating.freshWithinSec
        val sourceAgeSec = featureStore.ageSeconds(spec.source, nowMs)
        val isFresh = freshnessLimitSec == null || (sourceAgeSec != null && sourceAgeSec <= freshnessLimitSec)
        val sourceAgeMs = sourceAgeSec?.let { (it * 1000.0).toLong() }
        return MetricGatingContext(
            onWrist = onWrist,
            isStill = still,
            isSleeping = sleeping,
            isAwake = !sleeping,
            lastSampleAgeMs = sourceAgeMs,
            nowMs = nowMs,
            isFresh = isFresh,
            rawSourceKey = spec.source,
            hrRestBpm = featureStore.latestAsDouble("profile.hr_rest_bpm"),
            hrMaxBpm = featureStore.latestAsDouble("profile.hr_max_bpm"),
            activityContext = observedActivityContext,
            contextStableForSec = ((nowMs - (contextSinceMs ?: nowMs)).coerceAtLeast(0L)) / 1000L,
            lastActiveAgeSec = lastActiveAtMs?.let { ((nowMs - it).coerceAtLeast(0L)) / 1000L }
        )
    }

    private fun contextEligibleForIngestion(spec: MetricSpec, context: MetricGatingContext): Boolean {
        if (context.activityContext == MetricActivityContext.UNKNOWN &&
            spec.gating.activityContext != MetricActivityContext.ANY
        ) return false
        if (spec.gating.onWrist && !context.onWrist) return false
        if (spec.gating.stillOrSleeping && !(context.isStill || context.isSleeping)) return false
        if (spec.gating.activityContext != MetricActivityContext.ANY &&
            spec.gating.activityContext != context.activityContext
        ) return false
        if (context.contextStableForSec < spec.gating.minContextDwellSec) return false
        if (spec.gating.activityContext == MetricActivityContext.RESTING &&
            (context.lastActiveAgeSec ?: Long.MAX_VALUE) < spec.gating.restingCooldownSec
        ) return false
        return true
    }

    private fun observeActivityContext(featureStore: FeatureStore, nowMs: Long) {
        val onWrist = (featureStore.latestAsDouble("offbody.on_wrist") ?: 0.0) >= 0.5
        val sleeping = (featureStore.any("sleep.is_sleeping") as? Boolean) == true
        val activityState = featureStore.any("activity.state") as? String
        if (!onWrist) {
            if (unknownOffWristSinceMs == null) unknownOffWristSinceMs = nowMs
            if (observedActivityContext != MetricActivityContext.UNKNOWN &&
                nowMs - unknownOffWristSinceMs!! < UNKNOWN_OFF_WRIST_GRACE_MS
            ) return
        } else {
            unknownOffWristSinceMs = null
        }
        val next = when {
            !onWrist || activityState == null -> MetricActivityContext.UNKNOWN
            sleeping || activityState == "STILL" -> MetricActivityContext.RESTING
            else -> MetricActivityContext.ACTIVE
        }
        if (next != observedActivityContext) {
            observedActivityContext = next
            contextSinceMs = nowMs
        }
        if (next == MetricActivityContext.ACTIVE) lastActiveAtMs = nowMs
    }

    private fun isValidValue(spec: MetricSpec, value: Double): Boolean {
        if (!value.isFinite()) return false
        spec.validity.minValue?.let { if (value < it) return false }
        spec.validity.maxValue?.let { if (value > it) return false }
        return true
    }

    private suspend fun mergeRuntimeOverrides(metricSpecs: List<MetricSpec>): List<MetricSpec> {
        // TODO(WEAR-470): Wire concrete DataRepository metric override keys once schema is finalized.
        // Current repository does not expose metric-spec override objects, so JSON remains authoritative.
        // Keeping this explicit hook preserves precedence and prevents hidden behavior.
        return metricSpecs
    }

    private fun validateSpecs(metricSpecs: List<MetricSpec>) {
        val duplicateIds = metricSpecs.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) { "Duplicate metric ids: $duplicateIds" }
        metricSpecs.forEach { spec ->
            require(spec.id.isNotBlank()) { "Metric id required" }
            require(spec.source.isNotBlank()) { "Metric source required for id=${spec.id}" }
            require(spec.baseline.minSamples > 0) { "baseline.min_samples must be > 0 for metric=${spec.id}" }
            require(spec.baseline.windowSec > 0) { "baseline.window_sec must be > 0 for metric=${spec.id}" }
            require(spec.baseline.maxStalenessSec > 0) { "baseline.max_staleness_sec must be > 0 for metric=${spec.id}" }
            require(spec.baseline.coverageTargetSec == null || spec.baseline.coverageTargetSec > 0L) {
                "baseline.coverage_target_sec must be > 0 for metric=${spec.id}"
            }
            require(spec.baseline.stabilityWeight >= 0.0) {
                "baseline.stability_weight must be >= 0 for metric=${spec.id}"
            }
            with(spec.baseline.thresholdPolicy) {
                require(lowAlertPercent in 0.0..<1.0 && lowWarningPercent in 0.0..<1.0 &&
                    highWarningPercent in 0.0..<1.0 && highAlertPercent in 0.0..<1.0) {
                    "baseline threshold percentages must be in [0,1) for metric=${spec.id}"
                }
                require(lowWarningPercent <= lowAlertPercent && highWarningPercent <= highAlertPercent) {
                    "warning thresholds must not exceed alert thresholds for metric=${spec.id}"
                }
            }
            require(spec.baseline.estimator in setOf("trimmed_mean", "time_weighted_mean")) {
                "Unsupported baseline.estimator=${spec.baseline.estimator} for metric=${spec.id}"
            }
            require(spec.baseline.transform in setOf("none", "ln")) {
                "Unsupported baseline.transform=${spec.baseline.transform} for metric=${spec.id}"
            }
            require(spec.trend.shortWindowSec < spec.trend.longWindowSec) {
                "trend.short_window_sec must be < trend.long_window_sec for metric=${spec.id}"
            }
            require(spec.baseline.windowSec >= spec.trend.longWindowSec) {
                "baseline.window_sec must be >= trend.long_window_sec for metric=${spec.id}"
            }
            require(spec.trend.enterPercent > spec.trend.returnToStableInnerPercent) {
                "trend.enter_percent must be > trend.return_to_stable_inner_percent for metric=${spec.id}"
            }
            val freshWithin = spec.gating.freshWithinSec
            if (freshWithin != null) {
                require(freshWithin > 0) {
                    "gating.fresh_within_sec must be > 0 for metric=${spec.id}"
                }
                require(freshWithin <= spec.baseline.maxStalenessSec) {
                    "gating.fresh_within_sec must be <= baseline.max_staleness_sec for metric=${spec.id}"
                }
            }
            with(spec.baseline.confidenceThresholds) {
                require(warming < readyLow && readyLow < medium && medium < high) {
                    "confidence thresholds must be strictly increasing for metric=${spec.id}"
                }
            }
            require(spec.validity.minValue == null || spec.validity.maxValue == null ||
                spec.validity.minValue <= spec.validity.maxValue) {
                "validity.min_value must be <= validity.max_value for metric=${spec.id}"
            }
            with(spec.baseline.establishment) {
                require(minCoverageSec >= 0L) { "establishment.min_coverage_sec must be >= 0 for metric=${spec.id}" }
                require(minActiveDays >= 0) { "establishment.min_active_days must be >= 0 for metric=${spec.id}" }
                require(minConfidence in 0.0..1.0) { "establishment.min_confidence must be in [0,1] for metric=${spec.id}" }
                require(maxGapSec >= 0L) { "establishment.max_gap_sec must be >= 0 for metric=${spec.id}" }
            }
            require(spec.gating.minContextDwellSec >= 0L) {
                "gating.min_context_dwell_sec must be >= 0 for metric=${spec.id}"
            }
            require(spec.gating.restingCooldownSec >= 0L) {
                "gating.resting_cooldown_sec must be >= 0 for metric=${spec.id}"
            }
            val contractSlots = outputContracts[spec.processor]?.canonicalSlots
                ?: error("Unknown processor contract for metric=${spec.id}")
            require(spec.selectedOutputs.slotsToSelectedKeys.keys.all { it in contractSlots }) {
                "Unknown selected-output slot for metric=${spec.id}"
            }
            if (spec.sourceHookId != null) {
                require(hookRegistry.resolveOrNull(spec.sourceHookId) != null) {
                    "Unknown source hook id=${spec.sourceHookId} for metric=${spec.id}"
                }
            }
        }
    }

}

class MetricDispatchRouter(metricSpecs: List<MetricSpec>) {
    private val sourcesByTopic: Map<String, Set<String>> = metricSpecs
        .groupBy { it.topic ?: defaultTopicFor(it.source) }
        .mapValues { (_, specs) -> specs.map { it.source }.toSet() }

    fun sourcesForEvent(evt: CepEvent<*>): Set<String> {
        return sourcesByTopic[evt.topic].orEmpty()
    }

    fun extractScalar(evt: CepEvent<*>, spec: MetricSpec): Double? {
        val field = spec.scalarField ?: return when (spec.source) {
            "heart_rate" -> (evt.payload as? HeartRatePayload)?.bpm
            else -> null
        }
        if (evt.payload is HeartRatePayload && field == "bpm") {
            return (evt.payload as HeartRatePayload).bpm
        }
        if (evt.payload is NumericSample && (field == "value" || field == evt.payload.key)) {
            return (evt.payload as NumericSample).value
        }
        val map = evt.payload as? Map<*, *>
        return (map?.get(field) as? Number)?.toDouble()
    }

    private fun defaultTopicFor(source: String): String = when (source) {
        "hrv.rmssd_ms" -> "hrv"
        "heart_rate" -> "heart_rate"
        else -> source
    }
}
