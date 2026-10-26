package com.aitech.weartak_civ.cep.engine

import android.app.Application
import com.aitech.weartak_civ.cep.engine.lifecycle.CepEngineHolder
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.OffBodyPayload
import com.aitech.weartak_civ.cep.model.payloads.WindPayload
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WeatherDependenciesTest {
    private val empty = CepConfig(1, emptyList(), emptyList())
    private val windChill = FeatureDef.Derived("features.wind_chill.c",
        JSONObject("""{"wind_chill":{"temp_key":"ambient.temp_c","wind_key":"ambient.wind_mps"}}"""))
    private fun rule(condition: JSONObject = JSONObject("""{"ref":"features.cold"}""")) =
        RuleDef("cold", null, condition, 0, holdDownSec = 0,
            action = RuleAction("log", "WARN", "cold"))

    @Test fun builtinsHaveWeatherConsumersIncludingCustomOperators() {
        val config = RuleCompiler.loadFromRaw(RuntimeEnvironment.getApplication())
        assertTrue(WeatherDependencies.requiresWeather(config))
        assertTrue(WeatherDependencies.requiresWeather(empty.copy(features = listOf(windChill))))
        assertTrue(WeatherDependencies.requiresWeather(empty.copy(features = listOf(
            FeatureDef.Derived("features.wet",
                JSONObject("""{"eq":[{"key":"water_sensor.wet"},true]}"""))))))
    }

    @Test fun executingFeaturesRemainConsumersEvenWithoutRulesAndTransitiveChainsAreKept() {
        val chain = FeatureDef.Derived("features.cold",
            JSONObject("""{"lte":[{"key":"features.wind_chill.c"},-20]}"""))
        val config = empty.copy(features = listOf(windChill, chain), rules = listOf(rule()))
        assertTrue(WeatherDependencies.requiresWeather(config))
        assertTrue(WeatherDependencies.requiresWeather(config.copy(rules = emptyList())))
        assertFalse(WeatherDependencies.requiresWeather(empty))
        assertFalse(WeatherDependencies.requiresWeather(empty.copy(features = listOf(
            FeatureDef.Window("features.hr", "heart_rate", "avg", 30)))))
    }

    @Test fun allWindowShapesAndRawRuleKeysAreConsumers() {
        val source = "ambient.temp_c"
        val windows = listOf(
            FeatureDef.Window("x", source, "avg", 30),
            FeatureDef.MultiWindow("x", source, "min", "avg", 30),
            FeatureDef.SlopeMulti("x", source, "min_channel", 30, "per_second"),
            FeatureDef.Delta("x", source, 30),
            FeatureDef.SampleWindow("x", source, "avg", 5),
            FeatureDef.SampleDelta("x", source, 5),
            FeatureDef.SampleSlope("x", source, 5))
        windows.forEach { assertTrue(it.toString(), WeatherDependencies.requiresWeather(empty.copy(features = listOf(it)))) }
        for (key in WeatherDependencies.outputKeys) {
            assertTrue(WeatherDependencies.requiresWeather(empty.copy(
                rules = listOf(rule(JSONObject("""{"gte_key":["$key",0]}"""))))))
        }
    }

    @Test fun renderSnapshotMessageAndExitKeysAreConsumers() {
        val base = rule()
        val rules = listOf(
            base.copy(renderKeys = listOf("ambient.temp_c")),
            base.copy(snapshotKeys = listOf("water_sensor.wet")),
            base.copy(action = base.action.copy(message = "Wind {ambient.wind_mps}")),
            base.copy(action = base.action.copy(monitor = listOf(ExitCondition("wind.direction_deg")))),
            base.copy(action = base.action.copy(monitor = listOf(
                ExitCondition("features.cold", exitBelowKey = "ambient.temp_c")))),
            base.copy(action = base.action.copy(monitor = listOf(
                ExitCondition("features.cold", exitAboveKey = "ambient.temp_c")))))
        rules.forEach { assertTrue(WeatherDependencies.requiresWeather(empty.copy(rules = listOf(it)))) }
    }

    @Test fun onlyAuthoritativeInstallAndSwapChangeDemand() {
        fun engine(wanted: Boolean): CepEngine = mockk { every { requiresWeather } returns wanted }
        val holder = CepEngineHolder()
        assertFalse(holder.weatherDemand.value)
        val first = engine(true)
        assertTrue(holder.install(first))
        assertTrue(holder.weatherDemand.value)
        assertFalse(holder.install(engine(false)))
        assertTrue(holder.weatherDemand.value)
        assertSame(first, holder.swap(engine(false)))
        assertFalse(holder.weatherDemand.value)
        holder.swap(engine(true))
        assertTrue(holder.weatherDemand.value)
    }

    @Test fun lostDemandClearsInheritedWeatherButPreservesOtherObservations() {
        val previous = WindowStore(RingConfig.default())
        val oldFeatures = FeatureStore()
        previous.ingest(CepEvent(Topics.WIND, WindPayload(8f, 90f)), oldFeatures)
        previous.ingest(CepEvent(Topics.OFFBODY, OffBodyPayload(1)), oldFeatures)
        val replacement = WindowStore(RingConfig.default())
        replacement.adoptIngestionStateFrom(previous)
        replacement.clearWeatherState()
        val field = WindowStore::class.java.getDeclaredField("latest").apply { isAccessible = true }
        val latest = field.get(replacement) as Map<*, *>
        assertFalse(latest.containsKey("ambient.wind_mps"))
        assertEquals(1, latest["offbody.on_wrist"])
        val missing = FeatureStore()
        DerivedEval.eval(windChill, missing)
        assertNull(missing.double(windChill.id))
    }
}
