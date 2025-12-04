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

    private val accelMag = "accel.magnitude_g"

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
                num.getOrPut(accelMag) { DoubleRing(numCap()) }.add(p.magnitude_g)
                latest[accelMag] = p.magnitude_g
                fs.put(accelMag, p.magnitude_g, tsMs)
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

            // ---- NEW: WindPayload (Topics.WIND) ----
            is WindPayload -> {
                p.speedMps?.let {
                    latest["ambient.wind_mps"] = it                   // keep backward-compatible key
                    fs.put("ambient.wind_mps", it, tsMs)
                }
                p.directionDeg?.let { fs.put("wind.direction_deg", it, tsMs) }
            }

            // ---- NEW: GyroPayload (Topics.GYRO) ----
            is GyroPayload -> {
                // Raw axes if you want them available:
                fs.put("gyro.x_dps", p.x.toDouble(), tsMs)
                fs.put("gyro.y_dps", p.y.toDouble(), tsMs)
                fs.put("gyro.z_dps", p.z.toDouble(), tsMs)
                // Optional RMS stream to support shiver detection windows:
                p.rms?.let {
                    num.getOrPut("gyro.rms_dps") { DoubleRing(numCap()) }.add(it.toDouble())
                    fs.put("gyro.rms_dps", it, tsMs)
                }
            }

            // ---- NEW: PpgPayload (Topics.PPG) ----
            is PpgPayload -> {
                // Amplitude is the one the rules use for collapse ratio:
                num.getOrPut("ppg.amplitude") { DoubleRing(numCap()) }.add(p.amplitude.toDouble())
                fs.put("ppg.amplitude", p.amplitude, tsMs)
                p.quality?.let { fs.put("ppg.quality", it, tsMs) }
                p.wavelength?.let { fs.put("ppg.wavelength", it, tsMs) }
            }

            // ---- NEW: HumidityPayload (Topics.HUMIDITY) ----
            is HumidityPayload -> {
                fs.put("humidity.relative_percent", p.relativePercent, tsMs)
                latest["humidity.relative_percent"] = p.relativePercent
            }

            // ---- NEW: WaterPayload (Topics.WATER) ----
            is WaterPayload -> {
                fs.put("water_sensor.wet", p.wet, tsMs)
                latest["water_sensor.wet"] = p.wet
                p.temperatureC?.let {
                    // Mirror to ambient.water_temp_c to match immersion override rule
                    fs.put("ambient.water_temp_c", it, tsMs)
                }
            }

            // ---- NEW: LightPayload (Topics.LIGHT) ----
            is LightPayload -> {
                fs.put("light.lux", p.lux, tsMs)
                latest["light.lux"] = p.lux
                p.covered?.let {
                    fs.put("als.covered", it, tsMs) // used by coverage/exposure logic
                }
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
        fs.putDerived(def.id, v, tsMs)
    }

    /*fun computeMultiWindow(def: FeatureDef.MultiWindow, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
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
        fs.putDerived(def.id, valWindow, tsMs)
    }*/

    fun computeMultiWindow(def: FeatureDef.MultiWindow, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val ring = arr[def.source] ?: return
        val n = def.windowSec * tickHz
        val window = ring.tail(n)
        if (window.isEmpty()) return

        // Combine channels per sample according to multiPolicy
        val perSampleAgg: List<Double> = when (def.multiPolicy) {
            "min" -> window.map { it.minOrNull() ?: Double.POSITIVE_INFINITY }
            "max" -> window.map { it.maxOrNull() ?: Double.NEGATIVE_INFINITY }
            else  -> window.map { it.minOrNull() ?: Double.POSITIVE_INFINITY } // default
        }

        val v = when (def.op) {
            "min" -> perSampleAgg.minOrNull()
            "max" -> perSampleAgg.maxOrNull()
            "avg" -> if (perSampleAgg.isNotEmpty()) perSampleAgg.average() else null
            "rms" -> MathOps.rms(perSampleAgg.toList())
            "median" -> if (perSampleAgg.isNotEmpty()) perSampleAgg.sorted()[perSampleAgg.size/2] else null
            else -> perSampleAgg.minOrNull()
        }

        fs.putDerived(def.id, v, tsMs)
    }

    /*fun computeSlopeMulti(def: FeatureDef.SlopeMulti, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val ring = arr[def.source] ?: return
        val n = def.windowSec * tickHz
        val window = ring.tail(n)
        if (window.isEmpty()) return
        val last = window.last()
        val minIdx = last.indices.minByOrNull { last[it] } ?: 0
        val series = window.mapNotNull { it.getOrNull(minIdx) }.toDoubleArray()
        val slopePerSample = MathOps.lsqSlope(series) ?: return
        val slope = if (def.unitsPerSec == "per_minute") slopePerSample * tickHz * 60.0 else slopePerSample * tickHz
        fs.putDerived(def.id, slope, tsMs)
    }*/

    fun computeSlopeMulti(def: FeatureDef.SlopeMulti, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val ring = arr[def.source] ?: return
        val n = def.windowSec * tickHz
        val window = ring.tail(n)
        if (window.isEmpty()) return

        // Choose which channel to follow for slope based on multiPolicy
        val last = window.last()
        val channelIdx = when (def.multiPolicy) {
            "min_channel" -> last.indices.minByOrNull { last[it] } ?: 0
            "max_channel" -> last.indices.maxByOrNull { last[it] } ?: 0
            else          -> 0
        }

        val series = window.mapNotNull { it.getOrNull(channelIdx) }.toDoubleArray()
        val slopePerSample = MathOps.lsqSlope(series) ?: return
        val slopePerSec = if (def.unitsPerSec == "per_minute") slopePerSample * tickHz * 60.0
        else slopePerSample * tickHz

        fs.putDerived(def.id, slopePerSec, tsMs)
    }

    fun computeDelta(def: FeatureDef.Delta, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val ring = num[def.source] ?: return
        val now = ring.lastOrNull() ?: return
        val back = ring.getBack(def.horizonSec * tickHz) ?: return
        fs.putDerived(def.id, now - back, tsMs)
    }
}


