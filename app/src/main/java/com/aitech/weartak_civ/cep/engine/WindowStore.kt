package com.aitech.weartak_civ.cep.engine

import android.util.Log
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.CepPayload
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.*
import com.aitech.weartak_civ.cep.util.TimedArrayRing
import com.aitech.weartak_civ.cep.util.TimedDoubleRing
import com.aitech.weartak_civ.cep.util.MathOps
import com.aitech.weartak_civ.cep.util.Stats

/**
 * Stores ingested sensor samples in per-channel ring buffers and exposes
 * feature-compute helpers consumed by [CepEngine].
 *
 * Phase 2: ring buffers changed from count-indexed ([DoubleRing]/[ArrayRing])
 * to timestamp-indexed ([TimedDoubleRing]/[TimedArrayRing]).  All window,
 * slope and delta computations now work in wall-clock time, making them
 * independent of CEP tick rate and sensor ingest frequency.
 */
class WindowStore(private val ringConfig: RingConfig) {

    // Phase 2: timestamp-aware ring maps
    private val num = mutableMapOf<String, TimedDoubleRing>()
    private val arr = mutableMapOf<String, TimedArrayRing>()
    private val latest = mutableMapOf<String, Any?>()

    private val accelMag = "accel.magnitude_g"
    private val ambientWind = "ambient.wind_mps"
    private val heart_rate = "heart_rate"
    private val skin_temp = "skin_temp"
    private val ambient_temp_c = "ambient.temp_c"
    private val ambient_humidity_pct = "ambient.humidity_pct"
    private val accel_cadence_spm = "accel.cadence_spm"
    private val activity_state = "activity.state"
    private val hrv_rmssd_ms = "hrv.rmssd_ms"
    private val immersion_wet_flag = "immersion.wet_flag"
    private val immersion_immersion_flag = "immersion.immersion_flag"
    private val sleep_is_sleeping = "sleep.is_sleeping"
    private val offbody_on_wrist = "offbody.on_wrist"
    private val gyro_rms_dps = "gyro.rms_dps"
    private val ppg_amplitude = "ppg.amplitude"
    private val spo2 = "spo2"
    private val humidity_relative_percent = "humidity.relative_percent"
    private val water_sensor_wet = "water_sensor.wet"
    private val light_lux = "light.lux"
    private val max = "max"
    private val min = "min"
    private val avg = "avg"
    private val rms = "rms"
    private val median = "median"

    // Ring capacity: sized by maxSensorHz (the fastest sensor rate in the system),
    // NOT by the engine tick rate.  This ensures that even a 4 Hz sensor retains a
    // full maxSecondsNumeric window of history.
    // e.g. default: 3600 s * 4 Hz = 14 400 slots (~56 kB for DoubleArray).
    private fun numCap() = ringConfig.maxSecondsNumeric * ringConfig.maxSensorHz
    private fun arrCap() = ringConfig.maxSecondsArray  * ringConfig.maxSensorHz

    /** Test / diagnostics: newest sample in numeric ring [topic], if any. */
    internal fun peekNumericRingLast(topic: String): Double? = num[topic]?.lastOrNull()

    /**
     * Holder-mediated swap: copy per-topic ring buffers and [latest] from another store.
     * [FeatureStore] is intentionally not copied — profile and derived keys are re-seeded in
     * [CepEngine.warmUp]. Requires identical [RingConfig] so ring capacities match.
     */
    fun adoptIngestionStateFrom(other: WindowStore) {
        require(ringConfig == other.ringConfig) {
            "RingConfig mismatch; retained rings are undefined across this swap."
        }
        num.clear()
        arr.clear()
        latest.clear()
        for ((topic, srcRing) in other.num) {
            val dst = TimedDoubleRing(numCap())
            dst.copyStateFrom(srcRing)
            num[topic] = dst
        }
        for ((topic, srcRing) in other.arr) {
            val dst = TimedArrayRing(arrCap())
            dst.copyStateFrom(srcRing)
            arr[topic] = dst
        }
        latest.putAll(other.latest)
    }

    // ── Ingestion ─────────────────────────────────────────────────────────────

