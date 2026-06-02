package com.aitech.weartak_civ.cep.engine

/**
 * Single-tick metric baseline/trend pipeline shared by [CepEngine] and JVM tests.
 */
internal object MetricBaselineTick {

    fun updateMetricBaselineAndTrends(
        adapter: BaselineMetricAdapter,
        stateData: MetricAggregationStateData,
        featureStore: FeatureStore,
        profileStore: ProfileStore,
        nowMs: Long,
    ) {
        val sample = featureStore.doubleIfFresh(adapter.sourceKey, maxAgeSec = 10.0, nowMs = nowMs)
        val context = MetricRuntimeContext(
            isStill = featureStore.str("activity.state") == "STILL",
            isSleeping = featureStore.bool("sleep.is_sleeping") == true,
            onWrist = (featureStore.double("offbody.on_wrist") ?: 0.0) >= 0.5
        )

        if (sample == null) {
            publishUnknownTrendIfStale(adapter, stateData, featureStore, nowMs, force = false)
            return
        }
        if (!context.onWrist) {
            handleOffWristInvalidation(adapter, stateData, featureStore, nowMs)
            return
        }
        val normalized = adapter.normalize(sample, nowMs, context) ?: return
        if (!normalized.baselineEligible) return

        if (nowMs - stateData.lastSampleAcceptedMs < adapter.sampleCadenceMs) return
        stateData.lastSampleAcceptedMs = nowMs

        val qualifiedValue = MetricAggregationStateOps.appendSampleAndMaybeEmitQualifiedValue(
            state = stateData,
            nowMs = nowMs,
            value = normalized.value,
            qualifiedWindowMs = adapter.qualifiedWindowMs
        ) ?: return
        stateData.qualifiedWindows.addLast(nowMs to qualifiedValue)

        val longCutoffMs = nowMs - (adapter.longWindowSec * 1000L)
        while (stateData.qualifiedWindows.isNotEmpty() && stateData.qualifiedWindows.first().first < longCutoffMs) {
            stateData.qualifiedWindows.removeFirst()
        }

        val snapshot = MetricBaselineCalculator.computeBaseline(adapter, stateData.qualifiedWindows.toList(), nowMs)
        profileStore.putMetricBaseline(
            ProfileStore.MetricBaselineSnapshot(
                metricId = adapter.metricId,
                value = snapshot.value,
                sampleCount = snapshot.sampleCount,
                confidence = snapshot.confidence,
                status = snapshot.status,
                windowStartTs = snapshot.windowStartTs,
                windowEndTs = snapshot.windowEndTs,
                lastUpdatedMs = snapshot.lastUpdatedTs ?: nowMs
            )
        )
        publishMetricOutputs(adapter, snapshot, stateData.qualifiedWindows.toList(), nowMs, stateData, featureStore)
        stateData.lastOutputMs = nowMs
    }

    private fun publishMetricOutputs(
        adapter: BaselineMetricAdapter,
        baseline: ComputedBaseline,
        windows: List<Pair<Long, Double>>,
        nowMs: Long,
        stateData: MetricAggregationStateData,
        featureStore: FeatureStore,
    ) {
        MetricFeaturePublisher.baselineMap(
            metricId = adapter.metricId,
            value = baseline.value,
            sampleCount = baseline.sampleCount,
            confidence = baseline.confidence,
            status = baseline.status
        ).forEach { (k, v) -> if (v != null) featureStore.put(k, v, nowMs) }

        val trend = MetricBaselineCalculator.computeTrend(
            adapter,
            baseline,
            windows,
            nowMs,
            stateData.lastTrendDirection
        ) ?: return

        stateData.lastTrendDirection = trend.direction

        val trendPrefix = "trend.${adapter.metricId}"
        trend.deviationFromBaseline?.let { featureStore.put(trendPrefix + ".deviation_from_baseline", it, nowMs) }
        trend.percentDeviationFromBaseline?.let { featureStore.put(trendPrefix + ".percent_deviation_from_baseline", it, nowMs) }
        trend.shortTermAverage?.let { featureStore.put(trendPrefix + ".short_term", it, nowMs) }
        trend.longTermAverage?.let { featureStore.put(trendPrefix + ".long_term", it, nowMs) }
        featureStore.put(trendPrefix + ".direction", trend.direction.name, nowMs)
        adapter.selectedOutputs(baseline, trend).forEach { (k, v) -> if (v != null) featureStore.put(k, v, nowMs) }
    }

    private fun handleOffWristInvalidation(
        adapter: BaselineMetricAdapter,
        stateData: MetricAggregationStateData,
        featureStore: FeatureStore,
        nowMs: Long,
    ) {
        MetricAggregationStateOps.invalidateActiveWindow(stateData)
        publishUnknownTrendIfStale(adapter, stateData, featureStore, nowMs, force = true)
    }

    private fun publishUnknownTrendIfStale(
        adapter: BaselineMetricAdapter,
        stateData: MetricAggregationStateData,
        featureStore: FeatureStore,
        nowMs: Long,
        force: Boolean,
    ) {
        val staleThresholdMs = adapter.sampleCadenceMs * 3
        if (!force) {
            if (stateData.lastSampleAcceptedMs == 0L || nowMs - stateData.lastSampleAcceptedMs < staleThresholdMs) return
            if (stateData.lastOutputMs != 0L && nowMs - stateData.lastOutputMs < staleThresholdMs) return
        } else {
            if (stateData.lastOutputMs != 0L && nowMs - stateData.lastOutputMs < 500L) return
        }
        val publication = MetricFeaturePublisher.unknownTrendPublication(adapter)
        publication.outputs.forEach { (k, v) -> if (v != null) featureStore.put(k, v, nowMs) }
        publication.clearKeys.forEach { featureStore.clear(it) }
        stateData.lastOutputMs = nowMs
        stateData.lastTrendDirection = null
    }
}
