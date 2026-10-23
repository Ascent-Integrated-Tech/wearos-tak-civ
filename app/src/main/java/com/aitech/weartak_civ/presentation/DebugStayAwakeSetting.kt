package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.service.DebugStayAwakePreferences
import com.aitech.weartak_civ.service.DebugStayAwakeService

@Composable
fun DebugStayAwakeSetting() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { DebugStayAwakePreferences(context) }
    val enabled by remember(preferences) { preferences.enabledFlow }.collectAsState(initial = preferences.enabled)
    val active by DebugStayAwakeService.active.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) DebugStayAwakeService.restoreIfEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column {
        WearTAKSplitToggleChip(
            title = stringResource(R.string.debug_stay_awake),
            label = stringResource(
                if (!enabled) R.string.companion_status_off
                else if (active) R.string.debug_stay_awake_running
                else R.string.debug_stay_awake_waiting
            ),
            checked = enabled,
            onCheckedChange = { DebugStayAwakeService.setEnabled(context, it) },
            onClick = { showDetails = !showDetails }
        )
        if (showDetails) {
            Text(stringResource(R.string.debug_stay_awake_details), modifier = Modifier.padding(8.dp))
        }
        if (enabled) Text(stringResource(R.string.debug_stay_awake_warning))
    }
}
