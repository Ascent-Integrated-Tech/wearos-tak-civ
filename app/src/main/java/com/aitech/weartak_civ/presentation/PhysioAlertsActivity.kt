/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.presentation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.*
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.complication.PhysioStatusAndAlertingComplicationService
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.TopDisplayMetric
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.presentation.viewmodel.WearTAKSettingsViewModel
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid

class PhysioAlertsActivity : ComponentActivity() {
    companion object {
        val TAG = "WearTAK-PhysioAlertsActivity"
        lateinit var dataRepository: DataRepository
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WearTAKCIVTheme {
                val settingsVm: WearTAKSettingsViewModel = viewModel()
                PhysioAlertsApp({ finish() }, this, settingsVm)
            }
        }
    }
}

@Composable
fun PhysioAlertsApp(exitActivity: () -> Unit, context: Context, settingsVm: WearTAKSettingsViewModel) {
    val repo = MainActivity.dataRepository
    val license by repo.getLicense.collectAsState(initial = "")
    val navController = rememberSwipeDismissableNavController()
    val selected by settingsVm.savedSelectedPhysioMetric.collectAsState()
    val monitoring by settingsVm.savedEnablePhysiologicalServices.collectAsState()
    val alertsEnabled by settingsVm.savedEnablePhysiologicalAlerts.collectAsState()
    val alerts by repo.physioAndAutoGenAlertList.collectAsState()
    LaunchedEffect(license) {
        if (!isLicenseValid()) {
            context.startActivity(Intent(context, WearTAKSettingsActivity::class.java))
            exitActivity()
        }
    }
    val choices = topMetricChoices(context)
    WearTAKPageWithBackArrow(
        title = stringResource(R.string.top_display_metric),
        navController = navController,
        exitActivity = true,
        exitFunction = {
            requestPhysioStatusAndAlertingComplicationUpdate(context)
            exitActivity()
        },
        columnContent = {
            items(choices.size) { index ->
                val (metric, label) = choices[index]
                TopMetricChoice(label, topMetricValue(metric, repo),
                    selected == metric.key) { settingsVm.storeSelectedPhysioMetric(metric.key) }
            }
            item {
                MetricAlertControls(alertsEnabled, settingsVm::storeEnablePhysiologicalAlerts) {
                    context.startActivity(settingsShortcutIntent(context, "physiological_alerts"))
                }
            }
            item {
                WearTAKToggleChip(
                    checkedLive = monitoring,
                    onCheckedChange = settingsVm::storeEnablePhysiologicalServices,
                    title = stringResource(R.string.physiological_monitoring))
            }
            items(alerts.size) { index ->
                val alert = alerts[index]
                if (alert.alertState == DataRepository.AlertState.ALERT) {
                    val color = when (alert.priority) {
                        1 -> Color.Red
                        2 -> Color.Yellow
                        else -> Color.Unspecified
                    }
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {},
                        colors = ChipDefaults.outlinedChipColors(),
                        label = { Text(alertCategoryFromString(alert.category), color = color) },
                        secondaryLabel = {
                            Column {
                                Text(alert.description)
                                Text("${utcToHoursMinutes(alert.timeMillis)} ${utcToDate(alert.timeMillis)}")
                            }
                        })
                }
            }
        })
}

@Composable
internal fun TopMetricChoice(label: String, value: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.body1)
            Text(value, style = MaterialTheme.typography.caption1)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
internal fun MetricAlertControls(enabled: Boolean, onToggle: (Boolean) -> Unit, onThresholds: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.metric_alerts), modifier = Modifier.weight(1f))
        val label = stringResource(R.string.metric_alerts)
        Switch(checked = enabled, onCheckedChange = onToggle,
            modifier = Modifier.size(48.dp).semantics { contentDescription = label })
        IconButton(onClick = onThresholds, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.metric_alert_thresholds))
        }
    }
}

fun List<DataRepository.CustomEmergencyAlert>.alertChipState(isEnabled: Boolean?): Utils.AlertChipState = when {
    isEmpty() || isEnabled == false -> Utils.AlertChipState.None
    any { it.alertState == DataRepository.AlertState.ALERT && it.priority == 1 && isEnabled == true } -> Utils.AlertChipState.Emergency
    all { (it.alertState == DataRepository.AlertState.ALERT && it.priority == 2 || it.priority == 3) && isEnabled == true } -> Utils.AlertChipState.Warning
    else -> Utils.AlertChipState.None
}

fun alertCategoryFromString(category: String): String = when (category) {
    "CAT_COLD_FROSTBITE_OVERRIDE" -> "Cold Override: Frostbite Risk"
    "CAT_COLD_TACTILE_OVERRIDE" -> "Cold Override: Tactile Failure"
    "CAT_COLD_SKIN20_OVERRIDE" -> "Cold Override: Severe Peripheral Vasoconstriction"
    "CAT_COLD_DEXTERITY" -> "Cold Stress: Dexterity Impairment"
    "CAT_COLD_WARN", "CAT_COLD_ALERT", "CAT_COLD_EMERGENCY" -> "Cold Stress"
    "CAT_HR_HIGH" -> "High Resting HR"
    "CAT_HR_EMERGENCY", "CAT_HR_ALERT" -> "High Exertion"
    "CAT_HR_LOW", "CAT_HR_REST_LOW" -> "Low Resting HR"
    "CAT_IMMERSION_WARN" -> "Immersion Warning"
    "CAT_IMMERSION_ALERT" -> "Immersion Alert"
    "CAT_SPO2_WARN", "CAT_SPO2_ALERT" -> "Low SpO2"
    "CAT_SPO2_EMERGENCY" -> "Severe Hypoxemia"
    "CAT_LOW_PRESSURE_ALERT" -> "Low Pressure Alert"
    "CAT_HIGH_PRESSURE_ALERT" -> "High Pressure Alert"
    else -> "ERROR"
}

fun Float.formatDecimal(numberOfDecimals: Int = 2): String = "%.${numberOfDecimals}f".format(this)
fun Double.formatDecimal(numberOfDecimals: Int = 2): String = "%.${numberOfDecimals}f".format(this)

fun requestPhysioStatusAndAlertingComplicationUpdate(context: Context) {
    ComplicationDataSourceUpdateRequester.create(context,
        ComponentName(context, PhysioStatusAndAlertingComplicationService::class.java)).requestUpdateAll()
}
