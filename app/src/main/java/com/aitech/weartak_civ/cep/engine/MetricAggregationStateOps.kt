package com.aitech.weartak_civ.cep.engine

data class MetricAggregationStateData(
    var activeWindowStartMs: Long? = null,
    val activeWindowValues: MutableList<Double> = mutableListOf(),
    val qualifiedWindows: ArrayDeque<Pair<Long, Double>> = ArrayDeque(),
    var lastSampleAcceptedMs: Long = 0L,
    var lastOutputMs: Long = 0L,
    /** Last published trend direction for hysteresis; cleared when unknown/off-wrist outputs publish. */
    var lastTrendDirection: TrendDirection? = null,
)

object MetricAggregationStateOps {
    fun invalidateActiveWindow(state: MetricAggregationStateData) {
        state.activeWindowStartMs = null
        state.activeWindowValues.clear()
    }

    fun appendSampleAndMaybeEmitQualifiedValue(
        state: MetricAggregationStateData,
        nowMs: Long,
        value: Double,
        qualifiedWindowMs: Long
    ): Double? {
        val windowStart = state.activeWindowStartMs ?: nowMs.also { state.activeWindowStartMs = it }
        state.activeWindowValues.add(value)
        if (nowMs - windowStart < qualifiedWindowMs) return null

        val qualifiedValue = state.activeWindowValues.average()
        state.activeWindowValues.clear()
        state.activeWindowStartMs = nowMs
        return qualifiedValue
    }
}
