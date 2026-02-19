package com.aitech.weartak_civ.cep.engine

import android.content.Context
import com.aitech.weartak_civ.cep.bus.CepBus
import kotlinx.coroutines.*
import android.util.Log
import com.aitech.weartak_civ.CAT_MANUAL_ALERT
import com.aitech.weartak_civ.cep.engine.action.AlertRegistry
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
    private val ruleEngine = RuleEngine(scope, config, featureStore)

    @Volatile
    private var currentYearOfBirth: Int = yearOfBirth

    fun start() {
        Log.d("CEP", "CEP Engine started")

        //Call this fn whenever the yob is changed
        updateHrProfile()

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

    /*fun updateHrProfile(newYearOfBirth: Int, hrRest: Double = 60.0) {
        currentYearOfBirth = newYearOfBirth

        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val age = (currentYear - newYearOfBirth).coerceIn(16, 80)

        val hrMax = ZoneHelper.tanakaHrMax(age)

        featureStore.put("profile.hr_rest_bpm", hrRest)
        featureStore.put("profile.hr_max_bpm", hrMax)

        // HR thresholds (loaded once alongside profile)
        featureStore.put("thresholds.hr.high_bpm", hrHigh)
        featureStore.put("thresholds.hr.low_bpm", hrLow)

        Log.d(
            "CEP",
            "Updated HR profile: YOB=$newYearOfBirth age=$age hrRest=$hrRest hrMax=$hrMax " +
                    "hrHigh=$hrHigh hrLow=$hrLow"
        )
    }*/

    fun updateHrProfile() {
        //currentYearOfBirth = newYearOfBirth

        scope.launch {
            try {
                val newYearOfBirth = dataRepository.getYearOfBirth.first()

                val currentYear =
                    java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                val age = (currentYear - newYearOfBirth).coerceIn(16, 80)

                val hrMax = ZoneHelper.tanakaHrMax(age)

                // Load values from DataRepository
                val hrRest = dataRepository.getRestingHeartRate.first() ?: 60.0
                val hrHigh = dataRepository.getHrHighAlertBpmThreshold.first() ?: 140
                val hrLow  = 40.0 //dataRepository.getRestingHeartRate

                // Core HR profile
                featureStore.put("profile.hr_rest_bpm", hrRest)
                featureStore.put("profile.hr_max_bpm", hrMax)

                // HR thresholds
                featureStore.put("thresholds.hr.high_bpm", hrHigh)
                featureStore.put("thresholds.hr.low_bpm",  hrLow)

                Log.d(
                    "CEP",
                    "Updated HR profile: YOB=$newYearOfBirth age=$age " +
                            "hrRest=$hrRest hrMax=$hrMax hrHigh=$hrHigh hrLow=$hrLow"
                )
            } catch (t: Throwable) {
                Log.e("CEP", "Failed to update HR profile from DataRepository", t)
            }
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

    private suspend fun checkAndClearAlerts() {
        val now = System.currentTimeMillis()

        for (alert in AlertRegistry.allActive()) {
            val conditions = alert.exitConditions
            if (conditions.isEmpty()) continue

            val satisfiedFlags = conditions.map { cond ->
                val value = featureStore.latestAsDouble(cond.feature) ?: return@map false

                // Resolve exit thresholds (constant or feature ref)
                val belowThreshold: Double? =
                    cond.exitBelow ?: cond.exitBelowKey?.let { key -> featureStore.latestAsDouble(key) }

                val aboveThreshold: Double? =
                    cond.exitAbove ?: cond.exitAboveKey?.let { key -> featureStore.latestAsDouble(key) }

                val belowOk = cond.exitBelow?.let { value < it - cond.hysteresis } ?: false
                val aboveOk = cond.exitAbove?.let { value > it + cond.hysteresis } ?: false

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
                        // Safe long enough: send CANCEL with same UID
                        /*EventBus.getDefault().post(
                            CustomEmergencyEvent(
                                uid = alert.uid,
                                category = CustomEmergencyEvent.CAT_SOS_ALERT_CANCELLED,
                                description = "Auto-clear ${alert.ruleId}",
                                priority = 0,
                                sendSms = false,
                                isCancel = true
                            )
                        )*/

                        dataRepository.clearPhysioAndAutoGenAlertRequestUid(alert.uid)

                        AlertRegistry.clear(alert.ruleId)
                    }
                }
            } else {
                // back in danger zone → reset timer
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


