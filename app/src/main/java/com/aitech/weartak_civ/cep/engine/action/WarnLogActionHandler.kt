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
 * Handles CEP rules with action type "warn_log": on main fire, performs both
 * warn-style behavior (vibration, UserWarnActivity) and log-style behavior
 * (AlertRegistry.tryActivate, TAK/physio enqueue). Exactly one registry
 * activation and one addPhysioAndAutoGenAlertRequest per main fire.
 * Pre-warn is dispatched as type "warn" with isPreWarn=true and never reaches this handler.
 */
class WarnLogActionHandler(
    appScope: CoroutineScope
) : BaseCepActionHandler(appScope) {

    override val type: String = "warn_log"

    private val PLACEHOLDER_REGEX = "\\{([a-zA-Z0-9._-]+)\\}".toRegex()

    private fun expandMessage(template: String, snapshot: Map<String, Any?>): String {
        if (!template.contains("{")) return template
        return PLACEHOLDER_REGEX.replace(template) { match ->
            val key = match.groupValues[1]
            snapshot[key]?.toString() ?: "?"
        }
    }

    override suspend fun handle(ctx: TriggerContext) {
        val expandedMessage = expandMessage(ctx.action.message.ifEmpty { "Triggered" }, ctx.snapshot)
        Log.w("CEP", "[CEP][${ctx.action.severity}][${ctx.ruleId}] $expandedMessage (warn_log)")

        if (!ctx.action.isTak) return

        val tak = resolveTakContext(ctx)

        val isFirstActivation = AlertRegistry.tryActivate(
            ruleId         = ctx.ruleId,
            priority       = tak.priority,
            uid            = tak.rawUid,
            exitConditions = tak.exitConditions,
            exitLogic      = tak.exitLogic,
            exitSustainSec = tak.sustainSec
        )
        if (!isFirstActivation) {
            Log.i("CEP", "[AlertRegistry] Suppressing duplicate warn_log for rule=${ctx.ruleId}")
            return
        }

        val uid      = tak.rawUid
        val category = ctx.action.category ?: "CAT_HEALTH_GENERIC"

        //val enableUserWarning = dataRepository.getEnableUserWarning.first()

        /*if (enableUserWarning) {
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
        }*/

        dataRepository.addPhysioAndAutoGenAlertRequest(
            uid         = uid,
            category    = category,
            description = expandedMessage.ifEmpty { ctx.ruleId },
            priority    = tak.priority,
            sendSms     = ctx.action.sendSms,
            timeMillis  = System.currentTimeMillis()
        )
    }

    private fun sevToPriority(sev: Severity): Int = when (sev) {
        Severity.EMERGENCY -> 1
        Severity.ALERT -> 2
        Severity.WARN -> 2
        Severity.INFO -> 3
    }
}
