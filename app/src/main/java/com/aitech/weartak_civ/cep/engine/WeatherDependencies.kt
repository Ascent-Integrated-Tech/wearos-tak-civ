package com.aitech.weartak_civ.cep.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Every effective feature executes, including features not referenced by a rule. Inspect
 * nested values too: custom operators such as wind_chill use temp_key / wind_key.
 * Exact string matches are deliberately conservative (a matching literal also keeps demand).
 */
internal object WeatherDependencies {
    val outputKeys = setOf("ambient.temp_c", "ambient.wind_mps", "wind.direction_deg", "water_sensor.wet")

    fun requiresWeather(config: CepConfig): Boolean =
        config.features.any { feature ->
            when (feature) {
                is FeatureDef.Derived -> referencesWeather(feature.expr)
                is FeatureDef.Window -> referencesWeather(feature.source)
                is FeatureDef.MultiWindow -> referencesWeather(feature.source)
                is FeatureDef.SlopeMulti -> referencesWeather(feature.source)
                is FeatureDef.Delta -> referencesWeather(feature.source)
                is FeatureDef.SampleWindow -> referencesWeather(feature.source)
                is FeatureDef.SampleDelta -> referencesWeather(feature.source)
                is FeatureDef.SampleSlope -> referencesWeather(feature.source)
            }
        } || config.rules.any { rule ->
            referencesWeather(rule.condition) ||
                (rule.renderKeys + rule.snapshotKeys + MessageTemplate.extractKeys(rule.action.message))
                    .any(::referencesWeather) ||
                rule.action.monitor.any {
                    referencesWeather(it.feature) || referencesWeather(it.exitBelowKey) ||
                        referencesWeather(it.exitAboveKey)
                }
        }

    private fun referencesWeather(value: Any?): Boolean = when (value) {
        is String -> value in outputKeys || value == "wind" || value == "water"
        is JSONObject -> value.keys().asSequence().any { referencesWeather(value.opt(it)) }
        is JSONArray -> (0 until value.length()).any { referencesWeather(value.opt(it)) }
        else -> false
    }
}
