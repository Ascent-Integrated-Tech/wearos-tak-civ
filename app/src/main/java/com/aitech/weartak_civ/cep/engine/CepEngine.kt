package com.aitech.weartak_civ.cep.engine

import android.content.Context
import com.aitech.weartak_civ.cep.bus.CepBus
import kotlinx.coroutines.*

class CepEngine(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val config: CepConfig
) {
    private val featureStore = FeatureStore()
    private val windows = WindowStore(config.tickHz, RingConfig.default())
    private val ruleEngine = RuleEngine(scope, config, featureStore)

    fun start() {
        // Subscribe to the bus (no emitters yet; safe)
        scope.launch { CepBus.events.collect { evt -> windows.ingest(evt, featureStore) } }

        // Tick loop
        scope.launch {
            val tickMs = (1000L / config.tickHz).coerceAtLeast(1L)
            while (isActive) {
                computeFeaturesSafely()
                ruleEngine.processTick()
                delay(tickMs)
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
                android.util.Log.e("CEP", "Feature compute failed for ${def.id}", ex)
            }
        }
    }
}


