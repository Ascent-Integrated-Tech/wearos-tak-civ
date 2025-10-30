package com.aitech.weartak_civ.cep.engine.action

import android.util.Log
import com.aitech.weartak_civ.events.CustomEmergencyEvent
import org.greenrobot.eventbus.EventBus

class LogActionHandler: ActionHandler {
    override val type: String = "log"
    override suspend fun handle(ctx: TriggerContext) {
        val prefix = "[CEP][${ctx.action.severity}][${ctx.ruleId}]"
        val body = "${ctx.action.message.ifEmpty { "Triggered" }} tags=${ctx.action.tags} | snapshot=${ctx.snapshot}"
        when (ctx.action.severity) {
            Severity.EMERGENCY -> Log.e("CEP", "$prefix $body")
            Severity.ALERT, Severity.WARN -> Log.w("CEP", "$prefix $body")
            Severity.INFO -> Log.i("CEP", "$prefix $body")
        }

        // Optional: local nudge if you choose to use it
        if (ctx.action.isLocal) {
            // Hook your existing on-watch UI here if desired
            // AlertUtil.showHeadsUp(ctx.appContext, ctx.action.message, ctx.action.severity)
        }

        // TAK via EventBus if requested by the rule
        if (ctx.action.isTak) {
            val priority = when (ctx.action.priorityFrom) {
                "severity" -> sevToPriority(ctx.action.severity)
                else -> ctx.action.priority ?: 0
            }

            EventBus.getDefault().post(
                CustomEmergencyEvent(
                    category = ctx.action.category ?: "CAT_HEALTH_GENERIC",
                    description = ctx.action.message.ifEmpty { ctx.ruleId },
                    priority = priority,
                    sendSms = ctx.action.sendSms
                )
            )
        }
    }

    private fun sevToPriority(sev: Severity): Int = when (sev) {
        Severity.EMERGENCY -> 1
        Severity.ALERT -> 2
        Severity.WARN -> 2
        Severity.INFO -> 3
    }
}


