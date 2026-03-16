/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 */

package com.aitech.weartak_civ.cep.engine.action

import android.content.Intent
import android.util.Log
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.UserWarnActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Handles CEP rules with action type "warn": same pipeline as log (AlertRegistry + physio request)
 * plus shows UserWarnActivity so the user can Acknowledge or Cancel.
 */
class WarnActionHandler(
    private val appScope: CoroutineScope
) : ActionHandler {

    override val type: String = "warn"

    private val PLACEHOLDER_REGEX = "\\{([a-zA-Z0-9._-]+)\\}".toRegex()

    private fun expandMessage(template: String, snapshot: Map<String, Any?>): String {
        if (!template.contains("{")) return template
        return PLACEHOLDER_REGEX.replace(template) { match ->
            val key = match.groupValues[1]
            snapshot[key]?.toString() ?: "?"
        }
    }

    override suspend fun handle(ctx: TriggerContext) {
        val expandedMessage = expandMessage(
            ctx.action.message.ifEmpty { "Triggered" },
            ctx.snapshot
        )
        Log.w("CEP", "[CEP][${ctx.action.severity}][${ctx.ruleId}] $expandedMessage (warn)")

        if (!ctx.action.isTak) return

        val priority = when (ctx.action.priorityFrom) {
            "severity" -> sevToPriority(ctx.action.severity)
            else -> ctx.action.priority ?: 0
        }
        val rawUid = ctx.action.uid?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val exitConditions = ctx.action.monitor
        val exitLogic = (ctx.action.exitLogic ?: "AND").uppercase()
        val sustainSec = ctx.action.exitSustainSec ?: 0L

        if (!ctx.isPreWarn) {
            val isFirstActivation = AlertRegistry.tryActivate(
                ruleId = ctx.ruleId,
                uid = rawUid,
                exitConditions = exitConditions,
                exitLogic = exitLogic,
                exitSustainSec = sustainSec
            )
            if (!isFirstActivation) {
                Log.d("CEP", "[AlertRegistry] Suppressing duplicate warn for rule=${ctx.ruleId}")
                return
            }
        }

        val uid = rawUid
        val category = ctx.action.category ?: "CAT_HEALTH_GENERIC"

        /*dataRepository.addPhysioAndAutoGenAlertRequest(
            uid = uid,
            category = category,
            description = expandedMessage.ifEmpty { ctx.ruleId },
            priority = priority,
            sendSms = ctx.action.sendSms,
            timeMillis = System.currentTimeMillis()
        )*/

        // Show UserWarnActivity only if user warning is enabled
        val enableUserWarning = dataRepository.getEnableUserWarning.first()
        if (enableUserWarning) {
            val appContext = WearTakApplication.AppCtx.app.applicationContext
            Utils.vibrateWatch(vibDurationMs = 200L, numberOfVibes = 5, timeBtwnVibesMs = 120L)
            val intent = Intent(appContext, UserWarnActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(UserWarnActivity.EXTRA_UID, uid)
                putExtra(UserWarnActivity.EXTRA_CATEGORY, category)
                putExtra(UserWarnActivity.EXTRA_DESCRIPTION, expandedMessage.ifEmpty { ctx.ruleId })
                putExtra(UserWarnActivity.EXTRA_SOURCE, UserWarnActivity.SOURCE_PHYSIO)
            }
            appContext.startActivity(intent)
        }
    }

    private fun sevToPriority(sev: Severity): Int = when (sev) {
        Severity.EMERGENCY -> 1
        Severity.ALERT -> 2
        Severity.WARN -> 2
        Severity.INFO -> 3
    }
}
