package com.aitech.weartak_civ.service

/** Opportunistic refill only. Fixed identities prevent churn from growing aggregation storage. */
internal class ContextDetailBudget {
    private var tokens = 16.0
    private var at: Long? = null
    private data class Summary(
        val event: DiagnosticEvent, var latest: Map<DiagnosticMetric, Long>,
        val first: Long, var last: Long, var count: Long
    )
    private val suppressed = mutableMapOf<Triple<DiagnosticEvent, Long, Boolean>, Summary>()

    fun admit(event: DiagnosticEvent, metrics: Map<DiagnosticMetric, Long>, now: Long): Boolean {
        at?.let { tokens = (tokens + (now - it).coerceAtLeast(0) / 1000.0).coerceAtMost(16.0) }
        at = now
        val id = metrics[DiagnosticMetric.SETTING_ID] ?: metrics[DiagnosticMetric.RESOURCE_ID] ?: 0
        require(id in 0..31)
        val resourceSummary = event == DiagnosticEvent.RESOURCE_LIFETIME && metrics[DiagnosticMetric.ACTION] == 10L
        val reserve = if (event == DiagnosticEvent.POWER_CONTEXT) 0 else 2
        if (!resourceSummary && tokens >= 1 + reserve) {
            tokens--
            return true
        }
        // Cumulative resource unions must not be overwritten by the latest suppressed detail.
        val key = Triple(event, id, resourceSummary)
        val prior = suppressed[key]
        if (prior == null) suppressed[key] = Summary(event, metrics.toMap(), now, now, 1)
        else {
            prior.latest = metrics.toMap()
            prior.last = now
            if (prior.count < Long.MAX_VALUE) prior.count++
        }
        return false
    }

    fun flush(): List<Pair<DiagnosticEvent, Map<DiagnosticMetric, Long>>> =
        suppressed.values.map { summary ->
            summary.event to (summary.latest + mapOf(
                DiagnosticMetric.AGGREGATED to 1L,
                DiagnosticMetric.SUPPRESSED to summary.count,
                DiagnosticMetric.FIRST_MS to summary.first,
                DiagnosticMetric.LAST_MS to summary.last
            ))
        }.also { suppressed.clear() }

    fun reset() {
        tokens = 16.0
        at = null
        suppressed.clear()
    }
}
