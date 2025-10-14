package com.aitech.weartak_civ.cep.engine

import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.CepPayload
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.*
import com.aitech.weartak_civ.cep.util.ArrayRing
import com.aitech.weartak_civ.cep.util.DoubleRing
import com.aitech.weartak_civ.cep.util.MathOps

class WindowStore(private val tickHz: Int, private val ringConfig: RingConfig) {

    private val num = mutableMapOf<String, DoubleRing>()
    private val arr = mutableMapOf<String, ArrayRing>()
    private val latest = mutableMapOf<String, Any?>()

    private fun numCap() = ringConfig.maxSecondsNumeric * tickHz
    private fun arrCap() = ringConfig.maxSecondsArray * tickHz

    fun ingest(evt: CepEvent<out CepPayload>, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        when (val p = evt.payload) {
            is HeartRatePayload -> {
                num.getOrPut(Topics.HEART_RATE) { DoubleRing(numCap()) }.add(p.bpm)
                latest["heart_rate"] = p.bpm
                fs.put("heart_rate", p.bpm, tsMs)
            }
            is SkinTempPayload -> {
                arr.getOrPut(Topics.SKIN_TEMP) { ArrayRing(arrCap()) }.add(p.c_peripheral)
                latest["skin_temp"] = p.c_peripheral
            }
            is AmbientPayload -> {
                latest["ambient.temp_c"] = p.temp_c
                latest["ambient.wind_mps"] = p.wind_mps
                latest["ambient.humidity_pct"] = p.humidity_pct
                fs.putAll(mapOf(
                    "ambient.temp_c" to p.temp_c,
                    "ambient.wind_mps" to p.wind_mps,
                    "ambient.humidity_pct" to p.humidity_pct
                ))
            }
            is AccelPayload -> {
                num.getOrPut("accel.magnitude_g") { DoubleRing(numCap()) }.add(p.magnitude_g)
                latest["accel.magnitude_g"] = p.magnitude_g
                fs.put("accel.magnitude_g", p.magnitude_g, tsMs)
                p.cadence_spm?.let {
                    num.getOrPut("accel.cadence_spm") { DoubleRing(numCap()) }.add(it)
                    latest["accel.cadence_spm"] = it
                }
            }
            is ActivityPayload -> {
                latest["activity.state"] = p.state
                fs.put("activity.state", p.state, tsMs)
            }
            is Spo2Payload -> {
                latest["spo2"] = p.pct; fs.put("spo2", p.pct, tsMs)
            }
            is HrvPayload -> {
                val h = p.rmssd_ms
                if (h != null) { num.getOrPut("hrv.rmssd_ms") { DoubleRing(numCap()) }.add(h); fs.put(
                    "hrv.rmssd_ms",
                    h,
                    tsMs
                ) }
            }
            is ImmersionPayload -> {
                latest["immersion.wet_flag"] = p.wet_flag
                latest["immersion.immersion_flag"] = p.immersion_flag
                fs.putAll(mapOf("immersion.wet_flag" to p.wet_flag, "immersion.immersion_flag" to p.immersion_flag))
            }
            is SleepPayload -> {
                latest["sleep.is_sleeping"] = p.is_sleeping; fs.put(
                    "sleep.is_sleeping",
                    p.is_sleeping,
                    tsMs
                )
            }
            is OffBodyPayload -> {
                latest["offbody.on_wrist"] = p.on_wrist; fs.put(
                    "offbody.on_wrist",
                    p.on_wrist,
                    tsMs
                )
            }
        }
    }

    fun computeWindow(def: FeatureDef.Window, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val ring = num[def.source] ?: return
        val n = def.windowSec * tickHz
        val window = ring.tail(n)
        val v = when (def.op) {
            "min" -> window.minOrNull()
            "max" -> window.maxOrNull()
            "avg" -> if (window.isNotEmpty()) window.average() else null
            "rms" -> MathOps.rms(window)
            "median" -> if (window.isNotEmpty()) window.sorted()[window.size/2] else null
            else -> null
        }
        fs.put(def.id, v, tsMs)
    }

    fun computeMultiWindow(def: FeatureDef.MultiWindow, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val ring = arr[def.source] ?: return
        val n = def.windowSec * tickHz
        val window = ring.tail(n)
        if (window.isEmpty()) return
        // per-sample min across channels
        val perSampleMin = window.map { it.minOrNull() ?: Double.POSITIVE_INFINITY }
        val valWindow = when (def.op) {
            "min" -> perSampleMin.minOrNull()
            "max" -> perSampleMin.maxOrNull()
            "avg" -> if (perSampleMin.isNotEmpty()) perSampleMin.average() else null
            else -> perSampleMin.minOrNull()
        }
        fs.put(def.id, valWindow, tsMs)
    }

    fun computeSlopeMulti(def: FeatureDef.SlopeMulti, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val ring = arr[def.source] ?: return
        val n = def.windowSec * tickHz
        val window = ring.tail(n)
        if (window.isEmpty()) return
        val last = window.last()
        val minIdx = last.indices.minByOrNull { last[it] } ?: 0
        val series = window.mapNotNull { it.getOrNull(minIdx) }.toDoubleArray()
        val slopePerSample = MathOps.lsqSlope(series) ?: return
        val slope = if (def.unitsPerSec == "per_minute") slopePerSample * tickHz * 60.0 else slopePerSample * tickHz
        fs.put(def.id, slope, tsMs)
    }

    fun computeDelta(def: FeatureDef.Delta, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val ring = num[def.source] ?: return
        val now = ring.lastOrNull() ?: return
        val back = ring.getBack(def.horizonSec * tickHz) ?: return
        fs.put(def.id, now - back, tsMs)
    }
}