    fun ingest(evt: CepEvent<out CepPayload>, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        when (val p = evt.payload) {
            is HeartRatePayload -> {
                // Phase 2: pass tsMs so tailByTime works correctly
                num.getOrPut(Topics.HEART_RATE) { TimedDoubleRing(numCap()) }.add(p.bpm, tsMs)
                latest[heart_rate] = p.bpm
                fs.put(heart_rate, p.bpm, tsMs)
            }
            is SkinTempPayload -> {
                arr.getOrPut(Topics.SKIN_TEMP) { TimedArrayRing(arrCap()) }.add(p.c_peripheral, tsMs)
                latest[skin_temp] = p.c_peripheral
                fs.put(skin_temp, p.c_peripheral, tsMs)
            }
            is AmbientPayload -> {
                latest[ambient_temp_c] = p.temp_c
                latest[ambientWind] = p.wind_mps
                latest[ambient_humidity_pct] = p.humidity_pct
                fs.putAll(mapOf(
                    ambient_temp_c to p.temp_c,
                    ambientWind to p.wind_mps,
                    ambient_humidity_pct to p.humidity_pct
                ))
            }
            is AccelPayload -> {
                num.getOrPut(accelMag) { TimedDoubleRing(numCap()) }.add(p.magnitude_g, tsMs)
                latest[accelMag] = p.magnitude_g
                fs.put(accelMag, p.magnitude_g, tsMs)
                p.cadence_spm?.let {
                    num.getOrPut(accel_cadence_spm) { TimedDoubleRing(numCap()) }.add(it, tsMs)
                    latest[accel_cadence_spm] = it
                }
            }
            is ActivityPayload -> {
                //Log.i("CEP", "latest[activity_state] = ${p.state}")
                latest[activity_state] = p.state
                fs.put(activity_state, p.state, tsMs)
            }
            is Spo2Payload -> {
                // Phase 2: add to ring so computeWindow can build time-based windows
                // (features.spo2.recent_120s, features.spo2.baseline_86400s).
                // Previously only fs.put() was called — num["spo2"] was always null
                // so both SPO2 window features returned early and were permanently null.
                num.getOrPut(spo2) { TimedDoubleRing(numCap()) }.add(p.pct, tsMs)
                latest[spo2] = p.pct
                fs.put(spo2, p.pct, tsMs)
            }
            is HrvPayload -> {
                val h = p.rmssd_ms
                if (h != null) {
                    num.getOrPut(hrv_rmssd_ms) { TimedDoubleRing(numCap()) }.add(h, tsMs)
                    fs.put(hrv_rmssd_ms, h, tsMs)
                }
            }
            is ImmersionPayload -> {
                latest[immersion_wet_flag] = p.wet_flag
                latest[immersion_immersion_flag] = p.immersion_flag
                fs.putAll(mapOf(immersion_wet_flag to p.wet_flag, immersion_immersion_flag to p.immersion_flag))
            }
            is SleepPayload -> {
                latest[sleep_is_sleeping] = p.is_sleeping
                fs.put(sleep_is_sleeping, p.is_sleeping, tsMs)
            }
            is OffBodyPayload -> {
                //Log.i("CEP", "latest[offbody_on_wrist] = ${p.on_wrist}")
                latest[offbody_on_wrist] = p.on_wrist; fs.put(
                    offbody_on_wrist,
                    p.on_wrist,
                    tsMs
                )
            }

            // ---- WindPayload (Topics.WIND) ----
            is WindPayload -> {
                p.speedMps?.let {
                    latest[ambientWind] = it
                    fs.put(ambientWind, it, tsMs)
                }
                p.directionDeg?.let { fs.put("wind.direction_deg", it, tsMs) }
            }

            // ---- GyroPayload (Topics.GYRO) ----
            is GyroPayload -> {
                fs.put("gyro.x_dps", p.x.toDouble(), tsMs)
                fs.put("gyro.y_dps", p.y.toDouble(), tsMs)
                fs.put("gyro.z_dps", p.z.toDouble(), tsMs)
                p.rms?.let {
                    num.getOrPut(gyro_rms_dps) { TimedDoubleRing(numCap()) }.add(it.toDouble(), tsMs)
                    fs.put(gyro_rms_dps, it, tsMs)
                }
            }

            // ---- PpgPayload (Topics.PPG) ----
            is PpgPayload -> {
                num.getOrPut(ppg_amplitude) { TimedDoubleRing(numCap()) }.add(p.amplitude.toDouble(), tsMs)
                fs.put(ppg_amplitude, p.amplitude, tsMs)
                p.quality?.let { fs.put("ppg.quality", it, tsMs) }
                p.wavelength?.let { fs.put("ppg.wavelength", it, tsMs) }
            }

            // ---- HumidityPayload (Topics.HUMIDITY) ----
            is HumidityPayload -> {
                fs.put(humidity_relative_percent, p.relativePercent, tsMs)
                latest[humidity_relative_percent] = p.relativePercent
            }

            // ---- WaterPayload (Topics.WATER) ----
            is WaterPayload -> {
                fs.put(water_sensor_wet, p.wet, tsMs)
                latest[water_sensor_wet] = p.wet
                p.temperatureC?.let {
                    fs.put("ambient.water_temp_c", it, tsMs)
                }
            }

            // ---- LightPayload (Topics.LIGHT) ----
            is LightPayload -> {
                fs.put(light_lux, p.lux, tsMs)
                latest[light_lux] = p.lux
                p.covered?.let {
                    fs.put("als.covered", it, tsMs)
                }
            }

            // ---- PressurePayload (Topics.PRESSURE) ----
            is PressurePayload -> {
                val ring = num.getOrPut(Topics.PRESSURE) { TimedDoubleRing(numCap()) }
                ring.add(p.hpa.toDouble(), tsMs)
                fs.put(Topics.PRESSURE, p.hpa.toDouble(), tsMs)
                fs.put("pressure.accuracy", p.accuracy.toDouble(), tsMs)
                //Log.d("CEP_P6", "[INGEST] hpa=${p.hpa} ts=$tsMs ringSize=${ring.size}")
            }
        }
    }

