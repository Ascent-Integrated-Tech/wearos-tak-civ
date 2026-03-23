package com.aitech.weartak_civ.cep.engine.action

import android.util.Log
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.Utils.addToSensorLogFileLocation
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.CoroutineScope


class LogActionHandler(
    appScope: CoroutineScope
) : BaseCepActionHandler(appScope) {

    override val type: String = "log"

    override suspend fun handle(ctx: TriggerContext) {
        val prefix = "[CEP][${ctx.action.severity}][${ctx.ruleId}]"
        val expandedMessage = expandMessage(ctx.action.message.ifEmpty { "Triggered" }, ctx.snapshot)
        val body = "$expandedMessage tags=${ctx.action.tags} | snapshot=${ctx.snapshot}"

        when (ctx.action.severity) {
            Severity.EMERGENCY            -> Log.e("CEP", "$prefix $body")
            Severity.ALERT, Severity.WARN -> Log.w("CEP", "$prefix $body")
            Severity.INFO                 -> Log.i("CEP", "$prefix $body")
        }

        if (ctx.action.isLocal) {
            // Hook your existing on-watch UI here if desired
        }

        if (ctx.action.isTak) {
            val tak = resolveTakContext(ctx)

            val isFirstActivation = AlertRegistry.tryActivate(
                ruleId        = ctx.ruleId,
                priority      = tak.priority,
                uid           = tak.rawUid,
                exitConditions = tak.exitConditions,
                exitLogic     = tak.exitLogic,
                exitSustainSec = tak.sustainSec
            )
            if (!isFirstActivation) {
                Log.i("CEP", "[AlertRegistry] Suppressing duplicate alert for rule=${ctx.ruleId}")
                return
            }

            Utils.vibrateWatch(vibDurationMs = 200L, numberOfVibes = 5, timeBtwnVibesMs = 120L)
            if (tak.priority == 1) {
                dataRepository.addPhysioAndAutoGenAlertRequest(
                    uid = tak.rawUid,
                    category = ctx.action.category ?: "CAT_HEALTH_GENERIC",
                    description = expandedMessage.ifEmpty { ctx.ruleId },
                    priority = tak.priority,
                    sendSms = ctx.action.sendSms,
                    timeMillis = System.currentTimeMillis()
                )
            } else {
                dataRepository.appendPhysioAndAutoGenAlert(
                    DataRepository.CustomEmergencyAlert(uuid = tak.rawUid,
                        alertState = DataRepository.AlertState.ALERT,
                        category = ctx.action.category ?: "CAT_HEALTH_GENERIC",
                        description = expandedMessage.ifEmpty { ctx.ruleId },
                        priority = tak.priority,
                        sendSms = ctx.action.sendSms,
                        timeMillis = System.currentTimeMillis(),
                        enqueuedToBeSent = false))
                addToSensorLogFileLocation(Utils.SensorLogType.ALERT_CLEAR, -1, "AutoGenAlert: ${ctx.ruleId} (desc=${expandedMessage.ifEmpty { ctx.ruleId }})")
            }
        }
    }
}
