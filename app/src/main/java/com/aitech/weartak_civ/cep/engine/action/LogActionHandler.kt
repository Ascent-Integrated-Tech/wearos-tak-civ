package com.aitech.weartak_civ.cep.engine.action

import android.util.Log
import com.aitech.weartak_civ.events.CustomEmergencyEvent
import com.aitech.weartak_civ.service.CotDispatchService
import org.greenrobot.eventbus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.UUID


class LogActionHandler(
    private val appScope: CoroutineScope
): ActionHandler {
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

            val uid = (ctx.action.uid?.takeIf { it.isNotBlank() }) ?: UUID.randomUUID().toString()

            EventBus.getDefault().post(
                CustomEmergencyEvent(
                    uid = uid,
                    category = ctx.action.category ?: "CAT_HEALTH_GENERIC",
                    description = ctx.action.message.ifEmpty { ctx.ruleId },
                    priority = priority,
                    sendSms = ctx.action.sendSms,
                    isCancel = false
                )
            )

            // 3) Optional auto-cancel after timeout using the SAME uid
            ctx.action.timeoutSec?.let { seconds ->
                if (seconds > 0) {
                    appScope.launch {
                        delay(seconds * 1000L)
                        EventBus.getDefault().post(
                            CustomEmergencyEvent(
                                uid = uid, // <-- same UID so TAK cancels the exact marker
                                // You can keep legacy category cancel for backward compatibility:
                                category = CustomEmergencyEvent.CAT_SOS_ALERT_CANCELLED,
                                description = "Cancel ${ctx.ruleId}",
                                priority = 0,
                                sendSms = false,
                                isCancel = true // new path; your dispatch will also look at category for legacy
                            )
                        )
                    }
                }
            }

        }
    }

    private fun sevToPriority(sev: Severity): Int = when (sev) {
        Severity.EMERGENCY -> 1
        Severity.ALERT -> 2
        Severity.WARN -> 2
        Severity.INFO -> 3
    }
}


