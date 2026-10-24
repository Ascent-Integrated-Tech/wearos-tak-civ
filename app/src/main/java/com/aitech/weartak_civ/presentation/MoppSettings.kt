package com.aitech.weartak_civ.presentation

import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.*
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.MoppStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
internal fun MoppSettingsPage(
    savedStatus: MoppStatus,
    enabled: Boolean,
    navController: NavHostController,
    onSave: suspend (MoppStatus) -> Unit
) {
    var candidate by rememberSaveable(savedStatus.key) { mutableStateOf(savedStatus.key) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    WearTAKPageWithBackArrow(
        title = stringResource(R.string.mopp_ppe_status),
        navController = navController,
        columnContent = {
            item {
                Icon(painterResource(R.drawable.ic_mopp), contentDescription = null,
                    modifier = Modifier.size(32.dp))
            }
            item { Text(stringResource(R.string.mopp_manual_local)) }
            item { Text(stringResource(R.string.mopp_unset_help)) }
            if (!enabled) {
                item { Text(stringResource(R.string.mopp_enable_to_edit)) }
            }
            items(MoppStatus.entries.size) { index ->
                val status = MoppStatus.entries[index]
                ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = candidate == status.key,
                    enabled = enabled && !saving,
                    onCheckedChange = { candidate = status.key },
                    label = { Text(stringResource(status.label)) },
                    toggleControl = { RadioButton(selected = candidate == status.key, onClick = null) }
                )
            }
            if (saveError) {
                item { Text(stringResource(R.string.mopp_save_error)) }
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled && !saving,
                    label = { Text(stringResource(if (saving) R.string.mopp_saving else R.string.mopp_save)) },
                    onClick = {
                        saving = true
                        saveError = false
                        scope.launch {
                            try {
                                onSave(MoppStatus.fromKey(candidate))
                                navController.popBackStack()
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: IOException) {
                                Log.e("WearTAK-MOPP", "Could not persist manual PPE status", error)
                                saveError = true
                            } finally {
                                saving = false
                            }
                        }
                    }
                )
            }
        }
    )
}
