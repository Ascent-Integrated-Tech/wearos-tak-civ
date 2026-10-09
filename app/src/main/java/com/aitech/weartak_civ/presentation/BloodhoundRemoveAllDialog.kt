package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R

@Composable
internal fun BloodhoundRemoveAllDialog(
    shouldShowDialog: MutableState<Boolean>,
    onRemoveAll: () -> Unit,
    onCancel: () -> Unit,
    enabled: Boolean = true
) {
    WearTAKAlertDialog(
        shouldShowDialog = shouldShowDialog,
        modifier = Modifier.fillMaxSize(),
        confirmButton = {
            Button(
                onClick = onRemoveAll,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.bloodhound_remove_all))
            }
        },
        dismissButton = {
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
