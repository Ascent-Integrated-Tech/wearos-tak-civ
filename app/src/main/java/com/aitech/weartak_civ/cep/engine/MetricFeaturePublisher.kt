package com.aitech.weartak_civ.cep.engine

object MetricFeaturePublisher {
    data class UnknownTrendPublication(
        val outputs: Map<String, Any?>,
        val clearKeys: List<String>
    )

    fun baselineMap(
        metricId: String,
        value: Double?,
        sampleCount: Int,
        confidence: BaselineConfidence,
        status: BaselineStatus
    ): Map<String, Any?> {
        val prefix = "baseline.$metricId"
        val map = mutableMapOf<String, Any?>(
            "$prefix.sample_count" to sampleCount.toDouble(),
            "$prefix.confidence" to confidence.ordinal.toDouble(),
            "$prefix.status" to status.name
        )
        if (value != null) map[prefix] = value
        return map
    }

    fun unknownTrendPublication(adapter: BaselineMetricAdapter): UnknownTrendPublication {
        val map = mutableMapOf<String, Any?>("trend.${adapter.metricId}.direction" to TrendDirection.UNKNOWN.name)
        map.putAll(adapter.selectedOutputsForUnknownTrend())
        return UnknownTrendPublication(
            outputs = map,
            clearKeys = adapter.transientSelectedOutputKeysForUnknownTrend()
        )
    }
}
