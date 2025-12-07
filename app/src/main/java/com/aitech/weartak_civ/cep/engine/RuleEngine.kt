package com.aitech.weartak_civ.cep.engine

import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.engine.action.ActionDef
import com.aitech.weartak_civ.cep.engine.action.Severity
import com.aitech.weartak_civ.cep.engine.action.TriggerContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class RuleEngine(
    private val scope: CoroutineScope,
    private val config: CepConfig,
    private val fs: FeatureStore
) {
    private val dispatcher = CepProvider.getActionDispatcher()
    private val compiledTimers = config.rules.associate { it.id to TriggerTimer(it.sustainedForSec, it.holdDownSec) }

    suspend fun processTick(now: Long = System.currentTimeMillis()) {
        config.rules.forEach { r ->
            val cond = JsonLogic.eval(r.condition, fs)
            //val fired = compiledTimers[r.id]!!.tick(cond, now)

            val fired = compiledTimers[r.id]?.tick(cond, now) ?: false

            if (fired) {
                val snapshot = fs.snapshot(r.snapshotKeys)
                val sev = when (r.action.severity.uppercase()) {
                    "WARN" -> Severity.WARN
                    "ALERT" -> Severity.ALERT
                    "EMERGENCY" -> Severity.EMERGENCY
                    else -> Severity.INFO
                }
                val ctx = TriggerContext(
                    ruleId = r.id,
                    action = ActionDef(
                        type        = r.action.type,
                        severity    = sev,
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
                    ),
                    snapshot = snapshot,
                    ts = now
                )
                scope.launch { dispatcher.dispatch(ctx) }
            }
        }
    }
}


