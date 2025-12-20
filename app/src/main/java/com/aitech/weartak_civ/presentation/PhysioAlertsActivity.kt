/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.presentation

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.HIGH_EXERTION_ALERT
import com.aitech.weartak_civ.HIGH_HEART_RATE_ALERT
import com.aitech.weartak_civ.LOW_HEART_RATE_ALERT
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.complication.PhysioAlertComplicationService
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.ChatActivity.Companion.TAG
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalAlertsSettingState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalServicesSettingsState
import com.aitech.weartak_civ.presentation.PhysioAlertsActivity.Companion.alertListPreview
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid

class PhysioAlertsActivity : ComponentActivity() {
    companion object {
        val TAG = "WearTAK-" + PhysioAlertsActivity::class.java.simpleName
        lateinit var dataRepository: DataRepository
        val alertListPreview = listOf(
            Utils.PhysioAlert("HIGH Heart Rate", Utils.AlertChipState.Emergency, 1756324232, ""),
            Utils.PhysioAlert("LOW Heart Rate", Utils.AlertChipState.Emergency, 1756325084, ""),
            Utils.PhysioAlert("HIGH Exertion", Utils.AlertChipState.Warning, 1756325076, ""))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d(TAG, "Physio Oncreate Called")
        dataRepository = DataRepository(applicationContext)

        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    PhysioAlertsApp(exitActivity = { finishAndRemoveTask() }, context)
                }
            }
        }
    }
}