    // ── Feature Compute ───────────────────────────────────────────────────────

    /**
     * Phase 2: time-based window — [ring.tailByTime] returns all samples
     * whose timestamp falls within the last [def.windowSec] real seconds,
     * regardless of how many ticks have elapsed.
     *
     * Phase 1: if [def.maxAgeSec] is set the feature is suppressed when the
     * most-recent sample is staler than that guard.
     */
    fun computeWindow(def: FeatureDef.Window, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val ring = num[def.source] ?: return

        // Phase 1: freshness guard
        if (def.maxAgeSec != null) {
            val lastTs = ring.lastTsOrNull() ?: return
            if ((tsMs - lastTs) / 1000L > def.maxAgeSec) return
        }

        // Phase 2: time-based window
        val window = ring.tailByTime(def.windowSec * 1000L, tsMs)
        val v = when (def.op) {
            min    -> window.minOrNull()
            max    -> window.maxOrNull()
            avg    -> if (window.isNotEmpty()) window.average() else null
            rms    -> MathOps.rms(window)
            median -> if (window.isNotEmpty()) window.sorted()[window.size / 2] else null
            else   -> null
        }
        // Phase 2: null guard — empty window produces null; don't forward null into
        // ConcurrentHashMap (putDerived) or it throws NPE.  Sparse sensors make empty
        // windows the normal steady-state, not just a test-teardown edge case.
        v?.let { fs.putDerived(def.id, it, tsMs) }
    }

    /**
     * Phase 2: time-based multi-channel window — mirrors [computeWindow] but
     * for array-valued rings (e.g. skin-temp with multiple peripheral sensors).
     */
    fun computeMultiWindow(def: FeatureDef.MultiWindow, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val ring = arr[def.source] ?: return

        // Phase 1: freshness guard
        if (def.maxAgeSec != null) {
            val lastTs = ring.lastTsOrNull() ?: return
            if ((tsMs - lastTs) / 1000L > def.maxAgeSec) return
        }

        // Phase 2: time-based window
        val window = ring.tailByTime(def.windowSec * 1000L, tsMs)
        if (window.isEmpty()) return

        // Combine channels per sample according to multiPolicy
        val perSampleAgg: List<Double> = when (def.multiPolicy) {
            min  -> window.map { it.minOrNull() ?: Double.POSITIVE_INFINITY }
            max  -> window.map { it.maxOrNull() ?: Double.NEGATIVE_INFINITY }
            else -> window.map { it.minOrNull() ?: Double.POSITIVE_INFINITY }
        }

        val v = when (def.op) {
            min    -> perSampleAgg.minOrNull()
            max    -> perSampleAgg.maxOrNull()
            avg    -> if (perSampleAgg.isNotEmpty()) perSampleAgg.average() else null
            rms    -> MathOps.rms(perSampleAgg)
            median -> if (perSampleAgg.isNotEmpty()) perSampleAgg.sorted()[perSampleAgg.size / 2] else null
            else   -> perSampleAgg.minOrNull()
        }
        // Phase 2: null guard — same as computeWindow; perSampleAgg can yield null
        // for edge cases (e.g. all infinity from empty channels).
        v?.let { fs.putDerived(def.id, it, tsMs) }
    }

