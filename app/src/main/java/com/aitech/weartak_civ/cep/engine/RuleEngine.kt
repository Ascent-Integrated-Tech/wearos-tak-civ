package com.aitech.weartak_civ.cep.engine

import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.engine.action.ActionDef
import com.aitech.weartak_civ.cep.engine.action.CepActionDispatcher
import com.aitech.weartak_civ.cep.engine.action.Severity
import com.aitech.weartak_civ.cep.engine.action.TriggerContext
import com.aitech.weartak_civ.cep.engine.TickResult
import com.aitech.weartak_civ.cep.engine.config.CepDiagnosticRecord
import com.aitech.weartak_civ.cep.engine.config.CepDiagnosticSink
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.aitech.weartak_civ.cep.engine.config.SourceLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class RuleEngine(
    private val scope: CoroutineScope,
    private val config: CepConfig,
    private val fs: FeatureStore,
    private val sustainOverrideMainProvider: () -> Map<String, Long> = { emptyMap() },
    private val sustainOverrideWarnProvider: () -> Map<String, Long> = { emptyMap() },
    private val dispatcher: CepActionDispatcher = CepProvider.getActionDispatcher(),
    private val diagnosticsProvider: () -> CepDiagnosticSink = { CepProvider.diagnostics() }
) {
    private val compiledTimers = config.rules.associate { it.id to TriggerTimer(it.holdDownSec) }
    private val missingRenderEpisodes = ConcurrentHashMap.newKeySet<String>()

    private fun resolveSeverity(raw: String): Severity = when (raw.uppercase()) {
        "WARN"      -> Severity.WARN
        "ALERT"     -> Severity.ALERT
        "EMERGENCY" -> Severity.EMERGENCY
        else        -> Severity.INFO
    }

    suspend fun processTick(now: Long = System.currentTimeMillis()) {
        val overridesMain = sustainOverrideMainProvider()
        val overridesWarn = sustainOverrideWarnProvider()
        config.rules.forEach { r ->
            val decision = evaluateRule(r, now, overridesMain, overridesWarn) ?: return@forEach
            if (decision.warnFired) {
                compiledTimers[r.id]?.consumeWarn()
                scope.launch {
                    dispatcher.dispatch(
                        TriggerContext(
                            ruleId = r.id, action = decision.action.copy(type = "warn"),
                            snapshot = decision.snapshot, ts = now, isPreWarn = true
                        )
                    )
                }
            }
            if (decision.mainFired) {
                //Log.i("CEP", "result.mainFired (${result.warnFired}, ${result.mainFired}); sustainWarnSec=$sustainWarnSec; effMain=$effMain")
                compiledTimers[r.id]?.consumeMain(now)
                scope.launch {
                    dispatcher.dispatch(
                        TriggerContext(
                            ruleId = r.id, action = decision.action,
                            snapshot = decision.snapshot, ts = now
                        )
                    )
                }
            }
        }
    }

    fun resetForTest() {
        compiledTimers.values.forEach { it.reset() }
        missingRenderEpisodes.clear()
        dispatcher.resetForTest()
    }

    private fun renderSnapshotKeys(rule: RuleDef): List<String> {
        if (rule.renderKeys.isEmpty()) return rule.snapshotKeys
        if (rule.snapshotKeys.isEmpty()) return rule.renderKeys
        return (rule.snapshotKeys + rule.renderKeys).distinct()
    }

    private fun buildDispatchPlan(rule: RuleDef, snapshot: Map<String, Any?>): DispatchPlan {
        val action = ActionDef(
            type = rule.action.type,
            severity = resolveSeverity(rule.action.severity.uppercase()),
            message = rule.action.message,
            tags = rule.action.tags,
            category = rule.action.category,
            isTak = rule.action.isTak,
            isLocal = rule.action.isLocal,
            priorityFrom = rule.action.priorityFrom,
            priority = rule.action.priority,
            sendSms = rule.action.sendSms,
            timeoutSec = rule.action.timeoutSec,
            uid = rule.action.uid,
            monitor = rule.action.monitor,
            exitLogic = rule.action.exitLogic,
            exitSustainSec = rule.action.exitSustainSec
        )
        val missingKeys = missingRenderKeys(rule, snapshot)
        val effectiveAction = if (missingKeys.isEmpty()) action else when (rule.onMissingRender) {
            MissingRenderPolicy.SUPPRESS -> action
            MissingRenderPolicy.DEGRADE -> action.copy(message = degradedMessage(rule.id, action.message))
        }
        return DispatchPlan(effectiveAction, missingKeys)
    }

    private fun evaluateRule(
        rule: RuleDef,
        now: Long,
        overridesMain: Map<String, Long>,
        overridesWarn: Map<String, Long>
    ): RuleDispatchDecision? {
        val cond = JsonLogic.eval(rule.condition, fs)
        val base = rule.sustainedForSec
        val effMain = if (rule.allowSustainOverride) {
            overridesMain[rule.id]?.coerceAtLeast(0L) ?: base
        } else {
            base
        }
        val effWarn = if (rule.allowSustainOverride && rule.sustainedForSecWarn != null) {
            overridesWarn[rule.id]?.coerceAtLeast(0L) ?: rule.sustainedForSecWarn
        } else {
            rule.sustainedForSecWarn
        }
        val sustainWarnSec = effWarn?.takeIf { it in 1 until effMain }
        val result = compiledTimers[rule.id]?.tick(cond, now, effMain, sustainWarnSec) ?: TickResult(false, false)
        if (!result.warnFired && !result.mainFired) {
            if (!cond) missingRenderEpisodes.remove(rule.id)
            return null
        }

        val snapshot = fs.snapshot(renderSnapshotKeys(rule))
        val plan = buildDispatchPlan(rule, snapshot)
        if (plan.missingKeys.isNotEmpty()) {
            recordMissingRenderEpisode(rule.id, plan.missingKeys, plan.action.severity, rule.onMissingRender)
            if (rule.onMissingRender == MissingRenderPolicy.SUPPRESS) return null
        } else {
            missingRenderEpisodes.remove(rule.id)
        }
        return RuleDispatchDecision(
            warnFired = result.warnFired,
            mainFired = result.mainFired,
            snapshot = snapshot,
            action = plan.action
        )
    }

    private fun missingRenderKeys(rule: RuleDef, snapshot: Map<String, Any?>): List<String> {
        if (rule.renderKeys.isEmpty()) return emptyList()
        return rule.renderKeys.filter { snapshot[it] == null }
    }

    private fun degradedMessage(ruleId: String, message: String): String {
        val stripped = MessageTemplate.strip(message)
        return stripped.ifBlank { ruleId }
    }

    private fun recordMissingRenderEpisode(
        ruleId: String,
        missingKeys: List<String>,
        severity: Severity,
        policy: MissingRenderPolicy
    ) {
        if (!missingRenderEpisodes.add(ruleId)) return
        diagnosticsProvider().record(
            CepDiagnosticRecord(
                timestamp = System.currentTimeMillis(),
                sourceLayer = SourceLayer.RUNTIME,
                reasonCode = ReasonCodes.MISSING_RENDER_VALUES,
                fallbackDecision = policy.name.lowercase(),
                message = "rule=$ruleId missing render keys=${missingKeys.joinToString()} severity=${severity.name.lowercase()}"
            )
        )
    }

    private data class DispatchPlan(
        val action: ActionDef,
        val missingKeys: List<String>
    )

    private data class RuleDispatchDecision(
        val warnFired: Boolean,
        val mainFired: Boolean,
        val snapshot: Map<String, Any?>,
        val action: ActionDef
    )
}
