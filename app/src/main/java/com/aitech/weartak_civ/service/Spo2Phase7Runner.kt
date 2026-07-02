package com.aitech.weartak_civ.service

import android.content.Context
import android.util.Log
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.engine.action.AlertRegistry
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.ActivityPayload
import com.aitech.weartak_civ.cep.model.payloads.OffBodyPayload
import com.aitech.weartak_civ.cep.model.payloads.SleepPayload
import com.aitech.weartak_civ.cep.model.payloads.Spo2EdgeState
import com.aitech.weartak_civ.cep.model.payloads.Spo2Tier
import com.aitech.weartak_civ.cep.model.payloads.Spo2TierEventPayload
import kotlinx.coroutines.delay

data class Spo2ScenarioAssertionResult(
    val scenario: String,
    val pass: Boolean,
    val expectedRuleIds: Set<String>,
    val observedRuleIds: Set<String>,
    val expectedCounts: Map<String, Int>,
    val observedCounts: Map<String, Int>,
    val missing: Map<String, Int>,
    val unexpected: Set<String>,
    val assertionFailures: List<String> = emptyList(),
)

enum class Spo2Phase7Scenario {
    SPO2_TRAUMA_HIGH_RISK_ESCALATION,
    SPO2_EMERGENCY_CORROBORATION_PATH,
    SPO2_MIXED_WINDOW_WARN_ABSTAIN,
    SPO2_LOW_BASELINE_WARN_CASE,
    SPO2_LONG_SENSOR_OUTAGE_CLEAR,
    SPO2_TRANSPORT_DUAL_PATH_SAFETY,
    SPO2_BASELINE_ABSTAIN_NON_REST_NON_WALK,
}

data class Spo2Phase7RunnerEnv(
    val applicationContext: Context,
    val loggerTag: String = "Simulation",
)

