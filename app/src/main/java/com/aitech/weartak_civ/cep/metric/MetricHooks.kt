package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.model.payloads.HrvPayload

class SamsungHrvRmssdHook : MetricSourceHook {
    override val hookId: String = "samsung_hrv_rmssd"

    override fun normalize(payload: Any, context: MetricGatingContext): NormalizedMetricSample? {
        if (!context.onWrist) return null
        return when (payload) {
            is HrvPayload -> payload.rmssd_ms?.let { value ->
                if (value.isFinite() && value > 0.0) NormalizedMetricSample(value = value, tsMs = payload.ts) else null
            }
            is Map<*, *> -> {
                val ibiList = (payload["ibi_list_ms"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }.orEmpty()
                val statusList = (payload["status_list"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty()
                val valid = if (statusList.isEmpty()) ibiList else ibiList.filterIndexed { idx, _ ->
                    statusList.getOrNull(idx) == 0
                }
                val filtered = valid.filter { it in 250.0..2000.0 }
                computeRmssdFromIbiMs(filtered)?.let { NormalizedMetricSample(value = it, tsMs = context.nowMs) }
            }
            else -> null
        }
    }
}