@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun PhysioAlertsApp(exitActivity: () -> Unit, context: Context) {
    val navController = rememberSwipeDismissableNavController()
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")
//    val chatEnabledLive by enableChatServiceSettingsState.observeAsState()
    val enablePhysioService by enablePhysiologicalServicesSettingsState.observeAsState()
    val enablePhysioAlerts by enablePhysiologicalAlertsSettingState.observeAsState()

    //TOOD: Use when alerts are added to this
    val alerts by dataRepository.physioAlertsFlow.collectAsState(initial = emptyList())


    LaunchedEffect(licenseKey) {
//        Log.d(TAG, "Launch chatEnabledLive = $chatEnabledLive")
        val currentDestination = navController.currentBackStackEntry?.destination?.route
        val isValid = isLicenseValid()

        when {
            !isValid && currentDestination != "invalid_license_screen" -> {
                context.startActivity(Intent(context, SettingsActivity::class.java))
                exitActivity()
            }
            isValid && enablePhysioService == true && enablePhysioAlerts == true && currentDestination != "alerts_screen" -> {
                navController.navigate("alerts_screen") {
                    popUpTo(0) { inclusive = true } // clears the back stack
                }
            }
            isValid && enablePhysioService == false || enablePhysioAlerts == false && currentDestination != "alerts_disabled_screen" -> {
                navController.navigate("alerts_disabled_screen") {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination =
            if (isLicenseValid()) {
                if (enablePhysioService == true && enablePhysioAlerts == true) "alerts_screen" else "alerts_disabled_screen"
            } else {
                "invalid_license_screen"
            }
    ) {
        composable("alerts_screen") {
            AlertsScreen(navController, exitActivity, alerts)

        }

        composable("alerts_disabled_screen") {
//            AlertsDisabledScreen(navController, exitActivity)
            ActivityDisabledScreen(
                navController,
                exitActivity,
                "Alerts Disabled",
                "Please enable alerts in order to use this feature"
            )
        }

        composable("invalid_license_screen") {
            context.startActivity(Intent(context, SettingsActivity::class.java))
            exitActivity()
        }
    }
}

fun List<Utils.PhysioAlert>.alertChipState(isEnabled: Boolean?): Utils.AlertChipState = when {
    isEmpty() || isEnabled == false -> Utils.AlertChipState.None
    any { it.level == Utils.AlertChipState.Emergency && isEnabled == true } -> Utils.AlertChipState.Emergency
    all { it.level == Utils.AlertChipState.Warning && isEnabled == true} -> Utils.AlertChipState.Warning
    else -> Utils.AlertChipState.None // e.g., a mix of None + Warning is NOT “only Warning”
}

@SuppressLint("CoroutineCreationDuringComposition", "UnrememberedMutableState")
@Composable
fun AlertsScreen(
    navController: NavController,
    exitActivity: () -> Unit,
    alerts: List<Utils.PhysioAlert> // <-- Use Utils.ChatRoom here
) {
    Log.d(TAG, "TakServerSetting called")
    val context = LocalContext.current
    WearTakCivScreenDimensions(
        columnContent = {
            LazyColumn(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.height(300.dp)) {
               item() {
                   ExpandableCard()
               }
                itemsIndexed(alerts) { index, alert ->
                    // room.messageList is the List<ChatMessage>

                    Chip(
                        onClick = { /* no-op */ },
                        enabled = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ChipDefaults.outlinedChipColors(),
                        icon = {
                            Icon(
                                painter = if (alert.alert == HIGH_HEART_RATE_ALERT) painterResource(R.drawable.high_hr_icon)
                                else if (alert.alert == LOW_HEART_RATE_ALERT) painterResource(R.drawable.low_hr_icon)
                                else if (alert.alert == HIGH_EXERTION_ALERT) painterResource(R.drawable.high_exertion_icon)
                                else painterResource(R.drawable.heart_rate),
                                contentDescription = "Favorite icon",
                                tint = if (alert.level == Utils.AlertChipState.Emergency)Color.Red
                                else if (alert.level == Utils.AlertChipState.Warning) Color.Yellow
                                else Color.Unspecified,
                                modifier = Modifier.size(30.dp),
                            )
                        },
                        label = {
                            Text(
                                text = alert.alert,
                                fontSize = 15.sp,
                                textAlign = TextAlign.Start,
                                fontWeight = FontWeight.Medium,
                                color = if (alert.level == Utils.AlertChipState.Emergency)Color.Red
                                else if (alert.level == Utils.AlertChipState.Warning) Color.Yellow
                                else Color.Unspecified,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = "${utcToHoursMinutes(alert.time)} ${utcToDate(alert.time)}",
                                fontSize = 13.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        },
        rowContent = {
            BackButton(onClick = { exitActivity(); context.startActivity(Intent(context, MainActivity::class.java)) })

        },
        text = ""
    )
}

@Composable
fun ExpandableCard() {
    var expanded by remember { mutableStateOf(false) }
    val heartRateFlow = dataRepository.latestHeartRate.collectAsState()
    val exertionFlow = dataRepository.latestExertion.collectAsState()
    val dailyMetricsFlow = dataRepository.latestDailyMetrics.collectAsState()
    val skinTempFlow = dataRepository.latestSkinTemp.collectAsState()
    val deviceWorn= dataRepository.latestIsDeviceWorn.collectAsState()


    Card(
//        shape = RoundedCornerShape(8.dp),
        onClick = { expanded = !expanded },
    ) {
        if (expanded) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.current_metrics))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    tint = Color.White,
                    contentDescription = "",
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column() {
                    MetricItem(
                        painterResource(id = R.drawable.heart_rate),
                        stringResource(R.string.heart_rate),
                        if (deviceWorn.value) "${heartRateFlow.value}" + stringResource(R.string.bpm) else "---"
                    )
                    MetricItem(
                        painterResource(id = R.drawable.steps),
                        stringResource(R.string.steps),
                        if (dailyMetricsFlow.value?.steps == null) "0" else "${dailyMetricsFlow.value?.steps}"
                    )
                    MetricItem(
                        painterResource(id = R.drawable.skin_temp),
                        stringResource(R.string.skin_temp),
                        if (deviceWorn.value) "${skinTempFlow.value?.formatDecimal(1)}°" else "---"
                    )
                }
                Spacer(modifier = Modifier.width(7.dp))
                Column() {
                    MetricItem(
                        painterResource(id = R.drawable.exertion),
                        stringResource(R.string.exertion),
                        if (deviceWorn.value) "${exertionFlow.value.toFloat().formatDecimal(2)}%" else "---"
                    )
                    MetricItem(
                        painterResource(id = R.drawable.floors),
                        stringResource(R.string.floors),
                        if (dailyMetricsFlow.value?.floors == null) "0" else "${dailyMetricsFlow.value?.floors?.toInt()}"
                    )
                    MetricItem(
                        painterResource(id = R.drawable.calories),
                        stringResource(R.string.calories),
                        if (dailyMetricsFlow.value?.calories == null) "0" else "${dailyMetricsFlow.value?.calories?.toInt()}"
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.current_metrics))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    tint = Color.White,
                    contentDescription = "",
                )
            }
            Row(modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                Icon(
                    painter = painterResource(id = R.drawable.heart_rate),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(23.dp),
                    contentDescription = "",
                )
                Text(
                    text = if (deviceWorn.value) "${heartRateFlow.value}" + stringResource(R.string.bpm) else "---",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(start = 5.dp)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Icon(
                    painter = painterResource(id = R.drawable.exertion),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(23.dp),
                    contentDescription = "",
                )
                Text(
                    text = if (deviceWorn.value) "${exertionFlow.value.toFloat().formatDecimal(2)}%" else "---",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(start = 5.dp)
                )
            }
        }
    }
}

fun Float.formatDecimal(numberOfDecimals: Int = 2): String = "%.${numberOfDecimals}f".format(this)

@Composable
fun MetricItem(
    icon: Painter,
    metricTitle: String,
    metricVal: String
) {
    Row(
        modifier = Modifier.padding(bottom = 6.dp)
    ) {
        Icon(
            painter = icon,
            tint = Color.Unspecified,
            modifier = Modifier.size(23.dp),
            contentDescription = "",
        )
        Column(
            modifier = Modifier.padding(start = 5.dp)
        ) {
            Text(
                text = metricTitle,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
            )
            Text(
                text = metricVal,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                fontSize = 15.sp)
        }
    }
}


fun requestPhysioComplicationUpdate(context: Context) {
    val componentName = ComponentName(context, PhysioAlertComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}


@Preview(widthDp = 192, heightDp = 400, showBackground = true)
@Composable
fun AlertsScreenPreview() {
    AlertsScreen(rememberSwipeDismissableNavController(), {}, alertListPreview)
}
@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun AlertsDisabledPreview() {
//    AlertsDisabledScreen(rememberSwipeDismissableNavController(), {})
}
@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun MetricItemPreview() {
MetricItem(
    painterResource(id = R.drawable.heart_rate),
    "Heart Rate",
    "100bpm"
    )
}