class Spo2Phase7Runner(
    private val env: Spo2Phase7RunnerEnv,
) {
    private companion object {
        const val OBSERVATION_POLL_MS = 300L
        const val OBSERVATION_TIMEOUT_MS = 4_500L
    }

    val reviewedScenarios: List<Spo2Phase7Scenario> = listOf(
        Spo2Phase7Scenario.SPO2_TRAUMA_HIGH_RISK_ESCALATION,
        Spo2Phase7Scenario.SPO2_EMERGENCY_CORROBORATION_PATH,
        Spo2Phase7Scenario.SPO2_MIXED_WINDOW_WARN_ABSTAIN,
        Spo2Phase7Scenario.SPO2_LOW_BASELINE_WARN_CASE,
        Spo2Phase7Scenario.SPO2_LONG_SENSOR_OUTAGE_CLEAR,
        Spo2Phase7Scenario.SPO2_TRANSPORT_DUAL_PATH_SAFETY,
        Spo2Phase7Scenario.SPO2_BASELINE_ABSTAIN_NON_REST_NON_WALK,
    )

    suspend fun runReviewedScenarios(): List<Spo2ScenarioAssertionResult> {
        val out = mutableListOf<Spo2ScenarioAssertionResult>()
        for (scenario in reviewedScenarios) out += runScenario(scenario)
        return out
    }

    suspend fun runScenario(scenario: Spo2Phase7Scenario): Spo2ScenarioAssertionResult {
        val appContext = env.applicationContext
        val cepEngine = CepProvider.getOrCreateEngine(appContext)
        val profile = CepProvider.getProfile(appContext)
        val now = System.currentTimeMillis()
        cepEngine.resetSpo2SimulationStateForTest()
        val assertionFailures = mutableListOf<String>()
        val (spo2Fired, spo2Counts) = when (scenario) {
            Spo2Phase7Scenario.SPO2_TRAUMA_HIGH_RISK_ESCALATION -> runSpo2SequencerCase(
                scenario = scenario,
                readings = listOf(
                    Spo2SimInput(89.0), Spo2SimInput(89.0), Spo2SimInput(89.0), Spo2SimInput(89.0),
                ),
                riskProfile = "high_risk",
            )
            Spo2Phase7Scenario.SPO2_EMERGENCY_CORROBORATION_PATH -> runSpo2SequencerCase(
                scenario = scenario,
                readings = listOf(Spo2SimInput(87.0), Spo2SimInput(89.0)),
            )
            Spo2Phase7Scenario.SPO2_MIXED_WINDOW_WARN_ABSTAIN -> runSpo2SequencerCase(
                scenario = scenario,
                readings = listOf(
                    Spo2SimInput(93.0, activityState = "STILL"),
                    Spo2SimInput(93.0, activityState = "WALKING"),
                    Spo2SimInput(93.0, activityState = "STILL"),
                    Spo2SimInput(93.0, activityState = "WALKING"),
                ),
            )
            Spo2Phase7Scenario.SPO2_LOW_BASELINE_WARN_CASE -> runSpo2SequencerCase(
                scenario = scenario,
                readings = listOf(
                    Spo2SimInput(90.0), Spo2SimInput(90.0), Spo2SimInput(90.0), Spo2SimInput(90.0),
                ),
            )
            Spo2Phase7Scenario.SPO2_LONG_SENSOR_OUTAGE_CLEAR -> runSpo2SequencerCase(
                scenario = scenario,
                readings = listOf(
                    Spo2SimInput(89.0), Spo2SimInput(89.0), Spo2SimInput(89.0), Spo2SimInput(89.0),
                ),
            ) { sequencer, observer, elapsedMs ->
                sequencer.onInvalid(now + 360_000L, InvalidReason.TIMEOUT)
                observer.awaitSpo2Activity(elapsedMs + 360_000L)
            }
            Spo2Phase7Scenario.SPO2_TRANSPORT_DUAL_PATH_SAFETY -> {
                AlertRegistry.clearAll()
                emitSpo2Context(now, "STILL")
                val observer = SimulationObserver(scenario.name)
                val payload = Spo2TierEventPayload(
                    tier = Spo2Tier.ALERT,
                    state = Spo2EdgeState.ENTER,
                    pct = 89.0,
                    ts = now,
                    reason = "DUAL_PATH_SAFETY",
                )
                Log.i(env.loggerTag, "[P7][$scenario] direct_handleTierEvent payload=$payload")
                cepEngine.handleTierEvent(payload)
                CepBus.emit(CepEvent(Topics.SPO2_TIER_EVENT, payload, payload.ts))
                observer.awaitSpo2Activity(900L)
                observer.report(env.loggerTag)
                observer.firedRuleIds().filter { it.startsWith("spo2_") }.toSet() to
                    observer.firedRuleCounts().filterKeys { it.startsWith("spo2_") }
            }
            Spo2Phase7Scenario.SPO2_BASELINE_ABSTAIN_NON_REST_NON_WALK -> {
                val bucket = 0
                seedSpo2Baselines(profile)
                val beforeRestCount = profile.spo2BaselineSampleCount("resting", bucket)
                val beforeWalkCount = profile.spo2BaselineSampleCount("walk", bucket)
                val beforeRestBaseline = profile.spo2Baseline("resting", bucket)
                val beforeWalkBaseline = profile.spo2Baseline("walk", bucket)
                val result = runSpo2SequencerCase(
                    scenario = scenario,
                    readings = listOf(
                        Spo2SimInput(96.0, activityState = "RUN"),
                        Spo2SimInput(96.0, activityState = "VEHICLE"),
                        Spo2SimInput(96.0, activityState = "UNKNOWN"),
                        Spo2SimInput(96.0, activityState = "RUN"),
                    ),
                    seedBaselines = false,
                )
                val afterRestCount = profile.spo2BaselineSampleCount("resting", bucket)
                val afterWalkCount = profile.spo2BaselineSampleCount("walk", bucket)
                val afterRestBaseline = profile.spo2Baseline("resting", bucket)
                val afterWalkBaseline = profile.spo2Baseline("walk", bucket)
                if (afterRestCount != beforeRestCount) assertionFailures += "RESTING_COUNT_CHANGED before=$beforeRestCount after=$afterRestCount"
                if (afterWalkCount != beforeWalkCount) assertionFailures += "WALK_COUNT_CHANGED before=$beforeWalkCount after=$afterWalkCount"
                if (afterRestBaseline != beforeRestBaseline) assertionFailures += "RESTING_BASELINE_CHANGED before=$beforeRestBaseline after=$afterRestBaseline"
                if (afterWalkBaseline != beforeWalkBaseline) assertionFailures += "WALK_BASELINE_CHANGED before=$beforeWalkBaseline after=$afterWalkBaseline"
                result
            }
        }

        val expectedCounts = when (scenario) {
            Spo2Phase7Scenario.SPO2_TRAUMA_HIGH_RISK_ESCALATION -> mapOf("spo2_alert_sustained_hypoxemia" to 1)
            Spo2Phase7Scenario.SPO2_EMERGENCY_CORROBORATION_PATH -> mapOf("spo2_emergency_severe_hypoxemia" to 1)
            Spo2Phase7Scenario.SPO2_MIXED_WINDOW_WARN_ABSTAIN -> emptyMap()
            Spo2Phase7Scenario.SPO2_LOW_BASELINE_WARN_CASE -> mapOf("spo2_warn_baseline_drop" to 1)
            Spo2Phase7Scenario.SPO2_LONG_SENSOR_OUTAGE_CLEAR -> mapOf("spo2_alert_sustained_hypoxemia" to 1, "spo2_clear_any" to 1)
            Spo2Phase7Scenario.SPO2_TRANSPORT_DUAL_PATH_SAFETY -> mapOf("spo2_alert_sustained_hypoxemia" to 1)
            Spo2Phase7Scenario.SPO2_BASELINE_ABSTAIN_NON_REST_NON_WALK -> emptyMap()
        }
        val missing = expectedCounts.filter { (id, minCount) -> spo2Counts.getOrDefault(id, 0) < minCount }
        val unexpected = spo2Counts.keys - expectedCounts.keys
        val pass = missing.isEmpty() && unexpected.isEmpty() && assertionFailures.isEmpty()
        val expectedRuleIds = expectedCounts.keys
        val observedRuleIds = spo2Counts.keys
        Log.i(
            env.loggerTag,
            "[ASSERT][$scenario] ${if (pass) "PASS" else "FAIL"} expectedRuleIds=$expectedRuleIds observedRuleIds=$observedRuleIds expectedCounts=$expectedCounts observedCounts=$spo2Counts missing=$missing unexpected=$unexpected assertionFailures=$assertionFailures observedIds=$spo2Fired",
        )
        AlertRegistry.clearAll()
        return Spo2ScenarioAssertionResult(
            scenario = scenario.name,
            pass = pass,
            expectedRuleIds = expectedRuleIds,
            observedRuleIds = observedRuleIds,
            expectedCounts = expectedCounts,
            observedCounts = spo2Counts,
            missing = missing,
            unexpected = unexpected,
            assertionFailures = assertionFailures,
        )
    }

    private data class Spo2SimInput(
        val pct: Double,
        val activityState: String = "STILL",
        val altitudeBucket: Int = 0,
        val motionOk: Boolean = true,
        val ppgRedSustainedMs: Long = 60_000L,
        val statusOk: Boolean = true,
        val stepMs: Long = 30_000L,
    )

    private suspend fun emitSpo2Context(ts: Long, activityState: String, confidence: Int = 90, onWrist: Int = 1) {
        CepBus.emit(CepEvent(Topics.ACTIVITY, ActivityPayload(activityState, confidence = confidence), ts = ts))
        CepBus.emit(CepEvent(Topics.OFFBODY, OffBodyPayload(onWrist), ts = ts))
        CepBus.emit(CepEvent(Topics.SLEEP, SleepPayload(false), ts = ts))
    }

    private suspend fun runSpo2SequencerCase(
        scenario: Spo2Phase7Scenario,
        readings: List<Spo2SimInput>,
        riskProfile: String = "default",
        seedBaselines: Boolean = true,
        postReadings: suspend (Spo2Sequencer, SimulationObserver, Long) -> Unit = { _, _, _ -> },
    ): Pair<Set<String>, Map<String, Int>> {
        val appContext = env.applicationContext
        val cepEngine = CepProvider.getOrCreateEngine(appContext)
        val profile = CepProvider.getProfile(appContext)
        cepEngine.resetSpo2SimulationStateForTest()
        profile.setUserRiskProfile(riskProfile)
        if (seedBaselines) {
            seedSpo2Baselines(profile)
        }
        val now = System.currentTimeMillis()
        cepEngine.updateSpo2Profile()
        val observer = SimulationObserver(scenario.name)

        val sequencer = Spo2Sequencer(
            profile = profile,
            publish = { payload ->
                Log.i(env.loggerTag, "[P7][$scenario] publish tier=${payload.tier} state=${payload.state} pct=${payload.pct} ts=${payload.ts} reason=${payload.reason}")
                cepEngine.handleTierEvent(payload)
            },
            ingest = { path, value, ts -> cepEngine.ingest(path, value, ts) },
        )

        var ts = now
        var elapsedMs = 0L
        for (reading in readings) {
            emitSpo2Context(ts, reading.activityState, confidence = if (reading.motionOk) 90 else 20, onWrist = 1)
            sequencer.onValid(
                pct = reading.pct,
                ts = ts,
                activityState = reading.activityState,
                altitudeBucket = reading.altitudeBucket,
                motionOk = reading.motionOk,
                ppgRedSustainedMs = reading.ppgRedSustainedMs,
                statusOk = reading.statusOk,
            )
            elapsedMs += reading.stepMs
            observer.awaitSpo2Activity(elapsedMs)
            ts += reading.stepMs
        }
        postReadings(sequencer, observer, elapsedMs)
        observer.report(env.loggerTag)
        return observer.firedRuleIds().filter { it.startsWith("spo2_") }.toSet() to
            observer.firedRuleCounts().filterKeys { it.startsWith("spo2_") }
    }

    private fun seedSpo2Baselines(profile: com.aitech.weartak_civ.cep.engine.ProfileStore) {
        val now = System.currentTimeMillis()
        repeat(24) { i ->
            val ts = now - (24 - i) * 30_000L
            profile.updateSpo2BaselinesEma(97.0, ts, 0, "STILL", true)
            profile.updateSpo2BaselinesEma(95.0, ts, 0, "WALKING", true)
        }
    }

    private class SimulationObserver(private val scenario: String) {
        private val startMs = System.currentTimeMillis()
        private val firstFireMs = mutableMapOf<String, Long>()
        private val fireCounts = mutableMapOf<String, Int>()
        private val baselineIds: Set<String> = AlertRegistry.allActive().map { it.ruleId }.toSet()

        fun sample(elapsedMs: Long) {
            for (alert in AlertRegistry.allActive()) {
                if (alert.ruleId in baselineIds) continue
                if (!firstFireMs.containsKey(alert.ruleId)) firstFireMs[alert.ruleId] = elapsedMs
                fireCounts[alert.ruleId] = fireCounts.getOrDefault(alert.ruleId, 0) + 1
            }
        }

        fun firedRuleIds(): Set<String> = firstFireMs.keys
        fun firedRuleCounts(): Map<String, Int> = fireCounts.toMap()

        suspend fun awaitSpo2Activity(elapsedMs: Long, timeoutMs: Long = OBSERVATION_TIMEOUT_MS) {
            var waited = 0L
            do {
                sample(elapsedMs + waited)
                if (firedRuleIds().any { it.startsWith("spo2_") }) return
                delay(OBSERVATION_POLL_MS)
                waited += OBSERVATION_POLL_MS
            } while (waited < timeoutMs)
        }

        fun report(tag: String) {
            val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
            Log.i(tag, "[ASSERT][$scenario] SUMMARY elapsedSec=$elapsed firedRuleIds=${firedRuleIds()} firedRuleCounts=${firedRuleCounts()}")
        }
    }
}
