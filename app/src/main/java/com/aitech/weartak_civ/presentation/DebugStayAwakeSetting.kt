package com.aitech.weartak_civ.presentation

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Checkbox
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.service.DebugStayAwakePreferences
import com.aitech.weartak_civ.service.DebugStayAwakeService

@Composable
fun DebugStayAwakeSetting() {
    val context = LocalContext.current
    val active by DebugStayAwakeService.active.collectAsState()
    val preferences = remember(context) { DebugStayAwakePreferences(context) }
    var resumeAfterUpdate by remember { mutableStateOf(preferences.resumeAfterUpdate) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colors.surface, RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).toggleable(
                    value = active,
                    role = Role.Switch,
                    onValueChange = { enabled ->
                        val intent = Intent(context, DebugStayAwakeService::class.java)
                        if (enabled) {
                            try {
                                ContextCompat.startForegroundService(context, intent)
                            } catch (error: SecurityException) {
                                Log.e("WearTAK-DebugStayAwake", "Cannot start debug service", error)
                                Toast.makeText(context, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
                            }
                        } else {
                            DebugStayAwakeService.stop(context)
                        }
                    }
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.debug_stay_awake), fontSize = 11.sp)
                Switch(checked = active, onCheckedChange = null)
            }
            Column(
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).toggleable(
                    value = resumeAfterUpdate,
                    role = Role.Checkbox,
                    onValueChange = {
                        preferences.resumeAfterUpdate = it
                        resumeAfterUpdate = it
                    }
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.debug_stay_awake_resume_update),
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )
                Checkbox(checked = resumeAfterUpdate, onCheckedChange = null)
            }
        }
        if (active) {
            Text(stringResource(R.string.debug_stay_awake_warning))
        }
    }
}