    /**
     * Phase 2: true time-based slope via [MathOps.lsqSlopeXY].
     *
     * Timestamps from [TimedArrayRing.tailByTimePairs] are converted to
     * seconds-from-first-sample as the x-axis, so the returned slope is
     * already in °C/s (or the appropriate y-unit per second).  No
     * `* tickHz` scaling needed — the actual elapsed time is used directly.
     *
     * Phase 1: optional freshness guard suppresses stale features.
     */
    fun computeSlopeMulti(def: FeatureDef.SlopeMulti, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val ring = arr[def.source] ?: return

        // Phase 1: freshness guard
        if (def.maxAgeSec != null) {
            val lastTs = ring.lastTsOrNull() ?: return
            if ((tsMs - lastTs) / 1000L > def.maxAgeSec) return
        }

        // Phase 2: time-based window + true LSQ slope
        val pairs = ring.tailByTimePairs(def.windowSec * 1000L, tsMs)
        if (pairs.size < 2) return

        // Choose channel to follow based on multiPolicy applied to the LAST sample
        val last = pairs.last().second
        val channelIdx = when (def.multiPolicy) {
            "min_channel" -> last.indices.minByOrNull { last[it] } ?: 0
            "max_channel" -> last.indices.maxByOrNull { last[it] } ?: 0
            else          -> 0
        }

        val t0 = pairs.first().first
        val xsList = ArrayList<Double>(pairs.size)
        val ysList = ArrayList<Double>(pairs.size)
        for ((ts, channels) in pairs) {
            val y = channels.getOrNull(channelIdx) ?: continue
            xsList.add((ts - t0) / 1000.0)   // seconds from first sample
            ysList.add(y)
        }
        if (xsList.size < 2) return

        // Slope is already per-second; multiply by 60 for per-minute features
        val slopePerSec = MathOps.lsqSlopeXY(xsList.toDoubleArray(), ysList.toDoubleArray()) ?: return
        val slope = if (def.unitsPerSec == "per_minute") slopePerSec * 60.0 else slopePerSec
        fs.putDerived(def.id, slope, tsMs)
    }

    /**
     * Phase 2: time-based delta — looks up the sample closest to
     * (tsMs - horizonSec * 1000) instead of indexing back [horizonSec * tickHz] slots.
     */
    fun computeDelta(def: FeatureDef.Delta, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val ring = num[def.source] ?: return
        val now  = ring.lastOrNull() ?: return
        // Phase 2: time-based horizon lookup
        val back = ring.getByTime(def.horizonSec * 1000L, tsMs) ?: return
        fs.putDerived(def.id, now - back, tsMs)
    }

    // ── Sample-count feature compute (signal-agnostic) ────────────────────────

    /**
     * Rolling aggregate over the last [def.sampleCount] samples by insertion order.
     *
     * Suppressed when:
     *  - the ring for [def.source] has not been populated yet
     *  - fewer than [def.minSamples] samples are available
     *  - [def.maxAgeSec] is set and the newest sample is older than that threshold
     *
     * Supported ops: avg, min, max, rms, median, stddev, variance
     */

    /** Test-only: drop the numeric ring for [topic] so the next ingest starts from an empty buffer. */
    fun clearRing(topic: String) { num.remove(topic) }

    fun computeSampleWindow(def: FeatureDef.SampleWindow, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        //val isPressure = def.source == Topics.PRESSURE
        val ring = num[def.source] ?: run {
            //if (isPressure) Log.d("CEP_P6", "[FEATURE] id=${def.id} suppressed=no_ring")
            return
        }

        // Freshness guard: newest sample must be within maxAgeSec of now
        if (def.maxAgeSec != null) {
            val lastTs = ring.lastTsOrNull() ?: run {
                //if (isPressure) Log.d("CEP_P6", "[FEATURE] id=${def.id} suppressed=empty_ring")
                return
            }
            if ((tsMs - lastTs) / 1000L > def.maxAgeSec) {
                //if (isPressure) Log.d("CEP_P6", "[FEATURE] id=${def.id} suppressed=stale ageSec=${(tsMs - lastTs) / 1000L} maxAge=${def.maxAgeSec}")
                return
            }
        }

        val window = ring.tailByCount(def.sampleCount)
        if (window.size < def.minSamples) {
            //if (isPressure) Log.d("CEP_P6", "[FEATURE] id=${def.id} suppressed=insufficient have=${window.size} need=${def.minSamples} ringSize=${ring.size}")
            return
        }

        val v: Double? = when (def.op) {
            avg        -> window.average()
            min        -> window.minOrNull()
            max        -> window.maxOrNull()
            rms        -> MathOps.rms(window)
            median     -> window.sorted().let { s -> s[s.size / 2] }
            "stddev"   -> Stats.std(window)
            "variance" -> Stats.std(window)?.let { s -> s * s }
            else       -> null
        }
        v?.let {
            //if (isPressure) Log.d("CEP_P6", "[FEATURE] id=${def.id} ringSize=${ring.size} value=$it")
            fs.putDerived(def.id, it, tsMs)
        }
    }

