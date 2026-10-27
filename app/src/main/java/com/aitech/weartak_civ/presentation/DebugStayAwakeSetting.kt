package com.aitech.weartak_civ.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.service.DebugStayAwakePreferences
import com.aitech.weartak_civ.service.DebugStayAwakeService

@Composable
fun DebugStayAwakeSetting() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { DebugStayAwakePreferences(context) }
    val enabled by remember(preferences) { preferences.enabledFlow }.collectAsState(initial = preferences.enabled)
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) DebugStayAwakeService.restoreIfEnabled(context)
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    WearTAKToggleChip(
        title = stringResource(R.string.debug_stay_awake),
        checkedLive = enabled,
        onCheckedChange = { DebugStayAwakeService.setEnabled(context, it) })
}

@Composable
fun ChargingStayAwakeSetting() {
    val context = LocalContext.current
    val preferences = remember(context) { DebugStayAwakePreferences(context) }
    val enabled by remember(preferences) { preferences.chargingEnabledFlow }
        .collectAsState(initial = preferences.chargingEnabled)
    WearTAKToggleChip(
        title = stringResource(R.string.debug_stay_awake_charging),
        checkedLive = enabled,
        onCheckedChange = { preferences.setChargingEnabled(it) })
}
