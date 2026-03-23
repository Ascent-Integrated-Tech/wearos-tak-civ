package com.aitech.weartak_civ.cep.engine

import android.content.Context
import com.aitech.weartak_civ.cep.bus.CepBus
import kotlinx.coroutines.*
import android.util.Log
import com.aitech.weartak_civ.CAT_MANUAL_ALERT
import com.aitech.weartak_civ.cep.engine.action.AlertRegistry
import com.aitech.weartak_civ.events.AlertClearedEvent
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.NumericSample
import com.aitech.weartak_civ.data.DataRepository
import firebase.com.protolitewrapper.BuildConfig
import kotlinx.coroutines.flow.first
import org.greenrobot.eventbus.EventBus
import java.util.Calendar

class CepEngine(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val config: CepConfig,
    private val yearOfBirth: Int,
    private val dataRepository: DataRepository
) {
    private val featureStore = FeatureStore()
    private val windows = WindowStore(config.tickHz, RingConfig.default())

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
        scope.launch { updateSustainOverrides() }

        /*scope.launch {
            try {
                val high = dataRepository.getHrHighAlertBpmThreshold
                val low  = dataRepository.getRestingHeartRate

                featureStore.put("thresholds.hr.high_bpm", high)
                featureStore.put("thresholds.hr.low_bpm", low)

                Log.d("CEP", "Loaded HR thresholds: high=$high low=$low")
            } catch (t: Throwable) {
                Log.e("CEP", "Failed to load HR thresholds from DataRepository", t)

                // Safe defaults (optional)
                featureStore.put("thresholds.hr.high_bpm", 163.0)
                featureStore.put("thresholds.hr.low_bpm", 40.0)
            }
        }*/

        // Subscribe to the bus (no emitters yet; safe)
        scope.launch { CepBus.events.collect { evt -> windows.ingest(evt, featureStore) } }

        // Tick loop
        scope.launch {
            val tickMs = (1000L / config.tickHz).coerceAtLeast(1L)
            while (isActive) {
                featureStore.beginTick()         // NEW: start tracking this tick

                computeFeaturesSafely()

                featureStore.logDerivedTick("CEP") // NEW: print everything computed this tick

                ruleEngine.processTick()
                checkAndClearAlerts()   // NEW
                delay(tickMs)
            }
        }
    }

    fun updateHrProfile() {
        //currentYearOfBirth = newYearOfBirth

        scope.launch {
            try {
                val newYearOfBirth = dataRepository.getYearOfBirth.first()

                val currentYear =
                    java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                val age = (currentYear - newYearOfBirth).coerceIn(0, 200)

                val hrMax = ZoneHelper.tanakaHrMax(age)

                // Load values from DataRepository
                val hrRest = dataRepository.getRestingHeartRate.first() ?: 60.0
                val hrHigh = dataRepository.getHrHighAlertBpmThreshold.first() ?: 140
                val hrRestingHigh = dataRepository.getRestingHrHighAlertBpmThreshold.first() ?: 120
                val hrLow  = dataRepository.getRestingHrLowAlertBpmThreshold.first() ?: 40

                // Core HR profile
                featureStore.put("profile.hr_rest_bpm", hrRest)
                featureStore.put("profile.hr_max_bpm", hrMax)

                // HR thresholds
                featureStore.put("thresholds.hr.high_bpm", hrRestingHigh)
                featureStore.put("thresholds.hr.low_bpm",  hrLow)

                Log.d(
                    "CEP",
                    "Updated HR profile: YOB=$newYearOfBirth age=$age " +
                            "hrRest=$hrRest hrMax=$hrMax hrHighResting=$hrRestingHigh hrLow=$hrLow"
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
                val exertWarn = dataRepository.getExertionThresholdWarning.first() ?: 80
                val exertAlert = dataRepository.getExertionThresholdAlert.first() ?: 90

                // Store as thresholds (keep naming consistent with existing thresholds.hr.* style)
                featureStore.put("thresholds.hrr.exert_warn_percent", exertWarn.toDouble())
                featureStore.put("thresholds.hrr.exert_alert_percent", exertAlert.toDouble())

                Log.d("CEP", "Loaded exertion thresholds: warn=$exertWarn% alert=$exertAlert%")

            } catch (t: Throwable) {
                Log.e("CEP", "Failed to update HR profile from DataRepository", t)
            }
        }
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

    private fun computeFeaturesSafely() {
        for (def in config.features) {
            try {
                when (def) {
                    is FeatureDef.Window      -> windows.computeWindow(def, featureStore)
                    is FeatureDef.MultiWindow -> windows.computeMultiWindow(def, featureStore)
                    is FeatureDef.SlopeMulti  -> windows.computeSlopeMulti(def, featureStore)
                    is FeatureDef.Delta       -> windows.computeDelta(def, featureStore)
                    is FeatureDef.Derived     -> DerivedEval.eval(def, featureStore)
                }
            } catch (ex: Throwable) {
                Log.e("CEP", "Feature compute failed for ${def.id}", ex)
            }
        }
    }

    //Fix for cep feature key alert cancellation
    private suspend fun checkAndClearAlerts() {
        val now = System.currentTimeMillis()
        Log.i("CEP", "checkAndClearAlerts: checking for expired alerts")

        for (alert in AlertRegistry.allActive()) {
            Log.i("CEP", "exitlogic: ${alert.exitLogic}; conditions: ${alert.exitConditions}")
            val conditions = alert.exitConditions
            if (conditions.isEmpty()) continue

            val satisfiedFlags = conditions.map { cond ->
                Log.i("CEP", "${featureStore.latestAsDouble(cond.feature)}")
                val value = featureStore.latestAsDouble(cond.feature) ?: return@map false

                // Resolve thresholds: prefer literal, else feature-key
                val below = cond.exitBelow ?: cond.exitBelowKey?.let { key -> featureStore.latestAsDouble(key) }
                val above = cond.exitAbove ?: cond.exitAboveKey?.let { key -> featureStore.latestAsDouble(key) }

                val belowOk = below?.let { value < it - cond.hysteresis } ?: false
                val aboveOk = above?.let { value > it + cond.hysteresis } ?: false
                Log.i("CEP", "$below, $belowOk; above $above, $aboveOk")

                belowOk || aboveOk
            }

            val allOrAnySatisfied = when (alert.exitLogic) {
                "OR" -> satisfiedFlags.any { it }
                else -> satisfiedFlags.all { it }  // default AND
            }
            Log.i("CEP", "checkAndClearAlerts: allOrAnySatisfied=$allOrAnySatisfied")

            if (allOrAnySatisfied) {
                val since = alert.exitSatisfiedSinceMs
                if (since == null) {
                    alert.exitSatisfiedSinceMs = now
                } else {
                    Log.i("CEP", "$now, $since, ${now - since}/1000L")
                    val elapsedSec = (now - since) / 1000L
                    if (elapsedSec >= alert.exitSustainSec) {
                        if (alert.priority == 1) {
                            dataRepository.clearPhysioAndAutoGenAlertRequestUid(alert.uid)
                            AlertRegistry.clear(alert.ruleId)
                            EventBus.getDefault().post(AlertClearedEvent(alert.uid))
                        } else {
                            dataRepository.removePhysioAndAutoGenAlertByUid(alert.uid)
                            AlertRegistry.clear(alert.ruleId)
                            EventBus.getDefault().post(AlertClearedEvent(alert.uid))
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


