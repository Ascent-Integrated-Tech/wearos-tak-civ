package com.aitech.weartak_civ.cep.metric

import android.content.Context
import com.aitech.weartak_civ.cep.engine.FeatureStore
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.NumericSample
import com.aitech.weartak_civ.cep.model.payloads.HeartRatePayload
import com.aitech.weartak_civ.data.DataRepository
import java.io.File

class MetricRuntimeOrchestrator(
    private val dataRepository: DataRepository?,
    metricSpecs: List<MetricSpec>,
    appContext: Context? = null,
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
        listOf(SamsungHrvRmssdHook(), HeartRateReservePercentHook())
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

    fun onEvent(evt: CepEvent<*>, featureStore: FeatureStore) {
        synchronized(runtimeLock) {
            val nowMs = evt.ts
            observeActivityContext(featureStore, nowMs)
            dispatchRouter.sourcesForEvent(evt).forEach { source ->
            val bindings = registry.findBySource(source)
            bindings.forEach { binding ->
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
                    return@forEach
                }
                val eventContext = context.copy(
                    isFresh = true,
                    lastSampleAgeMs = 0L
                )
                val normalized = when {
                    binding.sourceHook != null -> binding.sourceHook.normalize(evt.payload as Any, eventContext)
                    else -> dispatchRouter.extractScalar(evt, binding.metricSpec)?.let {
                        NormalizedMetricSample(it, nowMs)
                    }
                }
                val valid = normalized?.takeIf {
                    isValidValue(binding.metricSpec, it.value)
                }
                if (normalized != null && valid == null) {
                    state.invalidSamples += 1
                }
                if (valid != null) {
                    state.acceptedSamples += 1
                    state.latestSample = valid
                    historyStore.appendHotSample(binding.metricSpec.id, valid)
                }
                val result = evaluate(binding, eventContext, nowMs, forceRecalculate = false)
                MetricOutputMapper.publish(featureStore, binding.metricSpec.selectedOutputs, result, nowMs)
            }
            }
        }
    }

    internal fun onTick(nowMs: Long, featureStore: FeatureStore) {
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

    fun calculateNow(metricId: String, nowMs: Long, featureStore: FeatureStore): MetricComputationResult? {
        synchronized(runtimeLock) {
            observeActivityContext(featureStore, nowMs)
            val binding = registry.findById(metricId) ?: return null
            val context = buildGatingContext(binding.metricSpec, featureStore, nowMs)
            return evaluate(binding, context, nowMs, forceRecalculate = true)
        }
    }

    fun calculateAllNow(nowMs: Long, featureStore: FeatureStore): Map<String, MetricComputationResult> {
        synchronized(runtimeLock) {
            observeActivityContext(featureStore, nowMs)
            return registry.allBindings().associate { binding ->
                val context = buildGatingContext(binding.metricSpec, featureStore, nowMs)
                binding.metricSpec.id to evaluate(binding, context, nowMs, forceRecalculate = true)
            }
        }
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
