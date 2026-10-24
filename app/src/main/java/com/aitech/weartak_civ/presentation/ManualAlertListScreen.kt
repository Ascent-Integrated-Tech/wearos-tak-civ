package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.cot.ManualAlertTypes
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import kotlinx.coroutines.launch

internal data class PresetRow(val text: String, val custom: Boolean)

internal fun sortedPresetRows(builtIns: List<String>, saved: List<String>): List<PresetRow> =
    (builtIns.map { PresetRow(it, false) } + saved.map { PresetRow(it, true) })
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.text })

private enum class ManualAlertScreenMode {
    LIST,
    ADD,
    DELETE,
}

@Composable
fun ManualAlertListScreen(
    builtInAlerts: List<String>,
    savedAlerts: List<String>,
    onAlertSelected: (String) -> Unit,
    onAddAlert: suspend (String) -> Boolean,
    onDeleteAlert: suspend (String) -> Unit,
    onCancel: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(ManualAlertScreenMode.LIST) }
    var draft by rememberSaveable { mutableStateOf("") }
    var alertToDelete by rememberSaveable { mutableStateOf("") }
    var showSaveError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val submitAlert: () -> Unit = {
        scope.launch {
            if (onAddAlert(draft)) {
                draft = ""
                showSaveError = false
                mode = ManualAlertScreenMode.LIST
            } else {
                showSaveError = true
            }
        }
        Unit
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (mode) {
            ManualAlertScreenMode.LIST -> {
                item {
                    Text(
                        text = "Manual Alert",
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                }
                item {
                    ManualAlertActionRow(
                        text = stringResource(R.string.route_back),
                        onClick = onCancel,
                    )
                }
                sortedPresetRows(builtInAlerts, savedAlerts).forEach { entry ->
                    val alert = entry.text
                    item(key = "${entry.custom}-$alert") {
                        ManualAlertActionRow(
                            text = alert,
                            onClick = { onAlertSelected(alert) },
                            onDelete = if (entry.custom) ({
                                alertToDelete = alert
                                mode = ManualAlertScreenMode.DELETE
                            }) else null,
                            allowLongLabel = entry.custom,
                        )
                    }
                }
                item {
                    ManualAlertActionRow(
                        text = "+",
                        contentDescription = stringResource(R.string.manual_alert_add_hint),
                        onClick = {
                            showSaveError = false
                            mode = ManualAlertScreenMode.ADD
                        },
                    )
                }
            }

            ManualAlertScreenMode.ADD -> {
                item {
                    Text(
                        text = "Add Custom Alert",
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                }
                item {
                    TextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            showSaveError = false
                        },
                        placeholder = { Text(stringResource(R.string.manual_alert_add_hint), maxLines = 1) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.DarkGray,
                            unfocusedContainerColor = Color.DarkGray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                        ),
                    )
                }
                if (showSaveError) {
                    item {
                        Text(
                            text = "Alert must be unique, 1–60 characters, and the list can hold up to 20.",
                            color = Color.Red,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                item {
                    ManualAlertActionRow(text = "Save alert", onClick = submitAlert)
                }
                item {
                    ManualAlertActionRow(
                        text = stringResource(R.string.route_back),
                        onClick = { mode = ManualAlertScreenMode.LIST },
                    )
                }
            }

            ManualAlertScreenMode.DELETE -> {
                item {
                    Text(
                        text = "Delete saved alert?",
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
                item {
                    Text(
                        text = alertToDelete,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                item {
                    ManualAlertActionRow(
                        text = "Delete",
                        onClick = {
                            scope.launch {
                                onDeleteAlert(alertToDelete)
                                alertToDelete = ""
                                mode = ManualAlertScreenMode.LIST
                            }
                        },
                    )
                }
                item {
                    ManualAlertActionRow(
                        text = stringResource(R.string.route_back),
                        onClick = {
                            alertToDelete = ""
                            mode = ManualAlertScreenMode.LIST
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ManualAlertActionRow(
    text: String,
    onClick: () -> Unit,
    contentDescription: String? = null,
    onDelete: (() -> Unit)? = null,
    allowLongLabel: Boolean = false,
) {
    val rowContentDescription = contentDescription
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.DarkGray)
            .semantics { rowContentDescription?.let { this.contentDescription = it } }
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f)
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val density = LocalDensity.current
            val availableWidthPx = with(density) { maxWidth.roundToPx() }
            val fontSize = remember(text, availableWidthPx, density) {
                chooseAlertFontSize(text, availableWidthPx) { candidate ->
                    measurer.measure(
                        text = text,
                        style = TextStyle(fontSize = candidate.sp, fontWeight = FontWeight.SemiBold),
                        constraints = Constraints(maxWidth = availableWidthPx),
                        maxLines = 1,
                        softWrap = false,
                    ).size.width
                }
            }
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = fontSize.sp,
                maxLines = 1,
                softWrap = false,
                overflow = if (allowLongLabel) TextOverflow.Ellipsis else TextOverflow.Clip,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.preset_delete, text),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

internal fun chooseAlertFontSize(
    text: String,
    availableWidthPx: Int,
    measureText: (fontSizeSp: Int) -> Int,
): Int {
    for (fontSize in 16 downTo 8) {
        if (measureText(fontSize) <= availableWidthPx) return fontSize
    }
    return 8
}

@Preview(widthDp = 192, heightDp = 192, fontScale = 1.3f, showBackground = true)
@Composable
private fun ManualAlertList192Preview() {
    WearTAKCIVTheme {
        ManualAlertListScreen(
            builtInAlerts = ManualAlertTypes.options.map { it.displayName },
            savedAlerts = listOf("Check north perimeter"),
            onAlertSelected = {},
            onAddAlert = { true },
            onDeleteAlert = {},
            onCancel = {},
        )
    }
}

@Preview(widthDp = 227, heightDp = 227, fontScale = 1.3f, showBackground = true)
@Composable
private fun ManualAlertList227Preview() {
    WearTAKCIVTheme {
        ManualAlertListScreen(
            builtInAlerts = ManualAlertTypes.options.map { it.displayName },
            savedAlerts = listOf("Check north perimeter"),
            onAlertSelected = {},
            onAddAlert = { true },
            onDeleteAlert = {},
            onCancel = {},
        )
    }
}