package com.aitech.weartak_civ.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.command.ChatCommandAuthorizer

@Composable
internal fun CommandCallsignEntryPage(
    navController: NavController,
    callsigns: List<String>,
    onAdd: (List<String>) -> Unit
) {
    val context = LocalContext.current
    WearTAKStringEntryPage(
        navController = navController,
        item = "",
        label = stringResource(R.string.callsign),
        validate = { input ->
            val callsign = ChatCommandAuthorizer.validatedCallsign(input)
            when {
                callsign == null -> context.getString(R.string.invalid_chat_command_callsign)
                callsigns.any { ChatCommandAuthorizer.normalize(it) == ChatCommandAuthorizer.normalize(callsign) } ->
                    context.getString(R.string.duplicate_chat_command_callsign)
                else -> null
            }
        },
        confirmFunction = { input ->
            val callsign = requireNotNull(ChatCommandAuthorizer.validatedCallsign(input))
            onAdd(ChatCommandAuthorizer.sortedAllowList(callsigns + callsign))
        },
        confirmOnImeDone = true
    )
}