    /**
     * Sample-count delta: (newest sample) − (sample [def.horizonSamples] positions back).
     *
     * Position 0 is the most-recently inserted sample; position [horizonSamples] is
     * [horizonSamples] samples older.  Writes nothing if insufficient history.
     */
    fun computeSampleDelta(def: FeatureDef.SampleDelta, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        //val isPressure = def.source == Topics.PRESSURE
        val ring = num[def.source] ?: run {
            //if (isPressure) Log.d("CEP_P6", "[FEATURE] id=${def.id} suppressed=no_ring")
            return
        }

        // Freshness guard on the newest sample
        if (def.maxAgeSec != null) {
            val lastTs = ring.lastTsOrNull() ?: run {
                //if (isPressure) Log.d("CEP_P6", "[FEATURE] id=${def.id} suppressed=empty_ring")
                return
            }
            if ((tsMs - lastTs) / 1000L > def.maxAgeSec) {
                //if (isPressure) Log.d("CEP_P6", "[FEATURE] id=${def.id} suppressed=stale ageSec=${(tsMs - lastTs) / 1000L} maxAge=${def.maxAgeSec}")
                return
            }
        }

        val now  = ring.getByCount(0) ?: run {
            //if (isPressure) Log.d("CEP_P6", "[FEATURE] id=${def.id} suppressed=missing_newest ringSize=${ring.size}")
            return
        }
        val back = ring.getByCount(def.horizonSamples) ?: run {
            //if (isPressure) Log.d("CEP_P6", "[FEATURE] id=${def.id} suppressed=missing_back ringSize=${ring.size} need=${def.horizonSamples + 1}")
            return
        }
        val delta = now - back
        //if (isPressure) Log.d("CEP_P6", "[FEATURE] id=${def.id} ringSize=${ring.size} now=$now back=$back delta=$delta")
        fs.putDerived(def.id, delta, tsMs)
    }

    /**
     * Sample-count slope via least-squares regression over [def.sampleCount] recent samples.
     *
     * Real sensor timestamps serve as the x-axis (converted to seconds) so the slope is
     * in signal-units/second (or /minute when [def.unitsPerSec] = "per_minute").
     *
     * Suppressed when:
     *  - fewer than 2 samples exist
     *  - timestamp span of the window is < MIN_SLOPE_SPAN_MS (guards against ÷0 near-singularity
     *    when multiple samples share the same millisecond timestamp)
     *  - [def.maxAgeSec] freshness check fails
     */
    fun computeSampleSlope(def: FeatureDef.SampleSlope, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val ring = num[def.source] ?: return

        // Freshness guard
        if (def.maxAgeSec != null) {
            val lastTs = ring.lastTsOrNull() ?: return
            if ((tsMs - lastTs) / 1000L > def.maxAgeSec) return
        }

        val pairs = ring.tailByCountPairs(def.sampleCount)
        if (pairs.size < 2) return

        // Guard against near-zero time span (e.g. high-frequency replay with synthetic clocks)
        val span = pairs.last().first - pairs.first().first
        if (span < MIN_SLOPE_SPAN_MS) return

        val t0 = pairs.first().first
        val xs = DoubleArray(pairs.size) { i -> (pairs[i].first - t0) / 1000.0 }
        val ys = DoubleArray(pairs.size) { i -> pairs[i].second }

        val slopePerSec = MathOps.lsqSlopeXY(xs, ys) ?: return
        val slope = if (def.unitsPerSec == "per_minute") slopePerSec * 60.0 else slopePerSec
        fs.putDerived(def.id, slope, tsMs)
    }

    companion object {
        /** Minimum timestamp span (ms) required to compute a valid sample-count slope. */
        private const val MIN_SLOPE_SPAN_MS = 10L
    }
}
