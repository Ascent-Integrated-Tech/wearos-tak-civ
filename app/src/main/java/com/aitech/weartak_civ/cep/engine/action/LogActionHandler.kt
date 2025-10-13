package com.aitech.weartak_civ.cep.engine.action

import android.util.Log

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
    }
}


