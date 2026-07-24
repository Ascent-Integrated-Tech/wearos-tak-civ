package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.model.payloads.HrvPayload
import com.aitech.weartak_civ.cep.model.payloads.HeartRatePayload

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
                computeRmssdFromIbiMs(filtered)?.let {
                    val measurementTs = (payload["ts"] as? Number)?.toLong() ?: context.nowMs
                    NormalizedMetricSample(value = it, tsMs = measurementTs)
                }
            }
            else -> null
        }
    }
}

class HeartRateReservePercentHook : MetricSourceHook {
    override val hookId: String = "heart_rate_reserve_percent"

    override fun normalize(payload: Any, context: MetricGatingContext): NormalizedMetricSample? {
        val heartRate = (payload as? HeartRatePayload)?.bpm ?: return null
        val resting = context.hrRestBpm ?: return null
        val maximum = context.hrMaxBpm ?: return null
        val reserve = (maximum - resting).takeIf { it > 0.0 } ?: return null
        val percent = ((heartRate - resting) / reserve) * 100.0
        return percent.takeIf { it.isFinite() }?.let {
            NormalizedMetricSample(value = it, tsMs = payload.ts)
        }
    }
}
