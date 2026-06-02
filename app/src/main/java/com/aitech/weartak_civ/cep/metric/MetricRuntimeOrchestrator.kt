package com.aitech.weartak_civ.cep.metric

import android.content.Context
import com.aitech.weartak_civ.cep.engine.FeatureStore
import com.aitech.weartak_civ.cep.model.CepEvent
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
            File(System.getProperty("java.io.tmpdir"), "metric_history")
        }
    )
) {
    companion object {
        val BASELINE_TREND_CANONICAL_SLOTS: Set<String> = setOf(
            "trend_direction",
            "trend_short_term",
            "trend_long_term",
            "trend_deviation",
            "trend_percent_deviation",
            "low_threshold",
            "low_threshold_warn",
            "baseline_confidence"
        )
        private const val HOT_WINDOW_MS: Long = 72L * 60L * 60L * 1000L
        private const val WARM_WINDOW_MS: Long = 7L * 24L * 60L * 60L * 1000L
        private const val COLD_WINDOW_MS: Long = 30L * 24L * 60L * 60L * 1000L
    }

    constructor(metricSpecs: List<MetricSpec>) : this(
        dataRepository = null,
        metricSpecs = metricSpecs
    )

    private val hookRegistry = MetricHookRegistry(listOf(SamsungHrvRmssdHook()))
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
    private val dispatchRouter = MetricDispatchRouter()
    private val specs = metricSpecs

    suspend fun initialize() {
        val merged = mergeRuntimeOverrides(specs)
        validateSpecs(merged)
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
        val nowMs = evt.ts
        dispatchRouter.sourcesForEvent(evt).forEach { source ->
            val bindings = registry.findBySource(source)
            bindings.forEach { binding ->
                val context = buildGatingContext(binding.metricSpec, featureStore, nowMs)
                val normalized = when {
                    binding.sourceHook != null -> binding.sourceHook.normalize(evt.payload as Any, context)
                    else -> dispatchRouter.extractScalar(evt, source)?.let { NormalizedMetricSample(it, nowMs) }
                }
                if (normalized != null) {
                    historyStore.appendHotSample(binding.metricSpec.id, normalized)
                }
                compactHistory(binding.metricSpec.id, nowMs)
                val historyView = assembleHistoryView(binding.metricSpec.id, nowMs)

                val result = binding.processor.compute(
                    spec = binding.metricSpec,
                    sample = normalized,
                    gatingContext = context,
                    state = registry.stateFor(binding.metricSpec.id),
                    historyView = historyView
                )
                MetricOutputMapper.publish(featureStore, binding.metricSpec.selectedOutputs, result, nowMs)
            }
        }
    }

    internal fun onTick(nowMs: Long, featureStore: FeatureStore) {
        registry.allBindings().forEach { binding ->
            val context = buildGatingContext(binding.metricSpec, featureStore, nowMs)
            val result = binding.processor.compute(
                spec = binding.metricSpec,
                sample = null,
                gatingContext = context,
                state = registry.stateFor(binding.metricSpec.id),
                historyView = assembleHistoryView(binding.metricSpec.id, nowMs)
            )
            MetricOutputMapper.publish(featureStore, binding.metricSpec.selectedOutputs, result, nowMs)
        }
    }

    private fun compactHistory(metricId: String, nowMs: Long) {
        val hotCutoff = nowMs - HOT_WINDOW_MS
        val warmCutoff = nowMs - WARM_WINDOW_MS
        val coldCutoff = nowMs - COLD_WINDOW_MS
        historyStore.compactHotToWarm(metricId, hotCutoff)
        historyStore.compactWarmToCold(metricId, warmCutoff)
        historyStore.evictOlderThan(metricId, coldCutoff)
    }

    private fun assembleHistoryView(metricId: String, nowMs: Long): MetricHistoryView {
        return MetricHistoryView(
            hotSamples = historyStore.loadHotSamples(metricId, nowMs - HOT_WINDOW_MS),
            warmBuckets = historyStore.loadWarmBuckets(metricId, nowMs - WARM_WINDOW_MS),
            coldBuckets = historyStore.loadColdBuckets(metricId, nowMs - COLD_WINDOW_MS),
            hotWindowSec = HOT_WINDOW_MS / 1000L,
            warmWindowSec = WARM_WINDOW_MS / 1000L,
            coldWindowSec = COLD_WINDOW_MS / 1000L
        )
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
            rawSourceKey = spec.source
        )
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

class MetricDispatchRouter {
    fun sourcesForEvent(evt: CepEvent<*>): Set<String> {
        return when (evt.topic) {
            "hrv" -> setOf("hrv.rmssd_ms")
            "heart_rate" -> setOf("heart_rate")
            else -> emptySet()
        }
    }

    fun extractScalar(evt: CepEvent<*>, source: String): Double? {
        return when (source) {
            "heart_rate" -> (evt.payload as? HeartRatePayload)?.bpm
            "hrv.rmssd_ms" -> null
            else -> null
        }
    }
}
