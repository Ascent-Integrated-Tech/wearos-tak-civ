package com.aitech.weartak_civ.cep.engine

import android.content.Context
import com.aitech.weartak_civ.cep.bus.CepBus
import kotlinx.coroutines.*
import android.util.Log
import com.aitech.weartak_civ.DEFAULT_EXERTION_ALERT_THRESHOLD_PRECENTAGE
import com.aitech.weartak_civ.DEFAULT_EXERTION_WARNING_THRESHOLD_PERCENTAGE
import com.aitech.weartak_civ.DEFAULT_HIGH_RESTING_HR_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_LOW_RESTING_HR_THRESHOLD
import com.aitech.weartak_civ.DEFAULT_RESTING_HR
import com.aitech.weartak_civ.cep.engine.action.AlertRegistry
import com.aitech.weartak_civ.events.AlertClearedEvent
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.NumericSample
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.flow.first

class CepEngine(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val config: CepConfig,
    private val yearOfBirth: Int,
    private val dataRepository: DataRepository
) {
    private val featureStore = FeatureStore()
    private val windows = WindowStore(RingConfig.default())

    @Volatile
    private var sustainOverridesMain: Map<String, Long> = emptyMap()

    @Volatile
    private var sustainOverridesWarn: Map<String, Long> = emptyMap()

    private val ruleEngine = RuleEngine(
        scope,
        config,
        featureStore,
        sustainOverrideMainProvider = { sustainOverridesMain },
        sustainOverrideWarnProvider = { sustainOverridesWarn }
    )

    @Volatile
    private var currentYearOfBirth: Int = yearOfBirth

    fun start() {
        Log.d("CEP", "CEP Engine started")

        //Call this fn whenever the yob is changed
        updateHrProfile()
        updateHrrProfile()
        updatePressureProfile()
        scope.launch { updateSustainOverrides() }

        // Subscribe to the bus (no emitters yet; safe)
        // Phase 1: pass evt.ts so WindowStore timestamps reflect sensor measurement time, not processing time
        scope.launch { CepBus.events.collect { evt -> windows.ingest(evt, featureStore, evt.ts) } }

        // Phase 3: per-feature compute coroutines.
        // Each feature runs in its own coroutine at its own updateIntervalMs cadence.
        // An exception in one feature is caught and logged — it cannot crash or stall
        // any other feature or the rule-eval loop.  The rule-eval loop always reads
        // the last cached value from FeatureStore, regardless of when the feature last computed.
        for (def in config.features) {
            scope.launch {
                while (isActive) {
                    try {
                        when (def) {
                            is FeatureDef.Window       -> windows.computeWindow(def, featureStore)
                            is FeatureDef.MultiWindow  -> windows.computeMultiWindow(def, featureStore)
                            is FeatureDef.SlopeMulti   -> windows.computeSlopeMulti(def, featureStore)
                            is FeatureDef.Delta        -> windows.computeDelta(def, featureStore)
                            is FeatureDef.Derived      -> DerivedEval.eval(def, featureStore)
                            is FeatureDef.SampleWindow -> windows.computeSampleWindow(def, featureStore)
                            is FeatureDef.SampleDelta  -> windows.computeSampleDelta(def, featureStore)
                            is FeatureDef.SampleSlope  -> windows.computeSampleSlope(def, featureStore)
                        }
                    } catch (ex: Throwable) {
                        Log.e("CEP", "Feature compute failed for ${def.id} — isolated, engine continues", ex)
                    }
                    delay(def.updateIntervalMs)
                }
            }
        }

        // Rule evaluation loop.
        // Runs at ruleEvalIntervalMs (from config); reads cached FeatureStore values written
        // by the per-feature coroutines above.  No feature compute happens here.
        scope.launch {
            val tickMs = config.ruleEvalIntervalMs.toLong().coerceAtLeast(1L)
            while (isActive) {
                featureStore.beginTick()
                featureStore.logDerivedTick("CEP")
                ruleEngine.processTick()
                checkAndClearAlerts()
                delay(tickMs)
            }
        }
    }

    fun updateHrProfile() {
        //currentYearOfBirth = newYearOfBirth

        scope.launch {
            try {
                val newYearOfBirth = dataRepository.getYearOfBirth.first()
                val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                val age = (currentYear - newYearOfBirth).coerceIn(0, 200)

                val hrMax = ZoneHelper.tanakaHrMax(age)

                // Load values from DataRepository
                val hrRest = dataRepository.getRestingHeartRate.first() ?: DEFAULT_RESTING_HR
                val hrRestingHigh = dataRepository.getRestingHrHighAlertBpmThreshold.first() ?: DEFAULT_HIGH_RESTING_HR_THRESHOLD
                val hrRestingLow  = dataRepository.getRestingHrLowAlertBpmThreshold.first() ?: DEFAULT_LOW_RESTING_HR_THRESHOLD

                // Core HR profile
                featureStore.put("profile.hr_rest_bpm", hrRest)
                featureStore.put("profile.hr_max_bpm", hrMax)

                // HR thresholds
                featureStore.put("thresholds.hr.rest_high_bpm", hrRestingHigh)
                featureStore.put("thresholds.hr.rest_low_bpm",  hrRestingLow)

                Log.d("CEP", "Updated HR profile: YOB=$newYearOfBirth age=$age " +
                            "hrRest=$hrRest hrMax=$hrMax hrHighResting=$hrRestingHigh hrLowResting=$hrRestingLow"
                )
            } catch (t: Throwable) {
                Log.e("CEP", "Failed to update HR profile from DataRepository", t)
            }
        }
    }

    fun updateHrrProfile() {
        scope.launch {
            try {

                // Load exertion thresholds (percent values from settings)
                val exertWarn = dataRepository.getExertionThresholdWarning.first() ?: DEFAULT_EXERTION_WARNING_THRESHOLD_PERCENTAGE
                val exertAlert = dataRepository.getExertionThresholdAlert.first() ?: DEFAULT_EXERTION_ALERT_THRESHOLD_PRECENTAGE

                // Store as thresholds (keep naming consistent with existing thresholds.hr.* style)
                featureStore.put("thresholds.hrr.exert_warn_percent", exertWarn.toDouble())
                featureStore.put("thresholds.hrr.exert_alert_percent", exertAlert.toDouble())

                Log.d("CEP", "Loaded exertion thresholds: warn=$exertWarn% alert=$exertAlert%")

            } catch (t: Throwable) {
                Log.e("CEP", "Failed to update HRR profile from DataRepository", t)
            }
        }
    }

    /**
     * Load atmospheric-pressure alert thresholds from DataRepository into FeatureStore
     * so derived features (features.pressure.above_high_threshold, below_low_threshold)
     * can compare against them without hard-coding numeric values in the rules JSON.
     *
     * Call from start() and whenever the user changes pressure alert thresholds in settings.
     */
    fun updatePressureProfile() {
        scope.launch {
            try {
                val highHpa = dataRepository.getHighAtmPressureAlertThreshold.first()
                val lowHpa  = dataRepository.getLowAtmPressureAlertThreshold.first()
                featureStore.put("thresholds.pressure.high", highHpa.toDouble())
                featureStore.put("thresholds.pressure.low",  lowHpa.toDouble())

                // Write user enable flags into FeatureStore so CEP rules can gate on them.
                // Rules use gte_key on settings.pressure.* (1.0 = enabled, 0.0 = disabled).
                val immersionOn = dataRepository.getEnableImmersionAlerts.first()
                val lowAtmOn    = dataRepository.getEnableLowAtmPressureAlert.first()
                val highAtmOn   = dataRepository.getEnableHighAtmPressureAlert.first()
                featureStore.put("settings.pressure.immersion_alerts_enabled", if (immersionOn) 1.0 else 0.0)
                featureStore.put("settings.pressure.low_atm_alert_enabled",    if (lowAtmOn)    1.0 else 0.0)
                featureStore.put("settings.pressure.high_atm_alert_enabled",   if (highAtmOn)   1.0 else 0.0)

                Log.d("CEP", "Updated pressure profile: high=$highHpa low=$lowHpa hPa " +
                            "immersionOn=$immersionOn lowAtmOn=$lowAtmOn highAtmOn=$highAtmOn")
            } catch (t: Throwable) {
                Log.e("CEP", "Failed to update pressure profile from DataRepository", t)
            }
        }
    }

    /**
     * Test-only: write known threshold values directly into FeatureStore, bypassing the async
     * DataRepository load.  Call before P6-T3 / P6-T4 / P6-T5 to make threshold-dependent
     * derived features deterministic regardless of device DataStore state.
     */
    fun injectPressureThresholdsForTest(lowHpa: Double, highHpa: Double) {
        featureStore.put("thresholds.pressure.high", highHpa)
        featureStore.put("thresholds.pressure.low",  lowHpa)
        Log.d("CEP", "[TEST] Injected pressure thresholds: low=$lowHpa high=$highHpa hPa")
    }

    /**
     * Test-only: write the three pressure alert enable flags synchronously into FeatureStore,
     * bypassing the async DataRepository load.  All P6 scenarios call this with all flags = true
     * so the enable-flag gate in each pressure rule does not suppress test assertions.
     */
    fun injectPressureAlertFlagsForTest(immersion: Boolean = true, lowAtm: Boolean = true, highAtm: Boolean = true) {
        featureStore.put("settings.pressure.immersion_alerts_enabled", if (immersion) 1.0 else 0.0)
        featureStore.put("settings.pressure.low_atm_alert_enabled",    if (lowAtm)    1.0 else 0.0)
        featureStore.put("settings.pressure.high_atm_alert_enabled",   if (highAtm)   1.0 else 0.0)
        Log.d("CEP", "[TEST] Injected pressure alert flags: immersion=$immersion lowAtm=$lowAtm highAtm=$highAtm")
    }

    /**
     * Test-only: drop the pressure numeric ring and clear all pressure-derived keys from
     * FeatureStore so each Phase 6 scenario starts from a completely clean state.
     */

    /** Test-only: snapshot all pressure-related FeatureStore keys for FAIL diagnostics. */
    fun pressureFeatureSnapshot(): Map<String, Any?> = featureStore.snapshot(
        listOf(
            "pressure", "pressure.accuracy",
            "thresholds.pressure.low", "thresholds.pressure.high",
            "features.pressure.mean_n5", "features.pressure.delta_n5",
            "features.pressure.delta_rapid_rise", "features.pressure.delta_shallow_rise",
            "features.pressure.below_low_threshold", "features.pressure.above_high_threshold"
        )
    )

    fun clearPressureStateForTest() {
        windows.clearRing("pressure")
        listOf(
            "pressure", "pressure.accuracy",
            "features.pressure.mean_n5",  "features.pressure.mean_n20",
            "features.pressure.delta_n5", "features.pressure.slope_n20",
            "features.pressure.submerge_rise",
            "features.pressure.delta_rapid_rise", "features.pressure.delta_shallow_rise",
            "features.pressure.above_high_threshold", "features.pressure.below_low_threshold"
        ).forEach { featureStore.clear(it) }
        // Note: settings.pressure.* flags are NOT cleared — they are injected by
        // injectPressureAlertFlagsForTest() and must survive across the clear so the
        // enable-gate conditions stay satisfied for the next scenario.
        Log.d("CEP", "[TEST] Pressure ring and derived features cleared")
    }

    /**
     * Load sustain overrides from DataRepository and apply only for rules with allowSustainOverride.
     * Call after engine start and whenever UI/settings save overrides (mirror updateHrProfile pattern).
     */
    suspend fun updateSustainOverrides() {
        Log.d("CEP", "updateSustainOverrides: loading threshold length values from DataRepository")
        try {
            val loaded = dataRepository.getCepSustainOverridesOnce()
            val overrideableIds: Set<String> = config.rules.filter { it.allowSustainOverride }.map { it.id }.toSet()
            sustainOverridesMain = loaded.main
                .filterKeys { it in overrideableIds }
                .mapValues { (_: String, v: Long) -> v.coerceAtLeast(0L) }
            sustainOverridesWarn = loaded.warn
                .filterKeys { it in overrideableIds }
                .mapValues { (_: String, v: Long) -> v.coerceAtLeast(0L) }
            if (sustainOverridesMain.isNotEmpty() || sustainOverridesWarn.isNotEmpty()) {
                Log.d("CEP", "updateSustainOverrides: main=${sustainOverridesMain.entries.joinToString { "${it.key}=${it.value}s" }} warn=${sustainOverridesWarn.entries.joinToString { "${it.key}=${it.value}s" }}")
            } else if (overrideableIds.isNotEmpty()) {
                Log.d("CEP", "updateSustainOverrides: no overrides in storage; overrideable rule ids=$overrideableIds")
            }
        } catch (t: Throwable) {
            Log.e("CEP", "Failed to load CEP sustain overrides from DataRepository", t)
            sustainOverridesMain = emptyMap()
            sustainOverridesWarn = emptyMap()
        }
    }

    // Note: computeFeaturesSafely() was removed in Phase 3.
    // Feature compute is now done by per-feature coroutines launched in start().

    //Fix for cep feature key alert cancellation
    private suspend fun checkAndClearAlerts() {
        val now = System.currentTimeMillis()

        for (alert in AlertRegistry.allActive()) {
            val conditions = alert.exitConditions
            if (conditions.isEmpty()) continue

            val satisfiedFlags = conditions.map { cond ->
                val value = featureStore.latestAsDouble(cond.feature) ?: return@map false

                // Resolve thresholds: prefer literal, else feature-key
                val below = cond.exitBelow ?: cond.exitBelowKey?.let { key -> featureStore.latestAsDouble(key) }
                val above = cond.exitAbove ?: cond.exitAboveKey?.let { key -> featureStore.latestAsDouble(key) }

                val belowOk = below?.let { value < it - cond.hysteresis } ?: false
                val aboveOk = above?.let { value > it + cond.hysteresis } ?: false

                belowOk || aboveOk
            }

            val allOrAnySatisfied = when (alert.exitLogic) {
                "OR" -> satisfiedFlags.any { it }
                else -> satisfiedFlags.all { it }  // default AND
            }

            if (allOrAnySatisfied) {
                val since = alert.exitSatisfiedSinceMs
                if (since == null) {
                    alert.exitSatisfiedSinceMs = now
                } else {
                    val elapsedSec = (now - since) / 1000L
                    if (elapsedSec >= alert.exitSustainSec) {
                        if (alert.priority == 1) {
                            dataRepository.clearPhysioAndAutoGenAlertRequestUid(alert.uid)
                            AlertRegistry.clear(alert.ruleId)
                            dataRepository.addAlertClearedEventCep(alert.uid)
                        } else {
                            dataRepository.removePhysioAndAutoGenAlertByUid(alert.uid)
                            AlertRegistry.clear(alert.ruleId)
                            dataRepository.addAlertClearedEventCep(alert.uid)
                        }
                    }
                }
            } else {
                alert.exitSatisfiedSinceMs = null
            }
        }
    }

    fun ingest(path: String, value: Double, tsMs: Long = System.currentTimeMillis()) {
        try {
            val parts = path.split(".")
            if (parts.size < 2) return

            val ns  = parts[0]
            val key = parts.drop(1).joinToString(".")

            // write to store using the full dotted id
            featureStore.put("$ns.$key", value, tsMs)

            // publish to bus
            val evt = CepEvent(
                topic = "$ns.$key",
                payload = NumericSample(ns = ns, key = key, value = value),
                ts = tsMs
            )
            if (!CepBus.tryEmit(evt)) {
                scope.launch { CepBus.emit(evt) }
            }

            Log.i("CEP", "INGEST $ns.$key=$value @ $tsMs")
        } catch (ex: Exception) {
            Log.e("CEP", "Failed to ingest $path", ex)
        }
    }

}


