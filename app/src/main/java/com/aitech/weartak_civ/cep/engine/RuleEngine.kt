package com.aitech.weartak_civ.cep.engine

import android.util.Log
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.engine.action.ActionDef
import com.aitech.weartak_civ.cep.engine.action.Severity
import com.aitech.weartak_civ.cep.engine.action.TriggerContext
import com.aitech.weartak_civ.cep.engine.TickResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class RuleEngine(
    private val scope: CoroutineScope,
    private val config: CepConfig,
    private val fs: FeatureStore,
    private val sustainOverrideMainProvider: () -> Map<String, Long> = { emptyMap() },
    private val sustainOverrideWarnProvider: () -> Map<String, Long> = { emptyMap() }
) {
    private val dispatcher = CepProvider.getActionDispatcher()
    private val compiledTimers = config.rules.associate { it.id to TriggerTimer(it.holdDownSec) }

    // ✅ Extracted: removes the `when` block + its nesting increment from processTick
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
            val cond = JsonLogic.eval(r.condition, fs)
            val base = r.sustainedForSec
            val effMain = if (r.allowSustainOverride) {
                overridesMain[r.id]?.coerceAtLeast(0L) ?: base
            } else {
                base
            }
            val effWarn = if (r.allowSustainOverride && r.sustainedForSecWarn != null) {
                overridesWarn[r.id]?.coerceAtLeast(0L) ?: r.sustainedForSecWarn
            } else {
                r.sustainedForSecWarn
            }
            val sustainWarnSec = effWarn?.takeIf { it in 1 until effMain }
            val result = compiledTimers[r.id]?.tick(cond, now, effMain, sustainWarnSec) ?: TickResult(false, false)

            if (!result.warnFired && !result.mainFired) return@forEach

            val snapshot = fs.snapshot(r.snapshotKeys)
            val action = ActionDef(
                type        = r.action.type,
                severity    = resolveSeverity(r.action.severity), // ✅ one-liner replaces the when block
                message     = r.action.message,
                tags        = r.action.tags,
                category    = r.action.category,
                isTak       = r.action.isTak,
                isLocal     = r.action.isLocal,
                priorityFrom= r.action.priorityFrom,
                priority    = r.action.priority,
                sendSms     = r.action.sendSms,
                timeoutSec  = r.action.timeoutSec,
                uid         = r.action.uid,
                monitor     = r.action.monitor,
                exitLogic   = r.action.exitLogic,
                exitSustainSec = r.action.exitSustainSec
            )

            if (result.warnFired) {
                Log.d("CEP", "result.warnFired (${result.warnFired}, ${result.mainFired}); sustainWarnSec=$sustainWarnSec; effMain=$effMain")
                scope.launch {
                    dispatcher.dispatch(
                        TriggerContext(ruleId = r.id, action = action.copy(type = "warn"),
                            snapshot = snapshot, ts = now, isPreWarn = true)
                    )
                }
            }
            if (result.mainFired) {
                Log.d("CEP", "result.mainFired (${result.warnFired}, ${result.mainFired}); sustainWarnSec=$sustainWarnSec; effMain=$effMain")
                scope.launch {
                    dispatcher.dispatch(
                        TriggerContext(ruleId = r.id, action = action,
                            snapshot = snapshot, ts = now)
                    )
                }
            }
        }
    }
}


