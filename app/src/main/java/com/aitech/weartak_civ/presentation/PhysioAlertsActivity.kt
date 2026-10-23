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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.complication.PhysioStatusAndAlertingComplicationService
import com.aitech.weartak_civ.data.CrmDataRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.physioMetricIconComplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.physioMetricStringComplication
import com.aitech.weartak_civ.presentation.PhysioAlertsActivity.Companion.TAG
import com.aitech.weartak_civ.presentation.PhysioAlertsActivity.Companion.alertListPreview
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.aitech.weartak_civ.presentation.CrmReserveActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBetaFeatures
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalAlerts
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalServices
import com.aitech.weartak_civ.presentation.MainActivity.Constants.selectedPhysioMetric
import com.aitech.weartak_civ.presentation.viewmodel.CrmViewModel
import com.aitech.weartak_civ.presentation.viewmodel.CrmViewModelFactory
import com.aitech.weartak_civ.presentation.viewmodel.WearTAKSettingsViewModel

class PhysioAlertsActivity : ComponentActivity() {
    companion object {
        val TAG = "WearTAK-" + PhysioAlertsActivity::class.java.simpleName
        lateinit var dataRepository: DataRepository
        val alertListPreview = listOf(
            DataRepository.CustomEmergencyAlert(uuid="1c6c1dc9-3b6c-466c-b11a-551aa4b142aa", alertState= DataRepository.AlertState.ALERT, category="CAT_HR_ALERT", description="HR ZONE 1 — between 50% and 60% HRR", priority=2, sendSms=false, timeMillis=1771434132637, enqueuedToBeSent=true),
            DataRepository.CustomEmergencyAlert(uuid="e5203b07-b64e-4e2d-acf1-6a8033c2008c", alertState=DataRepository.AlertState.ALERT, category="CAT_HR_REST_LOW", description="LOW HR — below 40 bpm", priority=1, sendSms=false, timeMillis=1771440940184, enqueuedToBeSent=true)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d(TAG, "Physio Oncreate Called")
        dataRepository = DataRepository(applicationContext)
//        selectedPhysioMetricSettingsState.postValue(selectedPhysioMetric)
        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    val settingsVm: WearTAKSettingsViewModel = viewModel ()
                    PhysioAlertsApp(exitActivity = { finishAndRemoveTask() }, context, settingsVm)
                }
            }
        }
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun PhysioAlertsApp(exitActivity: () -> Unit, context: Context, settingsVm: WearTAKSettingsViewModel) {
    val navController = rememberSwipeDismissableNavController()
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")
//    val chatEnabledLive by enableChatServiceSettingsState.observeAsState()

    //TOOD: Use when alerts are added to this
//    val alerts by dataRepository.physioAlertsFlow.collectAsState(initial = emptyList())
    val alerts by dataRepository.physioAndAutoGenAlertList.collectAsState(initial = emptyList())

    LaunchedEffect(licenseKey) {
//        Log.d(TAG, "Launch chatEnabledLive = $chatEnabledLive")
        val currentDestination = navController.currentBackStackEntry?.destination?.route
        val isValid = isLicenseValid()

        when {
            !isValid && currentDestination != "invalid_license_screen" -> {
                context.startActivity(Intent(context, WearTAKSettingsActivity::class.java))
                exitActivity()
            }
            isValid && enablePhysiologicalServices == true && currentDestination != "alerts_screen" -> {
                navController.navigate("alerts_screen") {
                    popUpTo(0) { inclusive = true } // clears the back stack
                }
            }
            isValid && enablePhysiologicalServices == false && currentDestination != "alerts_disabled_screen" -> {
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
                if (enablePhysiologicalServices == true) "alerts_screen" else "alerts_disabled_screen"
//                if (enablePhysioService == true && enablePhysioAlerts == true) "alerts_screen" else "alerts_disabled_screen"
            } else {
                "invalid_license_screen"
            }
    ) {
        composable("alerts_screen") {
            AlertsScreen(
                navController = navController,
                exitActivity = exitActivity,
                alertsEnabled = enablePhysiologicalAlerts,
                alerts = alerts,
                selectedPhysioMetric,
                settingsVm
            )
        }

        composable("alerts_disabled_screen") {
//            AlertsDisabledScreen(navController, exitActivity)
            ActivityDisabledScreen(
                navController,
                exitActivity,
                "Physio Monitoring Disabled",
                "Please enable physio monitoring in order to use this feature"
            )
        }

        composable("invalid_license_screen") {
            context.startActivity(Intent(context, WearTAKSettingsActivity::class.java))
            exitActivity()
        }
    }
}

fun List<DataRepository.CustomEmergencyAlert>.alertChipState(isEnabled: Boolean?): Utils.AlertChipState = when {
    isEmpty() || isEnabled == false -> Utils.AlertChipState.None
    any { it.alertState == DataRepository.AlertState.ALERT && it.priority == 1 && isEnabled == true } -> Utils.AlertChipState.Emergency
    all { (it.alertState == DataRepository.AlertState.ALERT && it.priority == 2 || it.priority == 3) && isEnabled == true} -> Utils.AlertChipState.Warning
    else -> Utils.AlertChipState.None // e.g., a mix of None + Warning is NOT “only Warning”
}

@Composable
fun alertIconFromString(category: String): Painter {
    val painter: Painter = when (category) {
        "CAT_COLD_FROSTBITE_OVERRIDE" -> painterResource(R.drawable.heart_rate)
        "CAT_COLD_TACTILE_OVERRIDE" -> painterResource(R.drawable.heart_rate)
        "CAT_COLD_SKIN20_OVERRIDE" -> painterResource(R.drawable.heart_rate)
        "CAT_COLD_DEXTERITY" -> painterResource(R.drawable.heart_rate)
        "CAT_COLD_WARN" -> painterResource(R.drawable.heart_rate)
        "CAT_COLD_ALERT" -> painterResource(R.drawable.heart_rate)
        "CAT_COLD_EMERGENCY" -> painterResource(R.drawable.heart_rate)
        "CAT_HR_HIGH" -> painterResource(R.drawable.high_heart_rate_alert)
        "CAT_HR_EMERGENCY" -> painterResource(R.drawable.high_exertion_alert)
        "CAT_HR_LOW" -> painterResource(R.drawable.low_hr_alert)
        "CAT_HR_REST_LOW" -> painterResource(R.drawable.low_hr_alert)
        "CAT_HR_ALERT" -> painterResource(R.drawable.high_exertion_alert)
        "CAT_IMMERSION_WARN" -> painterResource(R.drawable.immersion_icon)
        "CAT_IMMERSION_ALERT" -> painterResource(R.drawable.immersion_icon)
        "CAT_LOW_PRESSURE_ALERT" -> painterResource(R.drawable.pressure_icon)
        "CAT_HIGH_PRESSURE_ALERT" -> painterResource(R.drawable.pressure_icon)


        else ->  //TODO: Change to error icon
            painterResource(R.drawable.heart_rate)
    }
    return painter
}

fun alertCategoryFromString(category: String): String {
    val categoryString: String = when (category) {
        "CAT_COLD_FROSTBITE_OVERRIDE" -> "Cold Override: Frostbite Risk"
        "CAT_COLD_TACTILE_OVERRIDE" -> "Cold Override: Tactile Failure"
        "CAT_COLD_SKIN20_OVERRIDE" -> "Cold Override: Severe Peripheral Vasoconstriction"
        "CAT_COLD_DEXTERITY" -> "Cold Stress: Dexterity Impairment"
        "CAT_COLD_WARN" -> "Cold Stress"
        "CAT_COLD_ALERT" -> "Cold Stress"
        "CAT_COLD_EMERGENCY" -> "Cold Stress"
        "CAT_HR_HIGH" -> "High Resting HR"
        "CAT_HR_EMERGENCY" -> "High Exertion"
        "CAT_HR_LOW" -> "Low Resting HR"
        "CAT_HR_REST_LOW" -> "Low Resting HR"
        "CAT_HR_ALERT" -> "High Exertion"
        "CAT_IMMERSION_WARN" -> "Immersion Warning"
        "CAT_IMMERSION_ALERT" -> "Immersion Alert"
        "CAT_SPO2_WARN" -> "Low SpO2"
        "CAT_SPO2_ALERT" -> "Low SpO2"
        "CAT_SPO2_EMERGENCY" -> "Severe Hypoxemia"
        "CAT_LOW_PRESSURE_ALERT" -> "Low Pressure Alert"
        "CAT_HIGH_PRESSURE_ALERT" -> "High Pressure Alert"
        else ->
            "ERROR"
    }
    return categoryString
}

@SuppressLint("CoroutineCreationDuringComposition", "UnrememberedMutableState")
@Composable
fun AlertsScreen(
    navController: NavController,
    exitActivity: () -> Unit,
    alertsEnabled: Boolean?,
    alerts: List<DataRepository.CustomEmergencyAlert>, // <-- Use Utils.ChatRoom here,
    selectedPhysioMetric: String,
    settingsVm: WearTAKSettingsViewModel
) {
    Log.d(TAG, "AlertsScreen called")
    Log.d(TAG, "$alerts")
    val context = LocalContext.current
    WearTAKPageWithBackArrow(
        title = if (alertsEnabled == true) "Alerts: ON" else "Alerts: OFF",
        columnContent = {
            item {
                LazyColumn(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.height(300.dp)
                ) {
                    item() {
                        ExpandableCard(
                            navController = navController,
                            settingsVm,
                            selectedPhysioMetric,
                        )
                    }
                    itemsIndexed(alerts) { index, alert ->
                        // room.messageList is the List<ChatMessage>
                        if (alert.alertState == DataRepository.AlertState.ALERT) {
                            Chip(
                                onClick = { /* no-op */ },
                                enabled = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ChipDefaults.outlinedChipColors(),
                                icon = {
                                    Icon(
                                        painter = alertIconFromString(alert.category),
                                        contentDescription = "Favorite icon",
                                        tint = when (alert.priority) {
                                            1 -> Color.Red
                                            2 -> Color.Yellow
                                            else -> Color.Unspecified
                                        },
                                        modifier = Modifier.size(30.dp),
                                    )
                                },
                                label = {
                                    Text(
                                        text = alertCategoryFromString(alert.category) /* + "${alert.alertState}" */,
                                        fontSize = 15.sp,
                                        textAlign = TextAlign.Start,
                                        fontWeight = FontWeight.Medium,
                                        color = when (alert.priority) {
                                            1 -> Color.Red
                                            2 -> Color.Yellow
                                            else -> Color.Unspecified
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                },
                                secondaryLabel = {
                                    Column {
                                        Text(
                                            text = alert.description,
                                            fontSize = 12.sp,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            text = "${utcToHoursMinutes(alert.timeMillis)} ${
                                                utcToDate(
                                                    alert.timeMillis
                                                )
                                            }",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Normal,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(10.dp))
            }
        },
        navController = navController,
        exitActivity = true,
        exitFunction = {
            exitActivity(); context.startActivity(Intent(context, MainActivity::class.java))
//            if (selectedPhysioMetric != selectedPhysioMetricSettingsState.value) {
//                selectedPhysioMetric = selectedPhysioMetricSettingsState.value ?: selectedPhysioMetric
                CoroutineScope(Dispatchers.Main).launch {
                    Log.d(TAG, "Storing selectedPhysioMetric ($selectedPhysioMetric)")
//                    settingsVm.storeSelectedPhysioMetric(selectedPhysioMetric)
                    requestPhysioStatusAndAlertingComplicationUpdate(context)
                    val logOutput = """
                    === === Physiological User Metrics === ===
                    ${String.format("%-25s %-30s", "Icon:", physioMetricIconComplication)}
                    ${String.format("%-25s %-30s", "Metric:", physioMetricStringComplication)}
                    """.trimIndent()
                    Log.i(TAG, logOutput)
                }
        }
    )
}

@Composable
fun ExpandableCard(
    navController: NavController,
    settingsVm: WearTAKSettingsViewModel,
    selectedPhysioMetric: String) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val app = context.applicationContext as WearTakApplication

    val crmViewModel: CrmViewModel = viewModel(
        factory = CrmViewModelFactory(
            crmDataRepository = app.crmRepo,
            dataRepository = app.repo,
        )
    )

    val heartRateFlow = dataRepository.latestHeartRate.collectAsState()
    val exertionFlow = dataRepository.latestExertion.collectAsState()
    val dailyMetricsFlow = dataRepository.latestDailyMetrics.collectAsState()
    val skinTempFlow = dataRepository.latestSkinTemp.collectAsState()
    val deviceWorn= dataRepository.latestIsDeviceWorn.collectAsState()
    val latestSpo2 = dataRepository.latestSpo2.collectAsState()
    val latestSpo2TierEvent = dataRepository.latestSpo2TierEvent.collectAsState()
    val latestSpo2Debug = dataRepository.latestSpo2DebugState.collectAsState()
    val crmUiState by crmViewModel.uiState.collectAsStateWithLifecycle()
    val crmEnabled by dataRepository.getEnableCrmService.collectAsState(initial = MainActivity.enableCrmService)
    
    val crmScoreText = if (deviceWorn.value && crmUiState.hasValidScore) {
        "${crmUiState.score}"
    } else {
        "---"
    }

    val openCrmReserveScreen = {
        Log.d(TAG, "Opening CRM Reserve screen")
        context.startActivity(Intent(context, CrmReserveActivity::class.java))
    }

    val logOutput = """
                    === === Physiological User Metrics === ===
                    ${String.format("%-25s %-30s", "heartRateFlow:", heartRateFlow.value)}
                    ${String.format("%-25s %-30s", "steps:", dailyMetricsFlow.value?.steps)}
                    ${String.format("%-25s %-30s", "skinTemp:", skinTempFlow.value?.formatDecimal(1))}
                    ${String.format("%-25s %-30s", "exertion:", exertionFlow.value.toFloat().formatDecimal(2))}
                    ${String.format("%-25s %-30s", "floors:", dailyMetricsFlow.value?.floors?.toInt())}
                    ${String.format("%-25s %-30s", "calories:", dailyMetricsFlow.value?.calories?.toInt())}

                    """.trimIndent()
    Log.i(TAG, logOutput)



    val activePhysioMetrics = buildList {
        add(Utils.SelectedPhysioMetric("key_heart_rate", painterResource(id = R.drawable.heart_rate), stringResource(R.string.heart_rate), if (deviceWorn.value && heartRateFlow.value != null) "${heartRateFlow.value}" else "---"))
        add(Utils.SelectedPhysioMetric("key_steps", painterResource(id = R.drawable.steps), stringResource(R.string.steps), dailyMetricsFlow.value?.steps?.toString() ?: "0"))
        add(Utils.SelectedPhysioMetric("key_skin_temp", painterResource(id = R.drawable.skin_temp), stringResource(R.string.skin_temp), if (deviceWorn.value && skinTempFlow.value != null) "${skinTempFlow.value?.formatDecimal(1)}°" else "---"))
        if (crmEnabled) add(Utils.SelectedPhysioMetric("key_crm", painterResource(id = R.drawable.heart_rate), "CRM", crmScoreText))
        add(Utils.SelectedPhysioMetric("key_exertion", painterResource(id = R.drawable.exertion), stringResource(R.string.exertion), if (deviceWorn.value && exertionFlow.value.toFloat() >= 0.0) "${exertionFlow.value.toFloat().times(100).toInt()}%" else "---"))
        add(Utils.SelectedPhysioMetric("key_floors", painterResource(id = R.drawable.floors), stringResource(R.string.floors), dailyMetricsFlow.value?.floors?.toInt()?.toString() ?: "0"))
        add(Utils.SelectedPhysioMetric("key_calories", painterResource(id = R.drawable.calories), stringResource(R.string.calories), dailyMetricsFlow.value?.calories?.toInt()?.toString() ?: "0"))
        add(Utils.SelectedPhysioMetric("key_spo2", painterResource(id = R.drawable.oxygen_saturation), "SpO2", latestSpo2.value?.let { "${it.formatDecimal(1)}%" } ?: "---"))
        add(Utils.SelectedPhysioMetric("key_spo2_tier", painterResource(id = R.drawable.oxygen_saturation), "SpO2 Tier", latestSpo2TierEvent.value?.tier?.name ?: "NONE"))
    }

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
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.height(150.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(activePhysioMetrics) { physioMetric ->
                    MetricItem(
                        physioMetric.icon,
                        physioMetric.metric,
                        physioMetric.value,
                        physioMetric.key,
                        onClickOverride = if (physioMetric.key == "key_crm") {
                            openCrmReserveScreen
                        } else {
                            null
                        },
                        selectedPhysioMetric,
                        settingsVm
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "SpO2 Baseline: ${latestSpo2Debug.value?.activeBaselinePct?.formatDecimal(1) ?: "--"}% (${latestSpo2Debug.value?.activeBaselineFamily ?: "--"})",
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "SpO2 Bucket: ${latestSpo2Debug.value?.activeAltitudeBucket ?: "--"}  Provisional: ${latestSpo2Debug.value?.activeBaselineProvisional ?: "--"}",
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "SpO2 Ring: ${latestSpo2Debug.value?.ringSize ?: "--"}  Invalid: ${latestSpo2Debug.value?.lastInvalidReason ?: "none"}",
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "SpO2 StateTs: ${latestSpo2Debug.value?.lastStateUpdateTs ?: "--"}  Reset: ${latestSpo2Debug.value?.lastResetReason ?: "none"}",
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            val primaryMetric = activePhysioMetrics.firstOrNull()
            val crmMetric = activePhysioMetrics.firstOrNull { it.key == "key_crm" }
            val fallbackMetric = activePhysioMetrics.firstOrNull { it != primaryMetric && it.key != "key_crm" }
            val physioMetricsSummary = listOfNotNull(primaryMetric, crmMetric ?: fallbackMetric)

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

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.height(92.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(physioMetricsSummary) { physioMetric ->
                    MetricItem(
                        physioMetric.icon,
                        physioMetric.metric,
                        physioMetric.value,
                        physioMetric.key,
                        onClickOverride = if (physioMetric.key == "key_crm") {
                            openCrmReserveScreen
                        } else {
                            null
                        },
                        selectedPhysioMetric,
                        settingsVm
                    )
                }
            }
        }
    }
}

fun Float.formatDecimal(numberOfDecimals: Int = 2): String = "%.${numberOfDecimals}f".format(this)
fun Double.formatDecimal(numberOfDecimals: Int = 2): String = "%.${numberOfDecimals}f".format(this)

@Composable
fun MetricItem(
    icon: Painter?,
    metricTitle: String,
    metricVal: String,
    metricKey: String,
    onClickOverride: (() -> Unit)? = null,
    selectedPhysioMetric: String,
    settingsVm: WearTAKSettingsViewModel
) {
    val selectedMetric = settingsVm.savedSelectedPhysioMetric.collectAsState()
    Row(
        modifier = Modifier.then(
                if (selectedMetric.value == metricKey && onClickOverride == null) {
                    Modifier.border(
                        width = 3.dp,
                        color = colors.primary.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(5.dp),
                    )
                } else {
                   Modifier
                }
            ).padding(4.dp)
            .clickable(
                onClick = {
                    Log.d(TAG, "MetricItem clicked: $metricKey")
                    onClickOverride?.invoke()
                        ?: settingsVm.storeSelectedPhysioMetric(metricKey)
                    Log.d(TAG, "metric Key = $metricKey")
                }
            )
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                tint = Color.Unspecified,
                modifier = Modifier.size(20.dp),
                contentDescription = "",
            )
        }
        Column(
            modifier = Modifier.padding(start = 5.dp)
        ) {
            Text(
                text = metricTitle,
                fontSize = 11.sp,
                style = TextStyle(lineHeight = 1.em),
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

fun requestPhysioStatusAndAlertingComplicationUpdate(context: Context) {
    //Log.d(TAG, "Requesting Update of PhysioStatusandAlerting Complication")
    val componentName = ComponentName(context, PhysioStatusAndAlertingComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}

//@Preview(widthDp = 192, heightDp = 400, showBackground = true)
//@Composable
//fun AlertsScreenPreview() {
//    AlertsScreen(rememberSwipeDismissableNavController(), {}, true, alertListPreview)
//}
@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun AlertsDisabledPreview() {
//    AlertsDisabledScreen(rememberSwipeDismissableNavController(), {})
}
//@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
//@Composable
//fun MetricItemPreview() {
//MetricItem(
//    painterResource(id = R.drawable.heart_rate),
//    "Heart Rate",
//    "100bpm",
//    "key_heart_rate"
//    )
//}
