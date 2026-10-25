package com.aitech.weartak_civ.presentation

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.SelectableChip
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.LocationInput
import com.aitech.weartak_civ.data.LocationInputStatus
import kotlinx.coroutines.launch
import java.io.IOException

internal fun locationInputStatusLabel(status: LocationInputStatus): Int = when (status) {
    LocationInputStatus.OFF -> R.string.location_input_off
    LocationInputStatus.ACQUIRING -> R.string.location_input_acquiring
    LocationInputStatus.READY -> R.string.location_input_ready
    LocationInputStatus.UNAVAILABLE -> R.string.location_input_unavailable
    LocationInputStatus.DISABLED -> R.string.location_input_disabled
    LocationInputStatus.PERMISSION_REQUIRED -> R.string.location_input_permission
    LocationInputStatus.REQUEST_FAILED -> R.string.location_input_failed
}

@Composable
internal fun LocationInputSettingsPage(
    selected: LocationInput?,
    status: LocationInputStatus,
    navController: NavHostController,
    onSelect: suspend (LocationInput) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    WearTAKPageWithBackArrow(
        title = stringResource(R.string.location_input),
        navController = navController,
        columnContent = {
            if (selected == LocationInput.NETWORK) {
                item { Text(stringResource(R.string.location_input_legacy)) }
            }
            if (status != LocationInputStatus.READY) {
                item { Text(stringResource(locationInputStatusLabel(status))) }
            }
            if (status in setOf(LocationInputStatus.UNAVAILABLE, LocationInputStatus.DISABLED,
                    LocationInputStatus.PERMISSION_REQUIRED, LocationInputStatus.REQUEST_FAILED)) {
                item { Text(stringResource(R.string.location_input_help)) }
            }
            items(LocationInput.selectable.size) { index ->
                val input = LocationInput.selectable[index]
                SelectableChip(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    selected = selected == input,
                    enabled = !saving,
                    label = { Text(stringResource(input.label)) },
                    onClick = {
                        saving = true
                        failed = false
                        scope.launch {
                            try {
                                if (onSelect(input)) navController.popBackStack() else failed = true
                            } catch (error: IOException) {
                                Log.e("WearTAK-LocationInput", "Could not save selected provider", error)
                                failed = true
                            } finally {
                                saving = false
                            }
                        }
                    }
                )
            }
            if (failed) item { Text(stringResource(R.string.location_input_save_error)) }
        }
    )
}
