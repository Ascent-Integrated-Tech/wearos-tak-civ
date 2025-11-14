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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.MutableLiveData
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.RevealValue
import androidx.wear.compose.foundation.rememberRevealState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ExperimentalWearMaterialApi
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Picker
import androidx.wear.compose.material.PickerState
import androidx.wear.compose.material.SelectableChip
import androidx.wear.compose.material.SplitToggleChip
import androidx.wear.compose.material.SwipeToRevealChip
import androidx.wear.compose.material.SwipeToRevealDefaults
import androidx.wear.compose.material.SwipeToRevealPrimaryAction
import androidx.wear.compose.material.SwipeToRevealSecondaryAction
import androidx.wear.compose.material.SwipeToRevealUndoAction
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.rememberPickerState
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.complication.HeartRateComplicationService
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.CALLSIGN_TEAM_ROLE_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.HR_ALERT_THRESHOLDS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.PHYSIOLOGICAL_ALERTS_ENABLED_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_ADDED_VIA_UI
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_REMOVED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_TOGGLED
import com.aitech.weartak_civ.manager.NetworkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.ALL_WIFI_NETWORKS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.EVENT_SEND_DELAY_MS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.SOME_WIFI_NETWORKS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.ageSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.ageState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.ageValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.allergyList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.allergyListSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.allergyListState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.bloodTypeSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.bloodTypeState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.bloodTypeValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsignSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimization
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimizationSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimizationState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalServices
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalServicesSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableChatService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableChatServiceSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDittoService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDittoServiceSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationServiceSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableSmsForAlerts
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalAlerts
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalAlertsSettingState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableSmsForAlertsSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimization
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimizationSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimizationState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.generateSaveBattOnWifiString
import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightFeetSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightFeetState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightFeetValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightInchesSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightInchesState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.heightInchesValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.hrHighAlertBpmThreshold
import com.aitech.weartak_civ.presentation.MainActivity.Constants.hrHighAlertBpmThresholdSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.hrHighAlertBpmThresholdState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.hrHighAlertNumSecsThreshold
import com.aitech.weartak_civ.presentation.MainActivity.Constants.hrHighAlertNumSecsThresholdSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.hrHighAlertNumSecsThresholdState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.license
import com.aitech.weartak_civ.presentation.MainActivity.Constants.licenseSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.saveBattOnWifiString
import com.aitech.weartak_civ.presentation.MainActivity.Constants.saveBattOnWifiStringState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.smsNumbersList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.smsNumbersListString
import com.aitech.weartak_civ.presentation.MainActivity.Constants.smsNumbersListStringSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sexSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sexState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sexValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerListState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.team
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.userTypeSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.userTypeState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.userTypeValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.versionNumber
import com.aitech.weartak_civ.presentation.MainActivity.Constants.weightSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.weightState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.weightValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.wifiSsidList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.wifiSsidListSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.wifiSsidListState
import com.aitech.weartak_civ.presentation.SettingsActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.SettingsActivity.Constants.allergiesOptionsList
import com.aitech.weartak_civ.presentation.SettingsActivity.Constants.bloodTypeOptionsList
import com.aitech.weartak_civ.presentation.SettingsActivity.Constants.settingsActivity
import com.aitech.weartak_civ.presentation.SettingsActivity.Constants.sexOptionsList
import com.aitech.weartak_civ.presentation.SettingsActivity.Constants.userTypeOptionsList
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.CotDispatchService
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.LicenseManager.validateLicense
import com.aitech.weartak_civ.service.TAKConnectService
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTING_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_DISCONNECTED_STRING
import com.aitech.weartak_civ.presentation.MainActivity.Constants.category
import com.aitech.weartak_civ.presentation.MainActivity.Constants.categoryRolesEntryLists
import com.aitech.weartak_civ.presentation.MainActivity.Constants.categorySettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDynamicReportingStrategy
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDynamicReportingStrategySettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDynamicReportingStrategyState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableHailService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableHailServiceSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.onFootReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.onFootReportingIntervalSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.onFootReportingIntervalState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHeartRate
import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHeartRateSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHeartRateState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.constantReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.constantReportingIntervalSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.constantReportingIntervalState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBloodhoundCompassService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBloodhoundCompassServiceSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.stationaryReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.stationaryReportingIntervalSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.stationaryReportingIntervalState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.vehicleReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.vehicleReportingIntervalSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.vehicleReportingIntervalState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.whileAlertingReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.whileAlertingReportingIntervalSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.whileAlertingReportingIntervalState
import com.aitech.weartak_civ.service.HailService.HailLicenseStates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale


class SettingsActivity : ComponentActivity() {
    companion object Constants {
        val TAG = "WearTAK-" + SettingsActivity::class.java.simpleName
        lateinit var settingsActivity: SettingsActivity
        val callsignState = MutableLiveData<String>("")
        val licenseState = MutableLiveData<String>("")
        val sexOptionsList : List<String> = listOf("N/A", "Male", "Female")
        val bloodTypeOptionsList : List<String> = listOf("N/A", "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
        val allergiesOptionsList : List<String> = listOf("N/A", "Antibiotics", "Anti-Inflammatory (Ibuprofen)", "Antiseizure", "Aspirin", "Insulin", "Muscle Relaxers", "Sulfa Drugs")
        val userTypeOptionsList : List<String> = listOf("N/A", "Child", "Coalition Civilian", "Coalition Military", "Non-Coalition Civilian", "Non-Coalition Military", "Opposing Force Detainee")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "SettingsActivity onCreate()")
        settingsActivity = this
        loadSettingsValues()
        //TODO: Is there a reason this is called here? Is the same thing done through main activity?
//        lifecycleScope.launch {
//            categoryRolesEntryLists = dataRepository.getCategoryRolesList.first()
//            if (categoryRolesEntryLists.isEmpty())
//                categoryRolesEntryLists = applicationContext.defaultRoleCategoryEntriesFromRaw()
//
//            categorySettingsState.postValue(dataRepository.getRoleCategory.first())
//        }
        Log.d(TAG, "SettingsActivity onCreate called with enablePhysiologicalAlertsSettingState=$enablePhysiologicalAlertsSettingState and enablePhysiologicalAlerts=$enablePhysiologicalAlerts")

        enableEdgeToEdge()
        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WearTAKCivSettings(callsignState, licenseState)
                }
            }
        }
    }
}

fun loadSettingsValues(){
    enableLocationServiceSettingsState.postValue(enableLocationService)
    enableDittoServiceSettingsState.postValue(enableDittoService)
    enableChatServiceSettingsState.postValue(enableChatService)
    enableSmsForAlertsSettingsState.postValue(enableSmsForAlerts)
    enablePhysiologicalServicesSettingsState.postValue(enablePhysiologicalServices)
    enableHailServiceSettingsState.postValue(enableHailService)
    enableBloodhoundCompassServiceSettingsState.postValue(enableBloodhoundCompassService)
    callsignSettingsState.postValue(callsign)
    teamSettingsState.postValue(team)
    categorySettingsState.postValue(category)
    roleSettingsState.postValue(role)
    enableWifiBatteryOptimizationState.postValue(enableWifiBatteryOptimization)
    enableWifiBatteryOptimizationSettingsState.postValue(enableWifiBatteryOptimization)
    enableAllWifiBatteryOptimizationState.postValue(enableAllWifiBatteryOptimization)
    enableAllWifiBatteryOptimizationSettingsState.postValue(enableAllWifiBatteryOptimization)
    wifiSsidListState.postValue(wifiSsidList)
    wifiSsidListSettingsState.postValue(wifiSsidList)
    enableDynamicReportingStrategyState.postValue(enableDynamicReportingStrategy)
    enableDynamicReportingStrategySettingsState.postValue(enableDynamicReportingStrategy)
    constantReportingIntervalState.postValue(constantReportingInterval)
    constantReportingIntervalSettingsState.postValue(constantReportingInterval)
    stationaryReportingIntervalState.postValue(stationaryReportingInterval)
    stationaryReportingIntervalSettingsState.postValue(stationaryReportingInterval)
    onFootReportingIntervalState.postValue(onFootReportingInterval)
    onFootReportingIntervalSettingsState.postValue(onFootReportingInterval)
    vehicleReportingIntervalState.postValue(vehicleReportingInterval)
    vehicleReportingIntervalSettingsState.postValue(vehicleReportingInterval)
    whileAlertingReportingIntervalState.postValue(whileAlertingReportingInterval)
    whileAlertingReportingIntervalSettingsState.postValue(whileAlertingReportingInterval)
    takServerListState.postValue(takServerList)
    saveBattOnWifiStringState.postValue(saveBattOnWifiString)
    smsNumbersListStringSettingsState.postValue(convertSmsNumbersListToStringWithSeparator(smsNumbersList, ","))
    enablePhysiologicalAlertsSettingState.postValue(enablePhysiologicalAlerts)
    hrHighAlertBpmThresholdState.postValue(hrHighAlertBpmThreshold)
    hrHighAlertNumSecsThresholdState.postValue(hrHighAlertNumSecsThreshold)
    ageState.postValue(ageValue)
    heightFeetState.postValue(heightFeetValue)
    heightInchesState.postValue(heightInchesValue)
    weightState.postValue(weightValue)
    sexState.postValue(sexValue)
    bloodTypeState.postValue(bloodTypeValue)
    allergyListState.postValue(allergyList)
    userTypeState.postValue(userTypeValue)
    restingHeartRateState.postValue(restingHeartRate)
}

@Composable
fun WearTAKCivSettings(callsignLiveDataState: MutableLiveData<String>, licenseLiveDataState: MutableLiveData<String>) {
    val navController = rememberSwipeDismissableNavController()
    val context = LocalContext.current
//    var selectedCategoryAndRoleIndex by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectedCategoryAndRoleIndex by remember {
        mutableStateOf(findRoleSelectionFromString(roleSettingsState.value))
    }
    // collect license from data repo so we can see if it changes.
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")

    //Log.d(TAG, "WearTAKCivApp composable called")
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = if (isLicenseValid()) "settings_screen" else "invalid_license_screen"
    ) {
        composable("main_screen" ) {
//            MainScreen(
//                navController = navController,
//                callsignState = callsignLiveDataState,
//                dataRepository = dataRepository,
//                context = context
//            )
        }
        composable(
            "settings_screen") {
            SettingsScreen(navController, callsignLiveDataState, licenseLiveDataState, context)
        }
        composable(
            "callsign_screen") {
            CallsignSetting(navController, callsignSettingsState)
        }
        composable(
            "role_screen") {
            RoleSetting(navController)
        }
        composable(
            "role_category_screen/{index}",
                    arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            Log.d(TAG, "role_category_screen index: $index")
            RoleCategorySetting(
                navController = navController,
                categoryIndex = index,
                selectedCategoryAndRoleIndex = selectedCategoryAndRoleIndex,
                onRoleSelected = { selectedCategoryAndRoleIndex = it }
            )
        }
        composable(
            "team_screen") {
            TeamSetting(navController)
        }
        composable(
            "tak_server_screen") {
            TakServerSetting(navController)
        }
        composable(
            "new_server_screen") {
            NewServerPage(navController)
        }
        composable(
            "edit_server_screen/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            Log.d(TAG, "edit_server_screen index: $index")
            EditServerPage(navController, index)
        }
        composable("optimize_battery_on_wifi_screen"){
            OptimizeBatteryOnWifiSelectScreen(navController)
        }
        composable(
            "wifi_ssid_screen") {
            WifiSsidSelectScreen(navController)
        }
        composable(
            "wifi_ssid_setting_screen") {
            NewWiFiSSIDSetting(navController)
        }
        composable(
            "edit_wifi_ssid_setting_screen/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            Log.d(TAG, "edit_wifi_ssid_setting_screen index: $index")
            EditedWiFiSSIDSetting(navController, index)
        }
        composable(
            "reporting_settings_screen") {
            ReportingStrategySettings(navController)
        }
        composable(
            "reporting_dynamic_constant_screen") {
            ReportingStrategyDynamicConstantSetting(navController)
        }
        composable(
            "constant_reporting_interval_screen") {
            NumericEntryPage(navController, constantReportingIntervalSettingsState, 1000000, "Constant Reporting Interval", context, "reporting_settings_screen")
        }
        composable(
            "stationary_reporting_interval_screen") {
            NumericEntryPage(navController, stationaryReportingIntervalSettingsState, 1000000, "Stationary Reporting Interval", context, "reporting_settings_screen")
        }
        composable(
            "on_foot_reporting_interval_screen") {
            NumericEntryPage(navController, onFootReportingIntervalSettingsState, 1000000, "On Foot Reporting Interval", context, "reporting_settings_screen")
        }
        composable(
            "vehicle_reporting_interval_screen") {
            NumericEntryPage(navController, vehicleReportingIntervalSettingsState, 1000000, "Vehicle Reporting Interval", context,"reporting_settings_screen" )
        }
        composable(
            "while_alerting_reporting_interval_screen") {
            NumericEntryPage(navController, whileAlertingReportingIntervalSettingsState, 1000000, "While Alerting Reporting Interval", context, "reporting_settings_screen")
        }
        composable(
            "license_setting_screen") {
            LicenseKeyScreen(navController, licenseSettingsState, context)
        }
        composable(
            "invalid_license_screen") {
            InvalidLicenseScreen(navController)
        }
        composable(
            "physiological_alerts_setting_screen") {
            PhysiologicalAlertsSetting(navController)
        }
        composable(
            "physiological_alerts_bpm_screen") {
//            PhysioAlertsBpmThresholdSetting(navController, hrHighAlertBpmThresholdSettingsState, stringResource(R.string.threshold_high_heart_rate), "140")
            NumericEntryPage(navController, hrHighAlertBpmThresholdSettingsState, 300,stringResource(R.string.threshold_high_heart_rate), context,"physiological_alerts_setting_screen")
        }
        composable(
            "physiological_alerts_bpm_length_screen") {
//            PhysioAlertsBpmThresholdSetting(navController, hrHighAlertNumSecsThresholdSettingsState, (stringResource(R.string.threshold_high_heart_rate_length)), "60" + "seconds")
            NumericEntryPage(navController, hrHighAlertNumSecsThresholdSettingsState, 1000000, (stringResource(R.string.threshold_high_heart_rate_length)), context, "physiological_alerts_setting_screen")
        }
        //Physio Metrics
        composable(
            "physiological_metrics_setting_screen") {
            PhysiologicalMetricsSetting(navController)
        }
        composable(
            "physiological_metrics_age_screen") {
//            AgeWeightSetting(navController, ageSettingsState, 150, stringResource(R.string.age), context)
            NumericEntryPage(navController, ageSettingsState, 150, stringResource(R.string.age), context, "physiological_metrics_setting_screen")
        }
        composable(
            "physiological_metrics_height_screen") {
            HeightPickerSetting(navController)
        }
        composable(
            "physiological_metrics_weight_screen") {
//            AgeWeightSetting(navController, weightSettingsState, 1000, stringResource(R.string.weight), context)
            NumericEntryPage(navController, weightSettingsState, 1000, stringResource(R.string.weight), context, "physiological_metrics_setting_screen")
        }
        composable(
            "physiological_metrics_sex_screen") {
            SexBloodtypeUsertypeSetting(navController, sexSettingsState, sexOptionsList, stringResource(R.string.sex))
        }
        composable(
            "physiological_metrics_bloodtype_screen") {
            SexBloodtypeUsertypeSetting(navController, bloodTypeSettingsState, bloodTypeOptionsList, stringResource(R.string.blood_type))
        }
        composable(
            "physiological_metrics_allergies_screen") {
            AllergySetting(navController, stringResource(R.string.allergies))
        }
        composable(
            "physiological_metrics_usertype_screen") {
            SexBloodtypeUsertypeSetting(navController, userTypeSettingsState, userTypeOptionsList, stringResource(R.string.user_type))
        }
        composable(
            "physiological_metrics_resting_hr_screen") {
//            AgeWeightSetting(navController, restingHeartRateSettingsState, 150, stringResource(R.string.resting_heart_rate), context)
            NumericEntryPage(navController, restingHeartRateSettingsState, 150, stringResource(R.string.resting_heart_rate), context, "physiological_metrics_setting_screen")
        }
        composable(
            "hail_setting_screen") {
            HailSetting(navController)
        }
        composable(
            "hail_pairing_screen") {
            InvalidHailScreen(navController)
        }
    }

}

@Composable
fun GenericOutlinedTextField(
    liveDataState: MutableLiveData<String>,
    label: String,
    placeholder: String
): TextFieldValue {
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                liveDataState.value ?: "null"
            )
        )
    }
    OutlinedTextField(
        value = textFieldValue,
        onValueChange = {
            textFieldValue = it
        },
        maxLines = 1,
        label = { Text(text = label, color = colors.primary) },
        placeholder = { Text(text = placeholder, color = colors.primary) },
        textStyle = TextStyle(color = colors.primary)
    )
    return textFieldValue
}

@Composable
fun WearTakCivScreenDimensions(
    columnContent: @Composable () -> Unit,
    rowContent: @Composable () -> Unit,
    text: String
) {
    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .align(Alignment.TopCenter)
                    .padding(bottom = 30.dp)
                    .verticalScroll(rememberScrollState()),

            horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = text,
                    fontSize = if (text.length!! < 20) 15.sp else 13.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(vertical = 10.dp, horizontal = 20.dp)
                        .align(Alignment.CenterHorizontally)
                )
                columnContent()
            }
            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                rowContent()
            }
        }
    }
}

@Composable
fun CallsignRoleTeamChip(
    route: String,
    title: String,
    nameState: MutableLiveData<String>,
    navController: NavController
) {
    val nameSecondary: String? by nameState.observeAsState()
    Chip(
        onClick = { navController.navigate(route) },
        enabled = true,
        label = { Text(text = title) },
        secondaryLabel = {
            Text(text = nameSecondary.toString(), maxLines =1, overflow = TextOverflow.Ellipsis)
        },
        colors = ChipDefaults.chipColors(
            backgroundColor = Color.DarkGray,
            contentColor = Color.White,
            secondaryContentColor = Color.LightGray
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 3.dp)
    )
}

@Composable
fun CallsignSetting(navController: NavController, callsignLiveDataState: MutableLiveData<String>) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
//        var callsignTextFieldValue by remember {
//            mutableStateOf(
//                TextFieldValue(
//                    callsignLiveDataState.value ?: "null"
//                )
//            )
//        }
        var textField = GenericOutlinedTextField(callsignLiveDataState, stringResource(R.string.callsign), stringResource(R.string.callsign))

//        Log.d(TAG, "callsignTextFieldValue=" + callsignTextFieldValue.text)
//        OutlinedTextField(
//            value = callsignTextFieldValue,
//            onValueChange = {
//                callsignTextFieldValue = it
//            },
//            maxLines = 1,
//            label = { Text(text = stringResource(R.string.callsign), color = colors.primary) },
//            placeholder = { Text(text = stringResource(R.string.callsign), color = colors.primary) },
//            textStyle = TextStyle(color = colors.primary)
//        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Callsign Settings NOT saved")
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    callsignLiveDataState.postValue(textField.text)
                    Log.d(TAG, "Settings saved with callsign: ${callsignLiveDataState.value}")
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                })
        }
    }
}

@Composable
fun TeamSetting(navController: NavController) {
    val index : Int = findTeamIndex(teamSettingsState.value)
    var selectedRadioIndex by remember { mutableStateOf(index) }
    WearTakCivScreenDimensions(
        columnContent = {
            teamEntriesValuesList.ltEntries.forEachIndexed { index, ltItem ->
                run {
                    SelectableChip(
                        modifier = Modifier.fillMaxWidth(),
                        selected = index == selectedRadioIndex,
                        onClick = { selectedRadioIndex = index},
                        label = {
                            // The primary label should have a maximum 3 lines of text
                            Text(ltItem, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        },
                        enabled = true,
                        shape =
                            if (ltItem == teamEntriesValuesList.ltEntries.first())
                                RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp)
                            else if (ltItem == teamEntriesValuesList.ltEntries.last())
                                RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp)
                            else
                                RoundedCornerShape(0.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(15.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Team Settings NOT saved")
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    teamSettingsState.postValue(teamEntriesValuesList.ltEntries[selectedRadioIndex])
                    Log.d(TAG, "Settings saved with team: ${teamSettingsState.value}")
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                }
            )
        },
        text = stringResource(R.string.team)
    )
}

@Composable
fun RoleSetting (navController: NavController) {
//    val index = findRoleIndex(roleSettingsState.value)
//    var selectedRadioIndex by remember { mutableStateOf(index) }
    WearTakCivScreenDimensions(
        columnContent = {
            categoryRolesEntryLists.forEachIndexed { index, lrCategory ->
                run {
                    Chip(
                        onClick = {navController.navigate("role_category_screen/$index") },
                        enabled = true,
                        label = { Text(text = lrCategory.category) },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = Color.DarkGray,
                            contentColor = Color.White,
                            secondaryContentColor = Color.LightGray
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(15.dp))
        },
    rowContent = {
        BackButton(
            onClick = {
                //                roleSettingsState.postValue(roleEntriesValuesList.lrEntries[selectedRadioIndex])
//                Log.d(TAG, "Settings saved with role: ${roleSettingsState.value}")
                navController.navigate("settings_screen") {
                    popUpTo(("settings_screen")) {
                        inclusive = true
                    }
                } },
        )
    },
    text = stringResource(R.string.role)
    )
}

@Composable
fun RoleCategorySetting (
    navController: NavController,
    categoryIndex: Int,
    selectedCategoryAndRoleIndex: Pair<Int, Int>?,
    onRoleSelected: (Pair<Int, Int>) -> Unit
) {
    var localSelection by remember { mutableStateOf(selectedCategoryAndRoleIndex)}   // whatever the user's current pick is, e.g. (2,5)
//    val index = findRoleIndex(roleSettingsState.value)
    val categoryRoleListSetting = categoryRolesEntryLists.getOrNull(categoryIndex)
    val roleList = categoryRoleListSetting?.roles.orEmpty()
    WearTakCivScreenDimensions(
        columnContent = {
            LazyColumn(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.height(300.dp)) {
                itemsIndexed(roleList) { roleIndex, role ->
                    val isSelected = selectedCategoryAndRoleIndex?.let {
                        it.first == categoryIndex && it.second == roleIndex
                    } ?: false

                    SelectableChip(
                            modifier = Modifier.fillMaxWidth(),
                            selected = isSelected,
                            onClick = {
                                localSelection = categoryIndex to roleIndex
                                onRoleSelected(categoryIndex to roleIndex)},
                            label = {
                                // The primary label should have a maximum 3 lines of text
                                Text(role, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            },
                            enabled = true,
                            shape =
                                if (role == roleList.first())
                                    RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp)
                                else if (role == roleList.last())
                                    RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp)
                                else
                                    RoundedCornerShape(0.dp)
                        )
                    }
            }
            Spacer(modifier = Modifier.height(15.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Role Settings NOT saved")
                    navController.navigate("role_screen") {
                        popUpTo(("role_screen")) {
                            inclusive = true
                        }
                    }                },
                onClickConfirm = {
//                    val selectedRole = selectedCategoryAndRoleIndex?.let { (categoryIndex, roleIndex) ->
//                        roleCategoryEntryLists.getOrNull(categoryIndex)?.roles?.getOrNull(roleIndex)
//                    }
//                    if (selectedRole != null) {
//                        Log.d(TAG, "Selected role to save: $selectedRole")
//                        roleSettingsState.postValue(selectedRole)
//
//                    } else {
//                        Log.d(TAG, "No role selected.")
//                    }
//                    Log.d(TAG, "Settings saved with role: ${roleSettingsState.value}")

                    localSelection?.let{ (categoryIndex, roleIndex) ->
                        val entry = categoryRolesEntryLists[categoryIndex]
                        val selectedRole = entry.roles[roleIndex]

                        Log.d(TAG, "Saving category=${entry.category}, role=$selectedRole")
                        categorySettingsState.postValue(entry.category)
                        roleSettingsState.postValue(selectedRole)
                    } ?: Log.d(TAG, "No role selected")
                    navController.navigate("role_screen") {
                        popUpTo(("role_screen")) { inclusive = true }
                    }
                })
        },
        text = "Role - " + categoryRoleListSetting?.category
    )
}

fun findRoleSelectionFromString(roleString: String?): Pair<Int, Int>? {
    if (roleString == null) return null
    for ((categoryIndex, categoryRole) in categoryRolesEntryLists.withIndex()) {
        val roleIndex = categoryRole.roles.indexOf(roleString)
        if (roleIndex != -1) return Pair(categoryIndex, roleIndex)
    }
    return null
}

fun findRoleCategoryIndex(dataName: String?, dataList: List<String>) : Int {
    if (null == dataName) {
        return 0
    }
    val index = dataList.indexOf(dataName)
    if (index == -1) {
        return 0
    }
    return index
}

@SuppressLint("CoroutineCreationDuringComposition")
@Composable
fun TakServerSetting(navController: NavController) {
    val takServerSettingList by takServerListState.observeAsState(initial = emptyList())
    //val selectedIndexState = MutableLiveData<Int?>(null)
    //val selectedIndex by selectedIndexState.observeAsState(initial = null)
    Log.d(TAG, "TakServerSetting called")
    WearTakCivScreenDimensions(
        columnContent = {
            LazyColumn(modifier = Modifier
                    .height(200.dp)){
                itemsIndexed(takServerSettingList){ index, takServerItem ->
                    var takServerChecked by remember{ mutableStateOf(takServerItem.isEnabled)}
                    Log.d(TAG, "index($index): takServerChecked($takServerChecked)")
                    SwipeableTakServerChip(
                        index,
                        takServerItem.name,
                        takServerItem.address,
                        checked = takServerChecked,
                        onCheckedChange =  {
                            Log.d(TAG, "onCheckedChange index($index): $it")
                            takServerChecked = it
                            Log.d(TAG, "index($index): takServerChecked($takServerChecked)")
                            takServerSettingList[index].isEnabled = it
                            val mutableTakServerList = takServerSettingList.toMutableList()
                            mutableTakServerList.get(index).isEnabled = it
                            takServerList = mutableTakServerList.toList()
                            takServerListState.postValue(takServerList)
                            //selectedIndexState.postValue(null)
                            CoroutineScope(Dispatchers.Main).launch {
                                dataRepository.updateTakServerItem(index, takServerItem)
                                Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(
                                    MessageEvent(SERVER_TOGGLED, index)
                                ) }, EVENT_SEND_DELAY_MS)
                            } },
                        onPrimaryActionClick = {
                            Log.d(TAG, "Remove takServerItem ($takServerItem)")
                            val mutableTakServerList = takServerSettingList.toMutableList()
                            mutableTakServerList.removeAt(index)
                            takServerList = mutableTakServerList.toList()
                            takServerListState.postValue(takServerList)
                            //selectedIndexState.postValue(null)
                            Log.d(TAG, "onPrimaryActionClick index($index): REMOVE")
                            CoroutineScope(Dispatchers.Main).launch {
                                dataRepository.removeTakServerItem(takServerItem)
                                Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(
                                    MessageEvent(SERVER_REMOVED, index)
                                ) }, EVENT_SEND_DELAY_MS)
                            }
                        },
                        onSecondaryActionClick = {
                            //selectedIndexState.postValue(index)
                            navController.navigate("edit_server_screen/$index")
                        }
                    )
                }

                item {
                    addItemChip(navController, "new_server_screen", stringResource(R.string.add_network), "Add TAK Server")
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        },
        rowContent = {
            BackButton(
                onClick = {
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    } },
            )
        },
        stringResource(R.string.tak_server_connections)
    )
    Log.d(TAG, "Tak Server List: $takServerSettingList")
}

@Composable
fun addItemChip (
    navController: NavController,
    route: String,
    label: String,
    contentDescription: String
){
    Chip(
        onClick = { navController.navigate(route) {
            popUpTo((route)) {
                inclusive = true
            }
        } },
        enabled = true,
        label = { Text(text = label) },
        colors = ChipDefaults.chipColors(
            backgroundColor = Color.DarkGray,
            contentColor = Color.White,
            secondaryContentColor = Color.LightGray
        ),
        icon = {
            Icon(
                Icons.Filled.Add,
                contentDescription = contentDescription
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    )
}

@OptIn(ExperimentalWearMaterialApi::class)
@Composable
fun SwipeableTakServerChip(
    index: Int,
    title: String,
    address: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    onPrimaryActionClick: () -> Unit,
    onSecondaryActionClick: () -> Unit
) {
    val revealState = rememberRevealState()
    val scope = rememberCoroutineScope()
    val takServerStatus by TAKConnectService.takServerDetails.get(index).connectionStatusLiveData.observeAsState(initial = TAK_SERVER_DISCONNECTED_STRING)

    Log.d(TAG, "SwipeableTakServerChip called with index $index: takServerStatus($takServerStatus)")
    SwipeToRevealChip(
        revealState = revealState,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                // Use custom actions to make the primary and secondary actions accessible
                customActions =
                    listOf(
                        CustomAccessibilityAction("Delete TAK Server") {
                            /* Add the primary action click handler here */
                            true
                        },
                        CustomAccessibilityAction("Edit TAK Server") {
                            /* Add the secondary click handler here */
                            true
                        }
                    )
            },
        primaryAction = {
            SwipeToRevealPrimaryAction(
                revealState = revealState,
                icon = { Icon(SwipeToRevealDefaults.Delete, stringResource(R.string.delete)) },
                //TODO: DUPLICATE CODE - Same swipe to reveal chip for TAK server and WIFI name vvv
                label = { Text(stringResource(R.string.delete)) },
                onClick = { onPrimaryActionClick(); scope.launch {revealState.animateTo(targetValue = RevealValue.RightRevealed)}}
            )
        },
        secondaryAction = {
            SwipeToRevealSecondaryAction(
                revealState = revealState,
                onClick = { onSecondaryActionClick() }
            ) {
                Icon(SwipeToRevealDefaults.MoreOptions, "More Options")
            }
        },
        undoPrimaryAction = {
            SwipeToRevealUndoAction(
                revealState = revealState,
                label = { Text(stringResource(R.string.undo)) },
                onClick = { /* Add the undo handler for primary action */ }
            )
        },
        undoSecondaryAction = {
            SwipeToRevealUndoAction(
                revealState = revealState,
                label = { Text(stringResource(R.string.undo)) },
                onClick = { /* Add the undo handler for secondary action */ }
            )
        },
        onFullSwipe = {onPrimaryActionClick() }
        //TODO: DUPLICATE CODE - Same swipe to reveal chip for TAK server and WIFI name^^^
    ) {
        SplitToggleChip(
            label = {
                Image(
                    painter = if (takServerStatus == TAK_SERVER_CONNECTED_STRING) painterResource(R.drawable.tak_server_connected) else if (takServerStatus == TAK_SERVER_CONNECTING_STRING) painterResource(R.drawable.tak_server_connecting) else painterResource(R.drawable.tak_server_disconnected),
                    contentDescription = "Top Left Icon",
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(22.dp)
                        .padding(end = 2.dp),
                )
                Text(title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier
                    .align(Alignment.CenterVertically))
            },
            secondaryLabel = {
                Text(address, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            checked = checked,
            toggleControl = {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    modifier = Modifier.size(25.dp)
                )
            },
            onCheckedChange = onCheckedChange,
            enabled = true,
            onClick = { scope.launch {revealState.animateTo(targetValue = RevealValue.RightRevealing)} },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 3.dp)
        )
    }
}

@Composable
fun NewServerPage(navController: NavController) {
    var serverSettingsChanged = remember { mutableStateOf(false) }
    val isNetworkConnected = remember { mutableStateOf(false) }
    WearTAKCIVTheme {
        var name by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("8089") }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var isP12Cert = false
        val context = LocalContext.current
        val serverSettingsChanged = remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .align(Alignment.TopCenter)
                    .padding(bottom = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.new_server),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                ServerSettingText(
                    stringResource(R.string.connection_name),
                    name,
                    onValueChanged = { name = it },
                    false,
                    false,
                    true
                )
                ServerSettingText(
                    stringResource(R.string.address_url),
                    address,
                    onValueChanged = { address = it },
                    false,
                    false,
                    true
                )
                ServerSettingText(
                    stringResource(R.string.port),
                    port,
                    onValueChanged = { port = it },
                    false,
                    true,
                    true
                )
                ServerSettingText(
                    stringResource(R.string.username),
                    username,
                    onValueChanged = { username = it },
                    false,
                    false,
                    true
                )
                ServerSettingText(
                    stringResource(R.string.password),
                    password,
                    onValueChanged = { password = it },
                    true,
                    false,
                    true
                )

                val focusManager = LocalFocusManager.current

//                OutlinedTextField(
//                    modifier = Modifier.padding(vertical = 5.dp),
//                    keyboardOptions = KeyboardOptions(
//                        keyboardType = KeyboardType.Number,
//                        imeAction = ImeAction.Done
//                    ),
//                    keyboardActions = KeyboardActions(
//                        onDone = {
//                            if (reportingUpdateInterval.isEmpty()) {
//                                Toast.makeText(
//                                    mainActivity,
//                                    context.getString(R.string.reporting_interval_empty_enter_again), Toast.LENGTH_SHORT).show()
//                            } else if (reportingUpdateInterval == "0") {
//                                Toast.makeText(
//                                    mainActivity,
//                                    //TODO: DUPLICATE CODE - Same outlinetextfield credentials for New and Edited TAK server pagevvv
//                                    context.getString(R.string.reporting_interval_0_enter_again), Toast.LENGTH_SHORT).show()
//                            } else {
//                                // If there is valid input, we can clear focus
//                                // (which typically dismisses the keyboard)
//                                focusManager.clearFocus()
//
//                                // Now you can navigate or do the next action
//                            }
//                        }
//                    ),
//                    singleLine = true,
//                    value = reportingUpdateInterval,
//                    onValueChange = {
//                        reportingUpdateInterval = it
//                    },
//                    label = {
//                        Text(
//                            text = stringResource(R.string.reporting_interval),
//                            color = colors.primary,
//                            style = MaterialTheme.typography.caption3
//                        )
//                    },
//                    placeholder = {
//                        Text(
//                            text = stringResource(R.string.interval_in_secs),
//                            color = colors.primary,
//                            style = MaterialTheme.typography.caption3
//                        )
//                    },
//                    textStyle = TextStyle(color = colors.primary),
//                )
                //TODO: DUPLICATE CODE - Same outlinetextfield credentials for New and Edited TAK server page^^^
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {

                val maxReportRate: Int = 100000000
                val minReportRate: Int = 1

                ConfirmCancelButtons(
                    onClickCancel = {
                        Log.d(TAG, "NewServerPage Settings NOT saved")
                        navController.navigate("tak_server_screen") {
                            popUpTo(("tak_server_screen")) {
                                inclusive = true
                            }
                        }
                    },
                    onClickConfirm = {
                        scope.launch {
                            if (name.isNotBlank() && address.isNotBlank() && port.isNotBlank() && username.isNotBlank() &&
                                password.isNotBlank()) {
                                val takServerItem = DataRepository.TakServerItem(true, name, address, port.toInt(),
                                        isP12Cert, username, password, "", "")
                                runBlocking {
                                    dataRepository.addTakServerItem(takServerItem)
                                }
                                val mutableTakServerList = takServerListState.value?.toMutableList() ?: emptyList<DataRepository.TakServerItem>().toMutableList()
                                mutableTakServerList.add(takServerItem)
                                takServerList = mutableTakServerList.toList()
                                takServerListState.postValue(takServerList)
                                serverSettingsChanged.value = true

                                Log.d(TAG, "serverSettingsChanged=${serverSettingsChanged.value}")
                                Handler(Looper.getMainLooper()).postDelayed(
                                    { EventBus.getDefault().post(MessageEvent(
                                        SERVER_ADDED_VIA_UI, takServerList.size-1)) }, EVENT_SEND_DELAY_MS)
                            } else {
                                Toast.makeText(
                                    mainActivity,
                                    context.getString(R.string.please_enter_valid_values_for_all_fields), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
                Log.d(TAG, "NewServerPage serverSettingsChanged=${serverSettingsChanged.value}")
                if (serverSettingsChanged.value) {
                    //TAKConnectService.newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTING_STRING)
                    val loadingDialog by TAKConnectService.newServerAddedViaUiConnectionStatusLiveData.observeAsState()
                    val shouldShowLoadingDialog = remember { mutableStateOf(false) }
                    val shouldShowConfirmDialog = remember { mutableStateOf(false) }
                    val isServerConnected = remember { mutableStateOf(false) }

                    if (NetworkManager.isNetworkConnected) {
                        Log.d(TAG, "NewServerPage: serverSettingsChanged w/ Network connected")
                        isNetworkConnected.value = true
                        displayServerDialogs(true, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected)
                        LoadingDialog(shouldShowLoadingDialog, stringResource(R.string.registering_device_with_tak_server))
                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog,shouldShowLoadingDialog, navController, isServerConnected, serverSettingsChanged)
                    } else {
                        Log.d(TAG, "NewServerPage: serverSettingsChanged w/ Network disconnected")
                        isNetworkConnected.value = false
                        displayServerDialogs(false, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected)
                        //LoadingDialog(shouldShowLoadingDialog)
                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog, shouldShowLoadingDialog, navController, isServerConnected, serverSettingsChanged)
                    }
                }
            }
        }
    }
}

@Composable
fun EditServerPage(
    navController: NavController,
    index: Int
) {
    WearTAKCIVTheme {
        val takServerListLocal by takServerListState.observeAsState(initial = emptyList())
        Log.d(TAG, "Edited tak server list: $takServerListLocal")
        var name by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.name.orEmpty()) }
        var address by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.address.orEmpty()) }
        var port by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.port.toString().orEmpty()) }
        var username by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.username.orEmpty()) }
        var password by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.password.orEmpty()) }
        var isP12Cert by remember { mutableStateOf(takServerListLocal.get(index)?.isP12Cert ?: false) }
        val context = LocalContext.current
        val serverConnSettingsChanged = remember { mutableStateOf(false) }
        Log.d(TAG, "address=$address, serverSettingsChanged=${serverConnSettingsChanged.value}")

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .align(Alignment.TopCenter)
                    .padding(bottom = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.edit_server),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                ServerSettingText(
                    stringResource(R.string.connection_name),
                    name,
                    onValueChanged = { name = it },
                    false,
                    false,
                    true
                )

                if (isP12Cert == true) {
                    ServerSettingText(
                        stringResource(R.string.address_url),
                        address,
                        onValueChanged = { address = it },
                        false,
                        false,
                        false
                    )
                    ServerSettingText(
                        stringResource(R.string.port),
                        port,
                        onValueChanged = { port = it },
                        false,
                        true,
                        true)
                } else {
                    ServerSettingText(
                        stringResource(R.string.address_url),
                        address,
                        onValueChanged = { address = it },
                        false,
                        false,
                        true
                    )
                    ServerSettingText(
                        stringResource(R.string.port),
                        port,
                        onValueChanged = { port = it },
                        false,
                        true,
                        true)
                    ServerSettingText(
                        stringResource(R.string.username),
                        username,
                        onValueChanged = { username = it },
                        false,
                        false,
                        true
                    )
                    ServerSettingText(
                        stringResource(R.string.password),
                        password,
                        onValueChanged = { password = it },
                        true,
                        false,
                        true
                    )
                }
                val focusManager = LocalFocusManager.current

//                OutlinedTextField(
//                    modifier = Modifier.padding(vertical = 5.dp),
//                    keyboardOptions = KeyboardOptions(
//                        keyboardType = KeyboardType.Number,
//                        imeAction = ImeAction.Done
//                    ),
//                    keyboardActions = KeyboardActions(
//                        onDone = {
//                            if (reportingUpdateInterval.isEmpty()) {
//                                Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_empty_enter_again), Toast.LENGTH_SHORT).show()
//                            } else if (reportingUpdateInterval == "0") {
//                                Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_0_enter_again), Toast.LENGTH_SHORT).show()
//                            //TODO: DUPLICATE CODE - Same outlinetextfield credentials for New and Edited TAK server pagevvv
//                            } else {
//                                // If there is valid input, we can clear focus
//                                // (which typically dismisses the keyboard)
//                                focusManager.clearFocus()
//
//                                // Now you can navigate or do the next action
//                            }
//                        }
//                    ),
//                    singleLine = true,
//                    value = reportingUpdateInterval,
//                    onValueChange = {
//                        reportingUpdateInterval = it
//                    },
//                    label = {
//                        Text(
//                            text = stringResource(R.string.reporting_interval),
//                            color = colors.primary,
//                            style = MaterialTheme.typography.caption3
//                        )
//                    },
//                    placeholder = {
//                        Text(
//                            text = stringResource(R.string.interval_in_secs),
//                            color = colors.primary,
//                            style = MaterialTheme.typography.caption3
//                        )
//                    },
//                    textStyle = TextStyle(color = colors.primary),
//                )
                //TODO: DUPLICATE CODE - Same outlinetextfield credentials for New and Edited TAK server page^^^
                if (isP12Cert == true) {
                    Text(
                        text = "Using client certificate",
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(5.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                val maxReportRate: Int = 100000000
                val minReportRate: Int = 1
                val scope = rememberCoroutineScope()

                val context = LocalContext.current

                ConfirmCancelButtons(
                    onClickCancel = {
                        Log.d(TAG, "EditServerPage Settings NOT saved")
                        navController.navigate("tak_server_screen") {
                            popUpTo(("tak_server_screen")) {
                                inclusive = true
                            }
                        }
                    },
                    onClickConfirm = {
                        if (name.isNotBlank() && address.isNotBlank() && port.isNotBlank() &&
                            (if (isP12Cert) { true } else { username.isNotBlank() } ) &&
                            (if (isP12Cert) { true } else { password.isNotBlank() } ) ) {
                            val takServerDetails = takServerList.get(index)
                            Log.d(TAG, "EditServerPage: onClickConfirm all settings good")
                            Log.d(TAG, "takServerDetails: $takServerDetails")
                            Log.d(TAG, "name: $name, address: $address, port=$port, username=$username, password=$password")
                            if (name != takServerDetails.name || address != takServerDetails.address || port != takServerDetails.port.toString() ||
                                username != takServerDetails.username || password != takServerDetails.password) {
                                if (name == takServerDetails.name) {
                                    // Name is same, so something else in address, port, username, or password has changed
                                    serverConnSettingsChanged.value = true
                                    Log.d(TAG, "serverConnSettingsChanged set to true")
                                } else { // Name is different
                                    if (address != takServerDetails.address || port.toInt() != takServerDetails.port ||
                                        username != takServerDetails.username || password != takServerDetails.password) {
                                        // Apart from name, something else is different
                                        serverConnSettingsChanged.value = true
                                        Log.d(TAG, "serverConnSettingsChanged set to true with name being different")
                                    }
                                }
                                val changedTakServerDetail: DataRepository.TakServerItem =
                                    if (isP12Cert == true) {
                                        DataRepository.TakServerItem(takServerListLocal.get(index).isEnabled, name, address, port.toInt(), isP12Cert,
                                            takServerListLocal.get(index).username, takServerListLocal.get(index).password, takServerListLocal.get(index).p12Cert, takServerListLocal.get(index).p12CertPassword)
                                    } else {
                                        DataRepository.TakServerItem(takServerListLocal.get(index).isEnabled, name, address, port.toInt(), isP12Cert,
                                            username, password, "", "")
                                    }

                                runBlocking {
                                    dataRepository.updateTakServerItem(index, changedTakServerDetail)
                                }

                                val mutableTakServerList = takServerListLocal.toMutableList()
                                mutableTakServerList.set(index, changedTakServerDetail)
                                takServerList = mutableTakServerList.toList()
                                takServerListState.postValue(takServerList)

                                if (serverConnSettingsChanged.value) {
                                    Log.d(TAG, "EditServerPage serverSettingsChanged=${serverConnSettingsChanged.value}")
                                    EventBus.getDefault().post(MessageEvent(SERVER_CHANGED, index))
                                } else {
                                    navController.navigate("tak_server_screen") {
                                        popUpTo(("tak_server_screen")) {
                                            inclusive = true
                                        }
                                    }
                                }
                            } else {
                                Log.d(TAG, "EditServerPage onClickConfirm: nothing has changed")
                                serverConnSettingsChanged.value = false
                                navController.navigate("tak_server_screen") {
                                    popUpTo(("tak_server_screen")) {
                                        inclusive = true
                                    }
                                }
                            }
                        } else {
                            Toast.makeText(mainActivity, context.getString(R.string.please_enter_valid_values_for_all_fields), Toast.LENGTH_LONG).show()
                        }
                    }
                )
                Log.d(TAG, "EditServerPage: loadingDialog serverSettingsChanged=${serverConnSettingsChanged.value}")
                if (serverConnSettingsChanged.value) {
                    val loadingDialog by TAKConnectService.takServerDetails.get(index).connectionStatusLiveData.observeAsState()
                    val shouldShowLoadingDialog = remember { mutableStateOf(false) }
                    val shouldShowConfirmDialog = remember { mutableStateOf(false) }
                    val isServerConnected = remember { mutableStateOf(false) }
                    if (NetworkManager.isNetworkConnected) {
                        Log.d(TAG, "EditServerPage: serverSettingsChanged w/ Network connected")
                        val isNetworkConnected = remember { mutableStateOf(true)}

                        displayServerDialogs(isNetworkConnected.value, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected)
                        LoadingDialog(shouldShowLoadingDialog, stringResource(R.string.registering_device_with_tak_server))
                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog, shouldShowLoadingDialog, navController, isServerConnected, serverConnSettingsChanged)
                    } else {
                        Log.d(TAG, "EditServerPage: serverSettingsChanged w/ Network disconnected")
                        val isNetworkConnected = remember { mutableStateOf(false)}

                        displayServerDialogs(isNetworkConnected.value, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected)
                        //LoadingDialog(shouldShowLoadingDialog)
                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog, shouldShowLoadingDialog, navController, isServerConnected, serverConnSettingsChanged)
                    }
                }
            }
        }
    }
}

@Composable
fun ServerSettingText (
    label: String,
    value: String,
    onValueChanged: (String) -> Unit,
    isPassword : Boolean,
    isPort : Boolean,
    isEnabled: Boolean
) {
    val maxChar = 20

    OutlinedTextField(
        value = value,
        enabled = isEnabled,
        onValueChange = onValueChanged,
//        maxLines = 1,
        label = { Text(text = label, color = colors.primary, fontSize = 12.sp) },
        textStyle = TextStyle(color = colors.primary, fontSize = if (value!!.length <= maxChar) 12.sp else 10.sp),
        modifier = Modifier.padding(bottom = 5.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (isPort) KeyboardType.Number else KeyboardType.Text),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
//TODO: Edit Selected Options no longer jumps to bottom of screen, need to fix
fun OptimizeBatteryOnWifiSelectScreen(navController: NavController) {
    val enableWifiOpt = enableWifiBatteryOptimizationSettingsState.value ?: true
    val enableAllWifiOpt = enableAllWifiBatteryOptimizationSettingsState.value ?: true
    val SOME_WIFI_CONNECTIONS = stringResource(R.string.some_wifi_connections)
    val ALL_WIFI_CONNECTIONS = stringResource(R.string.all_wifi_connections)
    val NO_WIFI_CONNECTIONS = stringResource(R.string.no_wifi_connections)
    val radioOptions = listOf(ALL_WIFI_CONNECTIONS, NO_WIFI_CONNECTIONS, SOME_WIFI_CONNECTIONS)
    val initialValue = when {
        enableWifiOpt && enableAllWifiOpt -> radioOptions[0]  // "All"
        !enableWifiOpt -> radioOptions[1] // "No"
        else -> radioOptions[2] // "Some"
    }
    val selectedValue = remember { mutableStateOf(initialValue) }
    val isSelectedItem: (String) -> Boolean = { selectedValue.value == it }
    val onChangeState: (String) -> Unit = { selectedValue.value = it }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    //Log.d(TAG, "OptimizeBatteryonWifiSelectScreen called")
    WearTakCivScreenDimensions(
        columnContent = {
            Text(
                text = stringResource(R.string.slows_update_rate_on_enabled_wifi_networks),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .padding(horizontal = 10.dp)
            )

            Text(
                text = stringResource(R.string.optimize_on),
                textAlign = TextAlign.Left,
                modifier = Modifier
                    .padding(bottom = 3.dp)
                    .padding(horizontal = 10.dp)
//                    .align(Alignment.Start)
            )
            Log.d(TAG, "initial  value = $initialValue, selected value: ${selectedValue.value}")
            radioOptions.forEach { text ->
                SelectableChip(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 5.dp),
                    selected = isSelectedItem(text),
                    onClick = { onChangeState(text)
                        if (text == SOME_WIFI_CONNECTIONS) {
                            enableWifiBatteryOptimizationSettingsState.postValue(true)
                            enableAllWifiBatteryOptimizationSettingsState.postValue(false)
                            scope.launch { scrollState.animateScrollTo(scrollState.maxValue + 150) }
                        } else if (text == ALL_WIFI_CONNECTIONS) {
                            enableWifiBatteryOptimizationSettingsState.postValue(true)
                            enableAllWifiBatteryOptimizationSettingsState.postValue(true)
                        } else {
                            enableWifiBatteryOptimizationSettingsState.postValue(false)
                            enableAllWifiBatteryOptimizationSettingsState.postValue(false)
                        }
                    },
                    label = {
                        // The primary label should have a maximum 3 lines of text
                        Text(text, maxLines = 3, fontSize = 14.sp, overflow = TextOverflow.Ellipsis)
                    },
                    enabled = true,
                )

            }
            if (selectedValue.value == SOME_WIFI_CONNECTIONS) {
                Chip(
                    onClick = {
                        navController.navigate("wifi_ssid_screen") {
                            popUpTo(("wifi_ssid_screen")) {
                                inclusive = true
                            }
                        }
                        Log.d(TAG, "Some WiFi Connections selected: initial value = $initialValue, selected value: ${selectedValue.value}, isWifiOpt = $enableWifiOpt, isAllWifiOpt = $enableAllWifiOpt, wifiSsidList = ${wifiSsidListSettingsState.value}")
                    },
                    enabled = true,
                    label = {
                        Text(
                            text = stringResource(R.string.edit_optimized_wifi_connections),
                            fontWeight = FontWeight.Normal,
                            color = colors.primary
                        )
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray,
                        contentColor = Color.White,
                        secondaryContentColor = Color.LightGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 20.dp)
                )
            }
                Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "OptimizeBatteryonWifiSelectScreen Settings NOT saved")

                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    // TODO: check why enableWifiBatteryOptimizationState.value is Boolean?
                    val prevWifiOpt: Boolean = enableWifiBatteryOptimizationState.value ?: true
                    val prevAllOpt: Boolean = enableAllWifiBatteryOptimizationState.value ?: true
                    val currWifiOpt = enableWifiBatteryOptimizationSettingsState.value ?: true
                    val currAllOpt = enableAllWifiBatteryOptimizationSettingsState.value ?: true

                    if ((prevWifiOpt != currWifiOpt) || (prevAllOpt != currAllOpt) || (wifiSsidListState.value != wifiSsidListSettingsState.value)) {
                        Log.d(TAG, "Changed sthg: enableWifiOpt(old=$prevWifiOpt, new=$currWifiOpt), enableOptimAllWifi(old=$prevAllOpt, new=$currAllOpt), wifiSsidList(old=${wifiSsidListState.value}, new=${wifiSsidListSettingsState.value})")
                        saveBattOnWifiStringState.postValue(generateSaveBattOnWifiString(currWifiOpt ?: true, currAllOpt ?: true))
                        enableWifiBatteryOptimizationState.postValue(currWifiOpt)
                        enableAllWifiBatteryOptimizationState.postValue(currAllOpt)
                        wifiSsidListState.postValue(wifiSsidListSettingsState.value)
                    } else {
                        Log.d(TAG, "Settings NOT changed, NOT saved")
                    }

                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }

                })
        },
        text = stringResource(R.string.save_battery_on_wifi)
    )
}

@Composable
fun WifiSsidSelectScreen(navController: NavController) {
    //Log.d(TAG, "WifiSsidSelectScreen called")
    val wifiSsidList by wifiSsidListSettingsState.observeAsState(initial = emptyList())
    WearTakCivScreenDimensions(
        columnContent = {
            LazyColumn ( modifier = Modifier
                .height(100.dp)){
                Log.d(TAG, "SSID List = $wifiSsidList")
                itemsIndexed(wifiSsidList){ index, wifiSsidItem ->
                    WifiSsidItemChip(
                        wifiSsidItem.name,
                        onPrimaryActionClick = {
                            Log.d(TAG, "Remove wifiSsidItem ($wifiSsidItem)")
                            val mutableWifiSsidList = wifiSsidList.toMutableList()
                            mutableWifiSsidList.removeAt(index)
                            wifiSsidListSettingsState.postValue(mutableWifiSsidList.toList())
                        },
                        onSecondaryActionClick = {
                            // Navigate to "edit_wifi_ssid_setting_screen/<index>"
                            //Log.d(TAG, "onSecondaryActionClick called")
                            navController.navigate("edit_wifi_ssid_setting_screen/$index")
                        }
                    )
                }

                item {
                    addItemChip(navController, "wifi_ssid_setting_screen", stringResource(R.string.add_network), "Add Wifi")
                }

            }
            Spacer(modifier = Modifier.height(10.dp))
        },
        rowContent = {
            BackButton(
                onClick = { navController.navigate("optimize_battery_on_wifi_screen") {
                    popUpTo(("optimize_battery_on_wifi_screen")) {
                        inclusive = true
                    }
                } })
        },
        text = stringResource(R.string.enabled_wifi_connections)
    )
}

@OptIn(ExperimentalWearMaterialApi::class)
@Composable
fun WifiSsidItemChip(
    ssid: String,
    onPrimaryActionClick: () -> Unit,
    onSecondaryActionClick: () -> Unit
) {
    val revealState = rememberRevealState()
    val scope = rememberCoroutineScope()

    //Log.d(TAG, "WifiSsidItemChip called")
    SwipeToRevealChip(
        revealState = revealState,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                // Use custom actions to make the primary and secondary actions accessible
                customActions = listOf(
                    CustomAccessibilityAction("Delete WiFi SSID") {
                        /* Add the primary action click handler here */
                        true
                    },
                    // TODO: Can we change the MoreOptions Icon to Edit Icon?
                    CustomAccessibilityAction("Edit WiFi SSID") {
                        /* Add the secondary click handler here */
                        true
                    }
                )
            },
        primaryAction = {
            SwipeToRevealPrimaryAction(
                revealState = revealState,
                icon = { Icon(SwipeToRevealDefaults.Delete, "Delete") },
                //TODO: DUPLICATE CODE - Same swipe to reveal chip for TAK server and WIFI namevvv
                label = { Text(stringResource(R.string.delete)) },
                onClick = { onPrimaryActionClick(); scope.launch {revealState.animateTo(targetValue = RevealValue.RightRevealed)}}
            )
        },
        secondaryAction = {
            SwipeToRevealSecondaryAction(
                revealState = revealState,
                onClick = { onSecondaryActionClick() }
            ) {
                Icon(SwipeToRevealDefaults.MoreOptions, "More Options")
            }
        },
        undoPrimaryAction = {
            SwipeToRevealUndoAction(
                revealState = revealState,
                label = { Text(stringResource(R.string.undo)) },
                onClick = { /* Add the undo handler for primary action */ }
            )
        },
        undoSecondaryAction = {
            SwipeToRevealUndoAction(
                revealState = revealState,
                label = { Text(stringResource(R.string.undo)) },
                onClick = { /* Add the undo handler for secondary action */ }
            )
        },
        onFullSwipe = {onPrimaryActionClick() }
    ) {
        //TODO: DUPLICATE CODE - Same swipe to reveal chip for TAK server and WIFI name^^^
        Chip(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 5.dp),
            onClick = {
                scope.launch {revealState.animateTo(targetValue = RevealValue.RightRevealing)}
            },
            colors = ChipDefaults.chipColors(Color.DarkGray),
            label = { Text(ssid, maxLines = 3) }
        )
    }
}

@Composable
fun NewWiFiSSIDSetting(
    navController: NavController,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        //Log.d(TAG, "NewWiFiSSIDSetting called")
        var name by remember {
            mutableStateOf("")
        }

        OutlinedTextField(
            value = name,
            onValueChange = {text ->
                name = text
            },
            //TODO: DUPLICATE CODE - Same outlinetextfield credentials for new and edited wifiSSID chivvv
            maxLines = 1,
            label = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            placeholder = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            textStyle = TextStyle(color = colors.primary)
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "NewWiFiSSIDSetting Settings NOT saved")
                    navController.navigate("wifi_ssid_screen") {
                        popUpTo(("wifi_ssid_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    //TODO: DUPLICATE CODE - Same outlinetextfield credentials for new and edited wifiSSID chi^^^
                    if (name.isNotBlank()) {
                        val mutableWifiSsidList = wifiSsidListSettingsState.value?.toMutableList() ?: emptyList<DataRepository.WifiSsidItem>().toMutableList()
                        mutableWifiSsidList.add(DataRepository.WifiSsidItem(name))
                        wifiSsidListSettingsState.postValue(mutableWifiSsidList.toList())
                    }

                    navController.navigate("wifi_ssid_screen") {
                        popUpTo(("wifi_ssid_screen")) {
                            inclusive = true
                        }
                    }

                })
        }
    }
}

@Composable
fun EditedWiFiSSIDSetting(
    navController: NavController,
    index: Int
) {
    //Log.d(TAG, "EditedWiFiSSIDSetting called")
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val wifiSsidList by wifiSsidListSettingsState.observeAsState(initial = emptyList())
        Log.d(TAG, "EditedWiFiSSIDSetting SSID list: $wifiSsidList")
        var name by remember { mutableStateOf(wifiSsidList.getOrNull(index)?.name.orEmpty()) }
        Log.d(TAG, "text = $name")

        OutlinedTextField(
            value = name,
            onValueChange = { text -> name = text },
            //TODO: DUPLICATE CODE - Same outlinetextfield credentials for new and edited wifiSSID chivvv
            maxLines = 1,
            label = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            placeholder = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            textStyle = TextStyle(color = colors.primary)
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "EditedWiFiSSIDSetting Settings NOT saved")
                    navController.navigate("wifi_ssid_screen") {
                        popUpTo(("wifi_ssid_screen")) {
                            inclusive = true
                        }
                    }
                },
                //TODO: DUPLICATE CODE - Same outlinetextfield credentials for new and edited wifiSSID chi^^^
                onClickConfirm = {
                    val mutableWifiSsidList = wifiSsidList.toMutableList()
                    mutableWifiSsidList.set(index, DataRepository.WifiSsidItem(name))
                    wifiSsidListSettingsState.postValue(mutableWifiSsidList.toList())

                    navController.navigate("wifi_ssid_screen") {
                        popUpTo(("wifi_ssid_screen")) {
                            inclusive = true
                        }
                    }

                })
        }
    }
}

@Composable
fun LoadingDialog(
    shouldShowDialog: MutableState<Boolean>,
    secondaryText: String
) {
    if (shouldShowDialog.value) {
        Dialog(
            onDismissRequest = { shouldShowDialog.value = false },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.DarkGray)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {

                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = secondaryText,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun ServerRegistrationDialog(
    isNetworkConnected: MutableState<Boolean>,
    shouldShowDialog: MutableState<Boolean>,
    shouldShowLoadingDialog: MutableState<Boolean>,
    navController: NavController,
    isServerConnected: MutableState<Boolean>,
    serverDetailsChanged: MutableState<Boolean>
) {
    val invalidCredText = stringResource(R.string.invalid_credentials_try_again)
    val retryText = stringResource(R.string.retry)
    if (shouldShowDialog.value) {
        Log.d(TAG, "ServerRegistrationDialog: shouldShowDialog=${shouldShowDialog.value}, shouldShowLoadingDialog=${shouldShowLoadingDialog.value}")
        shouldShowLoadingDialog.value = false
        AlertDialog(
            modifier = Modifier.wrapContentHeight(),
            containerColor = Color.DarkGray,
            onDismissRequest = {
                Log.d(TAG, "onDismissRequest")
                shouldShowDialog.value = false
            },
            title = {
                if (isServerConnected.value) {
                    Text(stringResource(R.string.registration_complete), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                } else {
                    Text(stringResource(R.string.registration_failed), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 15.dp))
                }
            },
            text = {
                if (isNetworkConnected.value) {
                    if (isServerConnected.value) {
                        Text(stringResource(R.string.tak_server_registration_succeeded), textAlign = TextAlign.Center)
                    } else {
                        Text(invalidCredText, textAlign = TextAlign.Center, fontSize = if(invalidCredText.length!!<35) 15.sp else 13.sp)
                    }
                } else {
                    Log.d(TAG, "text=(${stringResource(R.string.network_disconnected_try_later)}), isNetworkConnected=${isNetworkConnected.value}, isServerConnected=${isServerConnected.value}")
                    Text(stringResource(R.string.registration_failed), textAlign = TextAlign.Center)
                }
            },
            confirmButton = {
                Log.d(TAG, "ServerRegistrationDialog confirmButton: isNetworkConnected=${isNetworkConnected.value}, isServerConnected=${isServerConnected.value}, shouldShowDialog=${shouldShowDialog.value}")
                if (isNetworkConnected.value && !isServerConnected.value) {
                    // Create retry button only if network is connected && server is NOT connected
                    Log.d(TAG, "ServerRegistrationDialog confirmButton: RetryButton is shown")
                    Button(
                        onClick = { shouldShowDialog.value = false
                            serverDetailsChanged.value = false
                        },
                        modifier = Modifier.fillMaxWidth(0.45f),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.LightGray,
                        ),
                    ) {
                        Text(text = retryText, fontSize = if(retryText.length!!<9) 15.sp else 13.sp, color = Color.Black)
                    }
                } else {
                    Log.d(TAG, "ServerRegistrationDialog confirmButton: RetryButton is NOT shown")
                }
            },

            dismissButton = {
                Button(
                    onClick = {
                        shouldShowDialog.value = false
                        serverDetailsChanged.value = false
                        navController.navigate("tak_server_screen") {
                            popUpTo(("tak_server_screen")) {
                                inclusive = true
                            }
                        }
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = if (isServerConnected.value) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.45f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                    )
                ) {
                    if (isServerConnected.value) {
                        Text(text = stringResource(R.string.okay))
                    } else {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            }
        )
    }
}

@Composable
fun ConfirmationDialog(
    shouldShowDialog: MutableState<Boolean>,
    navController: NavController,
    description: String,
    icon: ImageVector,
    confirmColor: Color,
    path: String,
    isConfirmed: MutableState<Boolean>
) {
    if (shouldShowDialog.value) {
        AlertDialog(
            containerColor = colors.background,
            modifier = Modifier.fillMaxSize(),
            onDismissRequest = {
                shouldShowDialog.value = false
            },
            icon = {
                Icon(
                    icon,
                    modifier = Modifier.size(25.dp),
                    contentDescription = null,
                    tint = Color.LightGray
                )
            },
            text = {
                Text(
                    description,
                    textAlign = TextAlign.Center,
                    fontSize = if (description.length > 40) 13.sp else 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        shouldShowDialog.value = false
                        isConfirmed.value = true
                    },
                    modifier = Modifier.fillMaxWidth(0.45f),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = confirmColor,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.confirm),
                        color = Color.Black
                    )
                }
            },

            dismissButton = {
                Button(
                    onClick = {
                        shouldShowDialog.value = false
                        isConfirmed.value = false
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.fillMaxWidth(0.45f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                    )
                ) {
                    Text(
                        text = stringResource(R.string.dismiss),
                    )
                }
            }
        )
    }
}
//Used for PhysioAlerts, Reporting Rates, and some User Metrics
@Composable
fun NumericEntryPage(
    navController: NavController,
    metricSettingState: MutableLiveData<Int>,
    maximum: Int,
    label: String,
    context: Context,
    route: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        var metricTextFieldValue by remember { mutableStateOf(TextFieldValue(metricSettingState.value.toString())) }
        //Log.d(TAG, "metricTextFieldValue=" + metricTextFieldValue.text)
        val focusManager = LocalFocusManager.current

        OutlinedTextField(
            value = metricTextFieldValue,
            onValueChange = {
                metricTextFieldValue = it
            },
            maxLines = 1,
            label = { Text(text = label, color = colors.primary) },
            placeholder = { Text(text = label, color = colors.primary) },
            textStyle = TextStyle(color = colors.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (metricTextFieldValue.text.toInt() > maximum) {
                        Toast.makeText(
                            mainActivity,
                            context.getString(R.string.value_exceeds_maximum_please_re_enter), Toast.LENGTH_LONG).show()
                    } else {
                        focusManager.clearFocus()
                    }
                }
            ),

            )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
                    navController.navigate(route) {
                        popUpTo((route)) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    metricSettingState.setValue(metricTextFieldValue.text.toInt())
                    Log.d(TAG, "Settings State saved with bpm threshold: ${metricSettingState.value}")
                    navController.navigate(route) {
                        popUpTo((route)) {
                            inclusive = true
                        }
                    }
                })
        }
    }
}
@Composable
fun ReportingStrategySettings(navController: NavController) {
    val enableDynamicReporting by enableDynamicReportingStrategySettingsState.observeAsState()

    WearTakCivScreenDimensions(
        columnContent = {
            Chip(
                onClick = {
                    navController.navigate("reporting_dynamic_constant_screen") {
                        popUpTo("reporting_dynamic_constant_screen") { inclusive = true }
                    }
                },
                enabled = true,
                label = { Text(text = "Reporting Strategy") },
                secondaryLabel = {
                    if (enableDynamicReporting == true) {
                        Text("Dynamic Reporting")
                    } else {
                        Text("Constant Reporting")
                    }
                },
                colors = ChipDefaults.chipColors(
                    backgroundColor = Color.DarkGray,
                    contentColor = Color.White,
                    secondaryContentColor = Color.LightGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp)
            )
            Spacer(modifier = Modifier.height(5.dp))

            if (enableDynamicReporting == true) {
                DataMetricsChip("stationary_reporting_interval_screen", "Stationary Reporting Interval", stationaryReportingIntervalSettingsState.value.toString(), navController)
                DataMetricsChip("on_foot_reporting_interval_screen", "On Foot Reporting Interval", onFootReportingIntervalSettingsState.value.toString(), navController)
                DataMetricsChip("vehicle_reporting_interval_screen", "Vehicle Reporting Interval", vehicleReportingIntervalSettingsState.value.toString(), navController)
                DataMetricsChip("while_alerting_reporting_interval_screen", "While Alerting Reporting Interval", whileAlertingReportingIntervalSettingsState.value.toString(), navController)

            } else {
                DataMetricsChip("constant_reporting_interval_screen", "Constant Reporting Interval", constantReportingIntervalSettingsState.value.toString(), navController)

            }

            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
                    enableDynamicReportingStrategySettingsState.postValue(enableDynamicReportingStrategy)
                    constantReportingIntervalSettingsState.setValue(constantReportingInterval)
                    stationaryReportingIntervalSettingsState.setValue(stationaryReportingInterval)
                    onFootReportingIntervalSettingsState.setValue(onFootReportingInterval)
                    vehicleReportingIntervalSettingsState.setValue(vehicleReportingInterval)
                    whileAlertingReportingIntervalSettingsState.setValue(whileAlertingReportingInterval)
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    constantReportingIntervalState.setValue(constantReportingIntervalSettingsState.value)
                    stationaryReportingIntervalState.setValue(stationaryReportingIntervalSettingsState.value)
                    onFootReportingIntervalState.setValue(onFootReportingIntervalSettingsState.value)
                    vehicleReportingIntervalState.setValue(vehicleReportingIntervalSettingsState.value)
                    whileAlertingReportingIntervalState.setValue(whileAlertingReportingIntervalSettingsState.value)

                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                })
        },
        text = "Reporting Settings"
    )
}

@Composable
fun ReportingStrategyDynamicConstantSetting (
    navController: NavController,
) {
    val enableDynamicReporting = enableDynamicReportingStrategySettingsState.value ?: true
    val radioOptions = listOf("Constant Reporting", "Dynamic Reporting")
    val initialValue = when {
        !enableDynamicReporting -> radioOptions[0]  // "Constant"
        else  -> radioOptions[1] // "Dynamic"
    }
    val selectedValue = remember { mutableStateOf(initialValue) }
    val isSelectedItem: (String) -> Boolean = { selectedValue.value == it }
    val onChangeState: (String) -> Unit = { selectedValue.value = it }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    //Log.d(TAG, "OptimizeBatteryonWifiSelectScreen called")
    WearTakCivScreenDimensions(
        columnContent = {
            Log.d(TAG, "initial  value = $initialValue, selected value: ${selectedValue.value}")
            radioOptions.forEach { text ->
                SelectableChip(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 5.dp),
                    selected = isSelectedItem(text),
                    onClick = { onChangeState(text)
                        if (text == "Constant Reporting") {
                            enableDynamicReportingStrategySettingsState.postValue(false)
                        } else {
                            enableDynamicReportingStrategySettingsState.postValue(true)
                        }
                    },
                    label = {
                        // The primary label should have a maximum 3 lines of text
                        Text(text, maxLines = 3, fontSize = 14.sp, overflow = TextOverflow.Ellipsis)
                    },
                    enabled = true,
                )

            }
            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Reporting Strategy Settings NOT saved")
                    enableDynamicReportingStrategySettingsState.postValue(enableDynamicReportingStrategyState.value)
                    navController.navigate("reporting_settings_screen") {
                        popUpTo(("reporting_settings_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    val prevSelection: Boolean = enableDynamicReportingStrategyState.value ?: true
                    val currSelection = enableDynamicReportingStrategySettingsState.value ?: true

                    if ((prevSelection != currSelection)) {
                        Log.d(TAG, "Changed Reporting Strategy: $currSelection")
                        enableDynamicReportingStrategyState.postValue(currSelection)
                    } else {
                        Log.d(TAG, "Settings NOT changed, NOT saved")
                    }

                    navController.navigate("reporting_settings_screen") {
                        popUpTo(("reporting_settings_screen")) {
                            inclusive = true
                        }
                    }

                })
        },
        text = "Reporting Strategy"
    )
}

//TODO: Can combine with physio alerts settings
@Composable
fun ReportingStrategyIntervalSettings(
    navController: NavController,
    intervalSettingState: MutableLiveData<Int>,
    label: String,
    placeholder: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        var intervalSettingTextFieldValue by remember {
            mutableStateOf(
                TextFieldValue(
                    intervalSettingState.value.toString() ?: "null"
                )
            )
        }

        OutlinedTextField(
            value = intervalSettingTextFieldValue,
            onValueChange = {
                intervalSettingTextFieldValue = it
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number),
            maxLines = 1,
            label = { Text(text = label, color = colors.primary, fontSize =  11.sp) },
            placeholder = { Text(text = placeholder, color = colors.primary) },
            textStyle = TextStyle(color = colors.primary)
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
                    navController.navigate("reporting_settings_screen") {
                        popUpTo(("reporting_settings_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    intervalSettingState.postValue(intervalSettingTextFieldValue.text.toInt())
                    Log.d(TAG, "Settings saved with reporting interval: ${intervalSettingState.value}")
                    navController.navigate("reporting_settings_screen") {
                        popUpTo(("reporting_settings_screen")) {
                            inclusive = true
                        }
                    }
                })
        }
    }
}


@Composable
fun LicenseKeyScreen(navController: NavController, licenseLiveDataState: MutableLiveData<String>, context: Context) {
    var offsetX by remember { mutableStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        var textField = GenericOutlinedTextField(licenseLiveDataState, stringResource(R.string.license_key), stringResource(R.string.license_key))
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            var settingsChanged: Boolean = false
            var licenseChanged: Boolean = false
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "LicenseKeyScreen Settings NOT saved")
                    settingsChanged = false; licenseChanged = false
                    if (isLicenseValid()) {
                        navController.navigate("settings_screen") {
                            popUpTo(("settings_screen")) {
                                inclusive = true
                            }
                        }
                    } else {
                        navController.navigate("invalid_license_screen")
                    }
                },
                onClickConfirm = {
                    val enteredLicense = textField.text
                    val currentLicense = licenseSettingsState.value
                    Log.d(
                        TAG, """
                            === === Licensing Information === ===
                            License Entered: $enteredLicense
                            License Current: $currentLicense
                            """.trimIndent()
                    )

                    if (enteredLicense != currentLicense) {
                        settingsChanged = true; licenseChanged = true
                        license = textField.text ?: license
                        Log.d(TAG, "License Changed. Updating DataRepository.")
                        CoroutineScope(Dispatchers.Main).launch {
                            licenseSettingsState.postValue(license)
                            licenseLiveDataState.postValue(license)
                            dataRepository.storeLicense(license)
                        }
                        requestCallsignComplicationUpdate(context)
                        requestHeartRateComplicationUpdate(context)
                        requestSosComplicationUpdate(context)
                        requestStatusIconsComplicationUpdate(context)
                        requestChatComplicationUpdate(context)
                        requestPhysioComplicationUpdate(context)
                        requestBloodhoundComplicationUpdate(context)

                        Log.d(TAG, "Sending License for Validation.")
                    } else {
                        Log.d(TAG, "License Did not Change.")
                    }
                    validateLicense(license)

                    if (isLicenseValid()) {
                        Log.d(
                            TAG, """
                        License is valid. Navigating to Settings Screen & Starting Application Services. 
                        =====================================
                        """.trimIndent()
                        )
                        navController.navigate("settings_screen") {
                            popUpTo(("invalid_license_screen")) {
                                inclusive = true
                            }
                        }
                    } else {
                        Log.d(
                            TAG, """
                            License is invalid. Navigating to Invalid License Screen & Stopping Services if started
                            =====================================
                            """.trimIndent()
                        )
                        navController.navigate("invalid_license_screen"){
                            popUpTo("settings_screen") { inclusive = true }
                        }
                    }
//                    startStopServicesLicense(context)
                }
            )
        }
    }
}

@Composable
fun InvalidLicenseScreen (navController: NavController) {
    WearTAKCIVTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.invalid_license_key),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(5.dp))
            //Maybe add info here for how to contact Ascent for the key?
            Text(
                text = stringResource(R.string.please_add_correct_key_to_use_weartak),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(10.dp))
            Button(
                onClick = { navController.navigate("license_setting_screen") {
                    popUpTo("license_setting_screen") { inclusive = true }
                } },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = Color.Black
                )
            ) {
                Text (
                    text = stringResource(R.string.update_license),
                    color = Color.Black,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun PhysiologicalAlertsSetting(navController: NavController) {
    Log.d(TAG, "PhysioAlertSetting called")
    val physiologicalAlertsCheckedLive by enablePhysiologicalAlertsSettingState.observeAsState()
    Log.d(TAG, "enablePhysiologicalAlertsSettingState=$enablePhysiologicalAlertsSettingState")

    WearTakCivScreenDimensions(
    columnContent = {
//        SettingSwitchToggle(
//            checked = physiologicalAlertsChecked, onCheckedChange = { physiologicalAlertsChecked = it },
//            title = stringResource(R.string.physiological_alerts),
//            description = stringResource(R.string.on_off),
//        )

        SettingSwitchToggleLiveData(
            physiologicalAlertsCheckedLive,
            { enablePhysiologicalAlertsSettingState.postValue(it) },
            {},
            stringResource(R.string.physiological_alerts),
            stringResource(R.string.on_off)
        )

        DataMetricsChip("physiological_alerts_bpm_screen", stringResource(R.string.threshold_high_heart_rate), hrHighAlertBpmThresholdSettingsState.value.toString(), navController)
        DataMetricsChip("physiological_alerts_bpm_length_screen", stringResource(R.string.threshold_high_heart_rate_length), hrHighAlertNumSecsThresholdSettingsState.value.toString() + " seconds", navController)

        Spacer(modifier = Modifier.height(16.dp))
    },
    rowContent = {
        ConfirmCancelButtons(
            onClickCancel = {
                Log.d(TAG, "PhysiologicalAlertsSetting Settings NOT saved")
                enablePhysiologicalAlertsSettingState.setValue(enablePhysiologicalAlerts)
                hrHighAlertBpmThresholdState.setValue(hrHighAlertBpmThreshold)
                hrHighAlertNumSecsThresholdState.setValue(hrHighAlertNumSecsThreshold)

                navController.navigate("settings_screen") {
                    popUpTo(("settings_screen")) {
                        inclusive = true
                    }
                }
            },
            onClickConfirm = {
                hrHighAlertBpmThresholdState.setValue(hrHighAlertBpmThresholdSettingsState.value)
                hrHighAlertNumSecsThresholdState.setValue(hrHighAlertNumSecsThresholdSettingsState.value)

                navController.navigate("settings_screen") {
                    popUpTo(("settings_screen")) {
                        inclusive = true
                    }
                }
            })
    },
    text = stringResource(R.string.physiological_alerts)
)
}

@Composable
fun DataMetricsChip(
    route: String,
    title: String,
    nameSecondary: String,
    navController: NavController
) {
    Chip(
        onClick = { navController.navigate(route) },
        enabled = true,
        label = { Text(text = title) },
        secondaryLabel = {
            Text(text = nameSecondary, maxLines =1, overflow = TextOverflow.Ellipsis)
        },
        colors = ChipDefaults.chipColors(
            backgroundColor = Color.DarkGray,
            contentColor = Color.White,
            secondaryContentColor = Color.LightGray
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 3.dp)
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HeightPickerSetting(navController: NavController){
    var selectedColumn by remember { mutableStateOf(0) }
    val textStyle = MaterialTheme.typography.display1
    val feetSettingsState by heightFeetSettingsState.observeAsState()
    val inchesSettingsState by heightInchesSettingsState.observeAsState()

    val feetState =
        rememberPickerState(initialNumberOfOptions = 8, initiallySelectedOption = feetSettingsState!!.toInt()-1)
    val feetContentDescription by remember {
        derivedStateOf { "${feetState.selectedOption + 1} feet" }
    }
    val inchesState =
        rememberPickerState(initialNumberOfOptions = 12, initiallySelectedOption = inchesSettingsState!!.toInt())
    val inchesContentDescription by remember {
        derivedStateOf { "${inchesState.selectedOption} inches" }
    }
    WearTakCivScreenDimensions(
        columnContent = {
            @Composable
            fun Option(column: Int, text: String) =
                Box(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = text,
                        style = textStyle,
                        color =
                            if (selectedColumn == column) MaterialTheme.colors.secondary
                            else MaterialTheme.colors.onBackground,
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .wrapContentSize()
                                .pointerInteropFilter {
                                    if (it.action == MotionEvent.ACTION_DOWN) selectedColumn =
                                        column
                                    true
                                },
                    )
                }

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Picker(
                    readOnly = selectedColumn != 0,
                    state = feetState,
                    modifier = Modifier.size(64.dp, 100.dp),
                    contentDescription = feetContentDescription,
                    option = { foot: Int -> Option(0, "%2d".format(foot + 1)) },
                )
                Spacer(Modifier.width(8.dp))
                Text(text = "'", style = textStyle, color = MaterialTheme.colors.onBackground)
                Spacer(Modifier.width(8.dp))
                Picker(
                    readOnly = selectedColumn != 1,
                    state = inchesState,
                    modifier = Modifier.size(64.dp, 100.dp),
                    contentDescription = inchesContentDescription,
                    option = { inch: Int -> Option(1, "%02d".format(inch)) },
                )
                Text(text = "''", style = textStyle, color = MaterialTheme.colors.onBackground)
            }
        },
        rowContent = {
            val feetIndex = feetInchesIndex(heightFeetSettingsState.value, feetState)
            val inchesIndex = feetInchesIndex(heightInchesSettingsState.value, inchesState)

            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "HeightPickerSetting Settings NOT saved")
                    navController.navigate("physiological_metrics_setting_screen") {
                        popUpTo(("physiological_metrics_setting_screen")) {
                            inclusive = true
                        }
                    } },
                onClickConfirm = {
                    heightFeetSettingsState.postValue(feetIndex+1)
                    heightInchesSettingsState.postValue(inchesIndex)
                    navController.navigate("physiological_metrics_setting_screen") {
                        popUpTo(("physiological_metrics_setting_screen")) {
                            inclusive = true
                        }
                    } })
        },
        text = stringResource(R.string.height)
    )
}

fun feetInchesIndex(dataName: Int?, picker: PickerState) : Int {
    if (null == dataName) {
        return 0
    }
    val index = picker.selectedOption
    if (index == -1) {
        return 0
    }
    return index
}

@Composable
fun SexBloodtypeUsertypeSetting (
    navController: NavController,
    metricSettingState: MutableLiveData<String>,
    dataList: List<String>,
    title: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = 20.dp, bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val index = findSexBloodtypeUsertypeIndex(metricSettingState.value, dataList)
        var selectedRadioIndex by remember { mutableStateOf(index) }
//TODO: DUPLICATE CODE - Same column and title credentials vvv
        //TODO: Convert to a lazyColumn
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp)
                .padding(bottom = 40.dp)
                .verticalScroll(state = rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                fontSize = if (title.length < 20) 15.sp else 13.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(vertical = 10.dp, horizontal = 20.dp)
                    .align(Alignment.CenterHorizontally)
            )
            //TODO: DUPLICATE CODE - Same column and title credentials ^^^

            dataList.forEachIndexed { index, dataItem ->
                run {
                    SelectableChip(
                        modifier = Modifier.fillMaxWidth(),
                        selected = index == selectedRadioIndex,
                        onClick = { selectedRadioIndex = index},
                        label = {
                            // The primary label should have a maximum 3 lines of text
                            Text(dataItem, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        },
                        enabled = true,
                        shape =
                            if (dataItem == dataList.first())
                                RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp)
                            else if (dataItem == dataList.last())
                                RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp)
                            else
                                RoundedCornerShape(0.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(15.dp))
        }
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "SexBloodtypeUsertypeSetting Settings NOT saved")
                    navController.navigate("physiological_metrics_setting_screen") {
                        popUpTo(("physiological_metrics_setting_screen")) {
                            inclusive = true
                        }
                    }                },
                onClickConfirm = {
                    metricSettingState.setValue(dataList[selectedRadioIndex])
                    Log.d(TAG, "Settings saved with Sex, Blood Type or User Type: ${metricSettingState.value}")
                    navController.navigate("physiological_metrics_setting_screen") {
                        popUpTo(("physiological_metrics_setting_screen")) {
                            inclusive = true
                        }
                    }
                })
        }
    }
}

fun findSexBloodtypeUsertypeIndex(dataName: String?, dataList: List<String>) : Int {
    if (null == dataName) {
        return 0
    }
    val index = dataList.indexOf(dataName)
    if (index == -1) {
        return 0
    }
    return index
}

@Composable
fun AllergySetting (
    navController: NavController,
    title: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = 20.dp, bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val storedAllergies by allergyListSettingsState.observeAsState(initial = emptyList())

        val initialIndices = remember(storedAllergies) {
            storedAllergies.mapNotNull { item ->
                allergiesOptionsList.indexOf(item.name)
                    .takeIf { it >= 0 }
            }
        }

        val selectedIndices = remember {
            // start with whatever LiveData says
            mutableStateListOf<Int>().apply { addAll(initialIndices) }
        }

        LaunchedEffect(storedAllergies) {
            selectedIndices.clear()
            selectedIndices.addAll(initialIndices)
        }
//TODO: DUPLICATE CODE - Same column and title credentials vvv
        //TODO: Convert to a lazyColumn
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp)
                .padding(bottom = 40.dp)
                .verticalScroll(state = rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                fontSize = if (title.length < 20) 15.sp else 13.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(vertical = 10.dp, horizontal = 20.dp)
                    .align(Alignment.CenterHorizontally)
            )
            //TODO: DUPLICATE CODE - Same column and title credentials ^^^

            allergiesOptionsList.forEachIndexed { index, dataItem ->
                run {
                    var checked = index in selectedIndices
                    ToggleChip(
                        modifier = Modifier.fillMaxWidth(),
                        checked = checked,
                        onCheckedChange = { checked ->
                            if (checked) selectedIndices += index
                            else selectedIndices -= index
                        },
                        toggleControl = { Checkbox(checked = checked, enabled = true,  onCheckedChange = null) },
                        label = {
                            // The primary label should have a maximum 3 lines of text
                            Text(dataItem, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        },
                        enabled = true,
                        shape =
                            if (dataItem == allergiesOptionsList.first())
                                RoundedCornerShape(20.dp, 20.dp, 0.dp, 0.dp)
                            else if (dataItem == allergiesOptionsList.last())
                                RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp)
                            else
                                RoundedCornerShape(0.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(15.dp))
        }
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Allergy Settings NOT saved")
                    navController.navigate("physiological_metrics_setting_screen") {
                        popUpTo(("physiological_metrics_setting_screen")) {
                            inclusive = true
                        }
                    }                },
                onClickConfirm = {
                    val toSave = selectedIndices
                        .map { idx -> DataRepository.AllergyItem(allergiesOptionsList[idx]) }
                    allergyListSettingsState.postValue(toSave)
                    navController.navigate("physiological_metrics_setting_screen") {
                        popUpTo(("physiological_metrics_setting_screen")) {
                            inclusive = true
                        }
                    }                })
        }
    }
}

@Composable
fun PhysiologicalMetricsSetting(navController: NavController) {
    Log.d(TAG, "PhysioMetricsSetting called")

    WearTakCivScreenDimensions(
    columnContent = {
        DataMetricsChip("physiological_metrics_age_screen", stringResource(R.string.age), ageSettingsState.value.toString(), navController)
        DataMetricsChip("physiological_metrics_height_screen", stringResource(R.string.height), heightFeetSettingsState.value.toString() + "' " + heightInchesSettingsState.value.toString() + "''", navController)
        DataMetricsChip("physiological_metrics_weight_screen", stringResource(R.string.weight), weightSettingsState.value.toString() + " lbs", navController)
        DataMetricsChip("physiological_metrics_sex_screen", stringResource(R.string.sex), sexSettingsState.value.toString(), navController)
        DataMetricsChip("physiological_metrics_bloodtype_screen", stringResource(R.string.blood_type), bloodTypeSettingsState.value.toString(), navController)
        DataMetricsChip("physiological_metrics_allergies_screen", stringResource(R.string.allergies), allergyListSettingsState.value.takeIf { it!!.isNotEmpty() }?.joinToString(", ") { it.name } ?: "None", navController)
        DataMetricsChip("physiological_metrics_usertype_screen", stringResource(R.string.user_type), userTypeSettingsState.value.toString(), navController)
        DataMetricsChip("physiological_metrics_resting_hr_screen", stringResource(R.string.resting_heart_rate), restingHeartRateSettingsState.value.toString(), navController)

        Spacer(modifier = Modifier.height(16.dp))
    },
    rowContent = {
        ConfirmCancelButtons(
            onClickCancel = {
                Log.d(TAG, "Physiological Settings NOT saved")
                ageState.setValue(ageValue)
                heightFeetState.setValue(heightFeetValue)
                heightInchesState.setValue(heightInchesValue)
                weightState.setValue(weightValue)
                sexState.setValue(sexValue)
                bloodTypeState.setValue(bloodTypeValue)
//                allergyListState.postValue(allergyValue)
                userTypeState.setValue(userTypeValue)
                restingHeartRateState.setValue(restingHeartRate)
                navController.navigate("settings_screen") {
                    popUpTo(("settings_screen")) {
                        inclusive = true
                    }
                }
            },
            onClickConfirm = {
                        ageState.setValue(ageSettingsState.value)
                        heightFeetState.setValue(heightFeetSettingsState.value)
                        heightInchesState.setValue(heightInchesSettingsState.value)
                        weightState.setValue(weightSettingsState.value)
                        sexState.setValue(sexSettingsState.value)
                        bloodTypeState.setValue(bloodTypeSettingsState.value)
                        allergyListState.setValue(allergyListSettingsState.value)
                        userTypeState.setValue(userTypeSettingsState.value)
                        restingHeartRateState.setValue(restingHeartRateSettingsState.value)

                Log.d(TAG, "Finished saving physio state settings")

                navController.navigate("settings_screen") {
                    popUpTo(("settings_screen")) {
                        inclusive = true
                    }
                }
            })
    },
    text = stringResource(R.string.physiological_user_metrics)
)
}

@Composable
fun HailTitleText(text: String, canChangeColor: Boolean, status: HailLicenseStates?){
    Text(text, modifier = Modifier.padding(top = 15.dp, bottom = 5.dp), textAlign = TextAlign.Center,
        color =
            if (canChangeColor)
                if (status == HailLicenseStates.SOFT_EXPIRED) Color.Yellow
                else if (status == HailLicenseStates.EXPIRED) Color.Red
                else Color.White
            else Color.White)
}
@Composable
fun HailDataText(text: String, canChangeColor: Boolean, status: HailLicenseStates?){
    Text(text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        color =
            if (canChangeColor)
                if (status == HailLicenseStates.SOFT_EXPIRED) Color.Yellow
                else if (status == HailLicenseStates.EXPIRED) Color.Red
                else Color.White
            else Color.White)
}

@Composable
fun hailLicenseStatusTextFromString(status: HailLicenseStates?): String {
    val statusString: String = when (status) {
        HailLicenseStates.VALID -> "License Valid"
        HailLicenseStates.CHECKING -> "Checking License"
        HailLicenseStates.SOFT_EXPIRED -> "Expiring Soon"
        HailLicenseStates.EXPIRED -> "License Expired"
        HailLicenseStates.INVALID -> "License Invalid"
        else -> stringResource(R.string.error)
    }
    return statusString
}


@Composable
fun DateDisplay(serverDateString: String, status: HailLicenseStates?) {
    // Parse the incoming date (pattern matches "Tue Sep 30 00:00:00 CDT 2025")
    if (serverDateString != "null") {
        val parser = java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.ENGLISH)
    val parsedDate = parser.parse(serverDateString)!!

    // Convert java.util.Date to LocalDate
    val localDate = parsedDate.toInstant()
        .atZone(ZoneId.systemDefault())
        .toLocalDate()

    // Format as "9/30/2025"
    val displayDate = localDate.format(DateTimeFormatter.ofPattern("M/d/yyyy"))

    // Calculate days difference
    val today = LocalDate.now()
    val daysDiff = ChronoUnit.DAYS.between(today, localDate)
    val daysDisplay = "$daysDiff Days"

    // Display in Compose
        HailDataText(text = displayDate, true, status)
        if (status != HailLicenseStates.EXPIRED)
            HailDataText(text = daysDisplay, true, status)
    } else {
        HailDataText(text = stringResource(R.string.error), true, status)
    }
}

@Composable
fun HailSetting(navController: NavController) {
    Log.d(TAG, "HailSetting called")
    val info = dataRepository.hailLicenseInfoFlow.collectAsState(initial = null).value
    val hailLicenseState = dataRepository.hailLicenseState.collectAsState().value

    WearTakCivScreenDimensions(
        columnContent = {
            Text(
                "Status:",
                modifier = Modifier.padding(bottom = 5.dp),
                textAlign = TextAlign.Center,
                color = if (hailLicenseState == HailLicenseStates.SOFT_EXPIRED) Color.Yellow
                        else if (hailLicenseState == HailLicenseStates.EXPIRED) Color.Red
                        else Color.White
            )

            HailDataText(hailLicenseStatusTextFromString(hailLicenseState), true, hailLicenseState)

            if (hailLicenseState == HailLicenseStates.EXPIRED || hailLicenseState == HailLicenseStates.SOFT_EXPIRED) {
                Text(
                    if (hailLicenseState == HailLicenseStates.EXPIRED) "Contact support to renew license" else "Contact support to extend license",
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp
                )
            }
            HailTitleText(
                if (hailLicenseState == HailLicenseStates.EXPIRED) "License Expired:" else "License Expires:",
                true, hailLicenseState)
            DateDisplay(info?.expirationDate.toString(), hailLicenseState)

            HailTitleText("Account Number:", false, hailLicenseState)
            HailDataText(info?.accountNumber.toString(), false, hailLicenseState)

            HailTitleText("License Identification:", false, hailLicenseState)
            HailDataText(info?.licenseId.toString(), false, hailLicenseState)

            HailTitleText("Device Identification:", false, hailLicenseState)
            HailDataText(info?.deviceId.toString(), false, hailLicenseState)

            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            BackButton(
                onClick = {
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    } },
            )
        },
        text = "HAIL Settings"
    )
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun InvalidHailScreen(navController: NavController) {
    val info = dataRepository.hailLicenseInfoFlow.collectAsState(initial = null).value // <-- observe backend license information changes
    val hailLicenseState = dataRepository.hailLicenseState.collectAsState().value // <-- observe backend license state changes
    val buttonPressed = dataRepository.hailCheckLicenseButtonPressed.collectAsState().value  // <-- observe button clicks
    val scope = rememberCoroutineScope()
    val showLoadingDialog = remember { mutableStateOf(false) } // single, stable state object for the dialog

    // When user presses "Register", show loading while we await state updates
    LaunchedEffect(buttonPressed) {
        if (buttonPressed == true) showLoadingDialog.value = true
    }

    // React to license state transitions
    LaunchedEffect(hailLicenseState) {
        when (hailLicenseState) {
            HailLicenseStates.CHECKING -> showLoadingDialog.value = true
            HailLicenseStates.INVALID  -> showLoadingDialog.value = false   // stay on screen
            HailLicenseStates.VALID,
            HailLicenseStates.SOFT_EXPIRED -> {
                showLoadingDialog.value = false
                navController.navigate("hail_setting_screen") {
                    popUpTo("settings_screen") { inclusive = true }
                }
            }
            HailLicenseStates.EXPIRED -> showLoadingDialog.value = false // show message, stay
        }
    }

    WearTAKCIVTheme {
        Column(
            modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Activate Hail",
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(vertical = 10.dp, horizontal = 20.dp)
                    .align(Alignment.CenterHorizontally)
            )
            Text(
                text = "Pairing Code:",
                textAlign = TextAlign.Center,
            )
            Text(
                text = info?.pairingCode.toString(),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                fontSize = 25.sp,
                modifier = Modifier
                    .padding(vertical = 7.dp)
                    .align(Alignment.CenterHorizontally)
                )
            Text(
                text = "Enter the code on your HAIL license portal, then press Register below",
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                )
            Spacer(modifier = Modifier.size(5.dp))
            Row(
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center) {
                Button(
                    onClick = {
                        navController.navigate("settings_screen") {
                            popUpTo("settings_screen") { inclusive = true }
                        }
                    },
                    contentPadding = PaddingValues(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                    )
                ) {
                    Text(
                        text = stringResource(R.string.dismiss),
                    )
                }

                Spacer(modifier = Modifier.size(10.dp))

                Button(
                    onClick = {
                        scope.launch {
                            dataRepository.hailCheckLicenseButtonPressed.emit(true)
                        }
                    },
                    contentPadding = PaddingValues(10.dp),
                    colors = ButtonDefaults.buttonColors(colors.primary),
                ) {
                    Text(
                        text = "Register",
                        color = Color.Black
                    )
                }
            }
        }
        // Render the dialog ONCE, using the remembered state (no inline mutableStateOf(true))
        LoadingDialog(shouldShowDialog = showLoadingDialog, secondaryText = "Checking License Status")
    }
}

fun displayHailLoadingDialog(
    state: HailLicenseStates?,
    shouldShowLoadingDialog: MutableState<Boolean>,
    navController: NavController) {
    Log.d(TAG, "$state")
    if (state == HailLicenseStates.CHECKING){
        shouldShowLoadingDialog.value = true
    } else if (state == HailLicenseStates.INVALID){
        shouldShowLoadingDialog.value = false
    } else {
        shouldShowLoadingDialog.value = false
        navController.navigate("hail_setting_screen") {
            popUpTo("settings_screen") { inclusive = true }
        }
    }
    Log.d(TAG, state.toString())
}

@Composable
fun SettingSwitchToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String,
    enabled: Boolean = true,
) {
    // The primary label should have a maximum 3 lines of text
    // and the secondary label should have max 2 lines of text.
    ToggleChip(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        label = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = {
            Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        checked = checked,
        // For Switch  toggle controls the Wear Material UX guidance is to set the
        // unselected toggle control color to ToggleChipDefaults.switchUncheckedIconColor()
        // rather than the default.
        colors =
        ToggleChipDefaults.toggleChipColors(
            uncheckedToggleControlColor = ToggleChipDefaults.SwitchUncheckedIconColor
        ),
        toggleControl = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        },
        onCheckedChange = onCheckedChange,
        enabled = true,
    )
}
@Composable
fun SettingSwitchToggleLiveData(
    checkedLive: Boolean?,
    onCheckedChange: (Boolean) -> Unit,
    appIcon: @Composable () -> Unit,
    title: String,
    description: String,
) {
    // The primary label should have a maximum 3 lines of text
    // and the secondary label should have max 2 lines of text.
    ToggleChip(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        label = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = {
            Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        appIcon = {appIcon()},
        checked = checkedLive == true,
        // For Switch  toggle controls the Wear Material UX guidance is to set the
        // unselected toggle control color to ToggleChipDefaults.switchUncheckedIconColor()
        // rather than the default.
        colors =
            ToggleChipDefaults.toggleChipColors(
                uncheckedToggleControlColor = ToggleChipDefaults.SwitchUncheckedIconColor
            ),
        toggleControl = {
            Switch(
                checked = checkedLive == true,
                onCheckedChange = null,
                enabled = true,
            )
        },
        onCheckedChange = onCheckedChange,
        enabled = true,
    )
}

//Contains functionality for settings screen
@Composable
fun SettingsScreen(
    navController: NavController,
    callsignLiveDataState: MutableLiveData<String>,
    licenseLiveDataState: MutableLiveData<String>,
    context: Context
) {
    val locationCheckedLive by enableLocationServiceSettingsState.observeAsState()
    val dittoCheckedLive by enableDittoServiceSettingsState.observeAsState()
    val chatCheckedLive by enableChatServiceSettingsState.observeAsState()
    val enableSmsCheckedLive by enableSmsForAlertsSettingsState.observeAsState()
    var smsListTextFieldValue by remember { mutableStateOf(TextFieldValue(smsNumbersListString)) }
    val physioCheckedLive by enablePhysiologicalServicesSettingsState.observeAsState()
    val hailCheckedLive by enableHailServiceSettingsState.observeAsState()
    val hailLicenseState = dataRepository.hailLicenseState.collectAsState(initial = null).value
    val bloodhoundCompassCheckedLive by enableBloodhoundCompassServiceSettingsState.observeAsState()

    Log.d(TAG, "Hail License State: $hailLicenseState")

    val displayedSecondaryText by saveBattOnWifiStringState.observeAsState()
    //Log.d(TAG, "SettingsScreen called")
    WearTakCivScreenDimensions(
        columnContent = {
            CallsignRoleTeamChip(
                "callsign_screen",
                stringResource(R.string.callsign),
                callsignSettingsState,
                navController
            )
            CallsignRoleTeamChip("team_screen",
                stringResource(R.string.team), teamSettingsState, navController)
            CallsignRoleTeamChip("role_screen",
                stringResource(R.string.role), roleSettingsState, navController)

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.connection_settings),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.padding(7.dp)
            )

            Chip(
                onClick = {
                    takServerListState.postValue(takServerList)
                    navController.navigate("tak_server_screen") },
                enabled = true,
                label = { Text(text = stringResource(R.string.tak_servers)) },
                secondaryLabel = {
//                        Text(text = name)
                },
                colors = ChipDefaults.chipColors(
                    backgroundColor = Color.DarkGray,
                    contentColor = Color.White,
                    secondaryContentColor = Color.LightGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp)
            )

            Chip(
                onClick = {
                    navController.navigate("reporting_settings_screen") {
                        popUpTo("reporting_settings_screen") { inclusive = true }
                    }
                },
                enabled = true,
                label = { Text(text = "Reporting Settings") },
                colors = ChipDefaults.chipColors(
                    backgroundColor = Color.DarkGray,
                    contentColor = Color.White,
                    secondaryContentColor = Color.LightGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp)
            )

            Chip(
                onClick = {
                    enableWifiBatteryOptimizationSettingsState.postValue(enableWifiBatteryOptimizationState.value)
                    enableAllWifiBatteryOptimizationSettingsState.postValue(enableAllWifiBatteryOptimizationState.value)
                    wifiSsidListSettingsState.postValue(wifiSsidListState.value)
                    navController.navigate("optimize_battery_on_wifi_screen") {
                        popUpTo("optimize_battery_on_wifi_screen") { inclusive = true }
                    } },
                enabled = true,
                label = { Text(text = stringResource(R.string.save_battery_on_wifi)) },
                secondaryLabel = {Text(
                    text = if (displayedSecondaryText == ALL_WIFI_NETWORKS) stringResource(R.string.all_wifi_connections)
                    else if (displayedSecondaryText == SOME_WIFI_NETWORKS) stringResource(R.string.some_wifi_connections)
                    else stringResource(R.string.no_wifi_connections)
                )
                },
                colors = ChipDefaults.chipColors(
                    backgroundColor = Color.DarkGray,
                    contentColor = Color.White,
                    secondaryContentColor = Color.LightGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp)
            )

            val licenseText: String? by licenseSettingsState.observeAsState()

            Chip(
                onClick = { navController.navigate("license_setting_screen") {
                    popUpTo("license_setting_screen") { inclusive = true }
                } },
                enabled = true,
                label = { Text(text = stringResource(R.string.license_key)) },
                secondaryLabel = {
                    Text(text = licenseText.toString(), maxLines =1, overflow = TextOverflow.Ellipsis)
                },
                colors = ChipDefaults.chipColors(
                    backgroundColor = Color.DarkGray,
                    contentColor = Color.White,
                    secondaryContentColor = Color.LightGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.general_settings),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(7.dp)
            )

            SettingSwitchToggleLiveData(
                locationCheckedLive,
                { enableLocationServiceSettingsState.postValue(it) },
                {},
                stringResource(R.string.location_service),
                stringResource(R.string.service_on_off)
            )

            SettingSwitchToggleLiveData(
                dittoCheckedLive,
                { enableDittoServiceSettingsState.postValue(it) },
                {},
                stringResource(R.string.ditto),
                stringResource(R.string.service_on_off)
            )

            SettingSwitchToggleLiveData(
                chatCheckedLive,
                { enableChatServiceSettingsState.postValue(it) },
                {},
                stringResource(R.string.chat),
                stringResource(R.string.service_on_off)
            )

            SettingSwitchToggleLiveData(
                bloodhoundCompassCheckedLive,
                { enableBloodhoundCompassServiceSettingsState.postValue(it) },
                {},
                "Bloodhound & Compass",
                stringResource(R.string.service_on_off)
            )

            SettingSwitchToggleLiveData(
                enableSmsCheckedLive,
                { enableSmsForAlertsSettingsState.postValue(it) },
                {},
                "SMS Alerts",
                stringResource(R.string.on_off)
            )
                val focusManager = LocalFocusManager.current

                if (enableSmsForAlertsSettingsState.value == true) {
                    OutlinedTextField(
                        value = smsListTextFieldValue,
                        onValueChange = {
                            smsListTextFieldValue = it
                            smsNumbersListStringSettingsState.postValue(smsListTextFieldValue.text)
                        },
                        modifier = Modifier.padding(5.dp),
                        maxLines = 3,
                        label = {
                            Text(
                                text = stringResource(R.string.sms_recipients),
                                color = colors.primary,
                                fontSize = 12.sp
                            )
                        },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.sms_recipients),
                                color = colors.primary
                            )
                        },
                        textStyle = TextStyle(color = colors.primary),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (smsListTextFieldValue.text.contains("[A-Za-z!\"#$%&'()*./:;\\\\<=>?@^_`{|}~]".toRegex())) {
                                    Toast.makeText(
                                        mainActivity,
                                        context.getString(R.string.invalid_characters),
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    focusManager.clearFocus()
                                }
                            }
                        )
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.physiological_settings),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(7.dp)
            )
            SettingSwitchToggleLiveData(
                physioCheckedLive,
                { enablePhysiologicalServicesSettingsState.postValue(it) },
                {},
                stringResource(R.string.physiological_monitoring),
                stringResource(R.string.service_on_off)
            )
            SettingSwitchToggleLiveData(
                hailCheckedLive,
                { enableHailServiceSettingsState.postValue(it) },
                { Icon(
                    painter = painterResource(id = R.drawable.hail_icon),
                    tint = Color.Unspecified,
                    contentDescription = "null",
                    modifier = Modifier
                        .size(20.dp),
                ) },
                "HAIL",
                stringResource(R.string.service_on_off)
            )
            if (enableHailServiceSettingsState.value == true){
                DataMetricsChip(
                    if (hailLicenseState == HailLicenseStates.INVALID) {
                        "hail_pairing_screen"
                    } else {
                        "hail_setting_screen"
                    },
                    "HAIL Settings", hailLicenseStatusTextFromString(hailLicenseState), navController)
            }
            Chip(
                onClick = {
                    hrHighAlertBpmThresholdSettingsState.postValue(hrHighAlertBpmThresholdState.value)
                    hrHighAlertNumSecsThresholdSettingsState.postValue(hrHighAlertNumSecsThresholdState.value)
                    navController.navigate("physiological_alerts_setting_screen") {
                    popUpTo("physiological_alerts_setting_screen") { inclusive = true }
                } },
                enabled = true,
                label = { Text(text = stringResource(R.string.physiological_alerts)) },
                colors = ChipDefaults.chipColors(
                    backgroundColor = Color.DarkGray,
                    contentColor = Color.White,
                    secondaryContentColor = Color.LightGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp)
            )

            Chip(
                    onClick = {
                        ageSettingsState.postValue(ageState.value)
                        heightFeetSettingsState.postValue(heightFeetState.value)
                        heightInchesSettingsState.postValue(heightInchesState.value)
                        weightSettingsState.postValue(weightState.value)
                        sexSettingsState.postValue(sexState.value)
                        bloodTypeSettingsState.postValue(bloodTypeState.value)
                        allergyListSettingsState.postValue(allergyListState.value)
                        userTypeSettingsState.postValue(userTypeState.value)
                        restingHeartRateSettingsState.postValue(restingHeartRateState.value)

                        navController.navigate("physiological_metrics_setting_screen") {
                            popUpTo("physiological_metrics_setting_screen") { inclusive = true }
                        } },
                    enabled = true,
                    label = { Text(text = stringResource(R.string.physiological_user_metrics)) },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray,
                        contentColor = Color.White,
                        secondaryContentColor = Color.LightGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                modifier = Modifier.padding(top = 7.dp),
                text = stringResource(R.string.version, versionNumber),
                fontSize = 11.sp,
                color = colors.primary,
            )

            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "SettingsScreen Settings NOT saved")
//                        navController.navigate("main_screen") {
//                            popUpTo(("main_screen")) {
//                                inclusive = true
//                            }
//                        }
                    loadSettingsValues()

                    settingsActivity.finish()
                    context.startActivity(Intent(context, MainActivity::class.java))
                                },
                onClickConfirm = {
                    var settingsChanged: Boolean = false
                    var callsignChanged: Boolean = false
                    var teamChanged = false
                    var roleChanged = false
                    var categoryChanged = false
                    var licenseChanged: Boolean = false
                    val maxReportRate: Int = 100000000
                    val minReportRate: Int = 1
                    var locationCheckedChanged: Boolean = false
                    var physioCheckedChanged: Boolean = false
                    var hailCheckedChanged: Boolean = false
                    var dittoCheckedChanged: Boolean = false
                    var chatCheckedChanged: Boolean = false
                    var bloodhoundCompassCheckedChanged: Boolean = false
                    var dynamicReportingChanged: Boolean = false
                    var constantReportingIntervalChanged: Boolean = false
                    var stationaryReportingIntervalChanged: Boolean = false
                    var onFootReportingIntervalChanged: Boolean = false
                    var vehicleReportingIntervalChanged: Boolean = false
                    var whileAlertingReportingIntervalChanged: Boolean = false
                    var smsAlertsCheckedChanged: Boolean = false
                    var smsNumbersChanged: Boolean = false
                    var physiologicalAlertsCheckedChanged: Boolean = false
                    var physiologicalAlertsBpmThresholdCheckedChanged: Boolean = false
                    var physiologicalAlertsBpmThresholdTimeCheckedChanged: Boolean = false
                    var saveBattOnWifiChanged = false
                    var takServerListChanged = false
                    var ageChanged: Boolean = false
                    var heightFeetChanged: Boolean = false
                    var heightInchesChanged: Boolean = false
                    var weightChanged: Boolean = false
                    var sexChanged: Boolean = false
                    var bloodTypeChanged: Boolean = false
                    var allergiesChanged: Boolean = false
                    var userTypeChanged: Boolean = false
                    var restingHeartRateChanged: Boolean = false

                    if (hrHighAlertBpmThreshold != hrHighAlertBpmThresholdState.value) {
                        settingsChanged = true; physiologicalAlertsBpmThresholdCheckedChanged = true
                        hrHighAlertBpmThreshold = hrHighAlertBpmThresholdState.value ?: hrHighAlertBpmThreshold
                    }
                    if (hrHighAlertNumSecsThreshold != hrHighAlertNumSecsThresholdSettingsState.value) {
                        settingsChanged = true; physiologicalAlertsBpmThresholdTimeCheckedChanged = true
                        hrHighAlertNumSecsThreshold = hrHighAlertNumSecsThresholdState.value ?: hrHighAlertNumSecsThreshold
                    }

                    if (ageValue != ageState.value) {
                        settingsChanged = true; ageChanged = true
                        ageValue = ageState.value ?: ageValue
                    }
                    if (heightFeetValue != heightFeetState.value) {
                        settingsChanged = true; heightFeetChanged = true
                        heightFeetValue = heightFeetState.value ?: heightFeetValue
                    }
                    if (heightInchesValue != heightInchesState.value) {
                        settingsChanged = true; heightInchesChanged = true
                        heightInchesValue = heightInchesState.value ?: heightInchesValue
                    }
                    if (weightValue != weightState.value) {
                        settingsChanged = true; weightChanged = true
                        weightValue = weightState.value ?: weightValue
                    }
                    if (sexValue != sexState.value) {
                        settingsChanged = true; sexChanged = true
                        sexValue = sexState.value ?: sexValue
                    }
                    if (bloodTypeValue != bloodTypeState.value) {
                        settingsChanged = true; bloodTypeChanged = true
                        bloodTypeValue = bloodTypeState.value ?: bloodTypeValue
                    }
                    if (allergyList != allergyListState.value) {
                        settingsChanged = true; allergiesChanged = true
                        allergyList = allergyListState.value ?: allergyList
                    }
                    if (userTypeValue != userTypeState.value) {
                        settingsChanged = true; userTypeChanged = true
                        userTypeValue = userTypeState.value ?: userTypeValue
                    }
                    if (restingHeartRate != restingHeartRateState.value) {
                        settingsChanged = true; restingHeartRateChanged = true
                        restingHeartRate = restingHeartRateState.value ?: restingHeartRate
                    }

                    if (enableDynamicReportingStrategy != enableDynamicReportingStrategySettingsState.value) {
                        settingsChanged = true; dynamicReportingChanged = true
                        enableDynamicReportingStrategy = enableDynamicReportingStrategySettingsState.value ?: enableDynamicReportingStrategy
                        runBlocking {
                            enableDynamicReportingStrategySettingsState.postValue(enableDynamicReportingStrategy)
                        }
                    }

                    if (constantReportingInterval != constantReportingIntervalState.value) {
                        settingsChanged = true; constantReportingIntervalChanged = true
                        constantReportingInterval = constantReportingIntervalState.value ?: constantReportingInterval
                    }

                    if (stationaryReportingInterval != stationaryReportingIntervalState.value) {
                        settingsChanged = true; stationaryReportingIntervalChanged = true
                        stationaryReportingInterval = stationaryReportingIntervalState.value ?: stationaryReportingInterval
                    }

                    if (onFootReportingInterval != onFootReportingIntervalState.value) {
                        settingsChanged = true; onFootReportingIntervalChanged = true
                        onFootReportingInterval = onFootReportingIntervalState.value ?: onFootReportingInterval
                    }

                    if (vehicleReportingInterval != vehicleReportingIntervalState.value) {
                        settingsChanged = true; vehicleReportingIntervalChanged = true
                        vehicleReportingInterval = vehicleReportingIntervalState.value ?: vehicleReportingInterval
                    }

                    if (whileAlertingReportingInterval != whileAlertingReportingIntervalState.value) {
                        settingsChanged = true; whileAlertingReportingIntervalChanged = true
                        whileAlertingReportingInterval = whileAlertingReportingIntervalState.value ?: whileAlertingReportingInterval
                    }

                    if (!callsign.equals(callsignSettingsState.value)) {
                        settingsChanged = true; callsignChanged = true
                        callsign = callsignSettingsState.value ?: callsign
                        requestCallsignComplicationUpdate(context)

                        runBlocking {
                            callsignLiveDataState.postValue(callsign)
                        }
                        EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))
                    }
                    if (!team.equals(teamSettingsState.value)) {
                        settingsChanged = true; teamChanged = true
                        team = teamSettingsState.value ?: team
                        var teamIndex = teamEntriesValuesList.ltEntries.indexOf(team)
                        // This cannot happen, but still...
                        if (teamIndex == -1) {
                            Log.e(TAG, "ERROR SettingsScreen: teamIndex is -1. Setting team entry and value to that of index 0")
                            teamIndex = 0
                            team = teamEntriesValuesList.ltEntries[0]
                            teamSettingsState.postValue(team)
                        }
                        teamValue = teamEntriesValuesList.ltValues[teamIndex]
                        runBlocking {
                            teamState.postValue(team)
                        }
                        EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))
                    }
                    if (!role.equals(roleSettingsState.value)) {
                        settingsChanged = true; roleChanged = true
                        role = roleSettingsState.value ?: role
//                        var roleIndex = roleEntriesValuesList.lrEntries.indexOf(role)
//                        // This cannot happen, but still...
//                        if (roleIndex == -1) {
//                            Log.e(TAG, "ERROR SettingsScreen: roleIndex is -1. Setting role entry and value to that of index 0")
//                            roleIndex = 0
//                            role = roleEntriesValuesList.lrEntries[0]
//                            roleSettingsState.postValue(role)
//                        }
//                        roleValue = roleEntriesValuesList.lrValues[roleIndex]
                        runBlocking {
                            roleState.postValue(role)
                        }
                        EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))
                    }
                    if (!category.equals(categorySettingsState.value)) {
                        settingsChanged = true; categoryChanged = true; roleChanged = true
                        role = roleSettingsState.value ?: role
                        category = categorySettingsState.value ?: category

                        runBlocking {
                            roleState.postValue(role)
                        }
                        EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))
                    }

                    if ((saveBattOnWifiString != saveBattOnWifiStringState.value) || (wifiSsidList != wifiSsidListState.value)) {
                        settingsChanged = true; saveBattOnWifiChanged = true
                        wifiSsidList = wifiSsidListState.value ?: wifiSsidList
                        saveBattOnWifiString = saveBattOnWifiStringState.value ?: saveBattOnWifiString
                        enableWifiBatteryOptimization = enableWifiBatteryOptimizationState.value ?: enableWifiBatteryOptimization
                        enableAllWifiBatteryOptimization = enableAllWifiBatteryOptimizationState.value ?: enableAllWifiBatteryOptimization
                    }
//                            if (!license.equals(licenseSettingsState.value)) {
//                                settingsChanged = true; licenseChanged = true
//                                license = licenseSettingsState.value ?: license
//                                licenseLiveDataState.postValue(license)
//                            }

                    if (takServerList != takServerListState.value) {
                        settingsChanged = true; takServerListChanged = true
                        takServerList = takServerListState.value ?: takServerList
                    }
                    Log.d(TAG, "takServerList: $takServerList")
                    Log.d(TAG, "takServerListState: " + takServerListState.value)
                    Log.d(TAG, "takServerListChanged: $takServerListChanged")

                    if (enableLocationService != enableLocationServiceSettingsState.value) {
                        settingsChanged = true; locationCheckedChanged = true
                        enableLocationService = enableLocationServiceSettingsState.value ?: enableLocationService
                        requestStatusIconsComplicationUpdate(context)
                        runBlocking {
                            // This is in case enableLocationService above is NOT set to enableLocationServiceSettingsState.value
                            enableLocationServiceSettingsState.postValue(enableLocationService)
                        }
                    }

                    if (enableDittoService != enableDittoServiceSettingsState.value) {
                        settingsChanged = true; dittoCheckedChanged = true
                        enableDittoService = enableDittoServiceSettingsState.value ?: enableDittoService
                        runBlocking {
                            enableDittoServiceSettingsState.postValue(enableDittoService)
                        }
                    }

                    if (enableChatService != enableChatServiceSettingsState.value) {
                        settingsChanged = true; chatCheckedChanged = true
                        enableChatService = enableChatServiceSettingsState.value ?: enableChatService
                        runBlocking {
                            enableChatServiceSettingsState.postValue(enableChatService)
                        }
                        //restartTAKConnectService()
                        //TODO: Find out if we actually need to restart the tak connect service (manager should handle)
                        requestChatComplicationUpdate(context)
                        requestBloodhoundComplicationUpdate(context)

                    }

                    if (enableBloodhoundCompassService != enableBloodhoundCompassServiceSettingsState.value) {
                        settingsChanged = true; bloodhoundCompassCheckedChanged = true
                        enableBloodhoundCompassService = enableBloodhoundCompassServiceSettingsState.value ?: enableBloodhoundCompassService
                        runBlocking {
                            enableBloodhoundCompassServiceSettingsState.postValue(enableBloodhoundCompassService)
                        }
                    }

                    if (enableHailService != enableHailServiceSettingsState.value) {
                        settingsChanged = true; hailCheckedChanged = true
                        enableHailService = enableHailServiceSettingsState.value ?: enableHailService
                        runBlocking {
                            // This is in case enableLocationService above is NOT set to enableLocationServiceSettingsState.value
                            enableHailServiceSettingsState.postValue(enableHailService)
                        }
                    }

                    if (enableSmsForAlerts != enableSmsForAlertsSettingsState.value) {
                        settingsChanged = true; smsAlertsCheckedChanged = true
                        enableSmsForAlerts = enableSmsForAlertsSettingsState.value ?: enableSmsForAlerts
                        if (enableSmsForAlerts) {
                            Log.d(TAG, "Trying enable start sms alerts")
                        } else {
                            Log.d(TAG, "Trying disable start sms alerts")
                        }
                        runBlocking {
                            enableSmsForAlertsSettingsState.postValue(enableSmsForAlerts)
                        }
                    }

                    if (smsNumbersListString != smsNumbersListStringSettingsState.value) {
                        settingsChanged = true; smsNumbersChanged = true
                        smsNumbersListString =
                            smsNumbersListStringSettingsState.value ?: smsNumbersListString
                    }

                    if (enablePhysiologicalServices != enablePhysiologicalServicesSettingsState.value) {
                        settingsChanged = true; physioCheckedChanged = true
                        enablePhysiologicalServices = enablePhysiologicalServicesSettingsState.value ?: enablePhysiologicalServices
                        runBlocking {
                            enablePhysiologicalServicesSettingsState.postValue(enablePhysiologicalServices)
                        }
                        requestHeartRateComplicationUpdate(context)
                    }

                    if (enablePhysiologicalAlerts != enablePhysiologicalAlertsSettingState.value) {
                        settingsChanged = true; physiologicalAlertsCheckedChanged = true
                        enablePhysiologicalAlerts = enablePhysiologicalAlertsSettingState.value ?: enablePhysiologicalAlerts
                        runBlocking {
                            enablePhysiologicalAlertsSettingState.postValue(enablePhysiologicalAlerts)
                        }
                        requestPhysioComplicationUpdate(context)
                    }

                     if (settingsChanged) {
                         CoroutineScope(Dispatchers.Main).launch {
                             if (callsignChanged) {
                                 Log.d(TAG, "Storing Callsign ($callsign)")
                                 dataRepository.storeCallsign(callsign)
                             }
                             if (teamChanged) {
                                 Log.d(TAG, "Storing Team ($team)")
                                 dataRepository.storeTeam(team)
                             }
                             if (roleChanged) {
                                 Log.d(TAG, "Storing Role ($role)")
                                 dataRepository.storeRole(role)
                                 dataRepository.storeRoleCategory(category)
                             }
                             if (categoryChanged) {
                                 Log.d(TAG, "Category Changed. Storing Category & Role ($role)")
                                 dataRepository.storeRole(role)
                                 dataRepository.storeRoleCategory(category)
                             }
//                                    if(licenseChanged) {
//                                        dataRepository.storeLicense(license)
//
//                                    }
                             if (ageChanged) {
                                 Log.d(TAG, "Storing Age ($ageValue)")
                                 dataRepository.storePhysiologicalMetricAge(ageValue)
                             }
                             if (heightFeetChanged) {
                                 Log.d(TAG, "Storing Height(Feet) ($heightFeetValue)")
                                 dataRepository.storePhysiologicalMetricHeightFeet(heightFeetValue)
                             }
                             if (heightInchesChanged) {
                                 Log.d(TAG, "Storing Height(Inches) ($heightInchesValue)")
                                 dataRepository.storePhysiologicalMetricHeightInches(heightInchesValue)
                             }
                             if (weightChanged) {
                                 Log.d(TAG, "Storing Weight ($weightValue)")
                                 dataRepository.storePhysiologicalMetricWeight(weightValue)
                             }
                             if (sexChanged) {
                                 Log.d(TAG, "Storing Sex ($sexValue)")
                                 dataRepository.storePhysiologicalMetricSex(sexValue)
                             }
                             if (bloodTypeChanged) {
                                 Log.d(TAG, "Storing Blood Type ($bloodTypeValue)")
                                 dataRepository.storePhysiologicalMetricBloodType(bloodTypeValue)
                             }
                             if (allergiesChanged) {
                                 Log.d(TAG, "Storing Allergy List ($allergyList)")
                                 dataRepository.storePhysiologicalMetricAllergyList(allergyList)
                             }
                             if (userTypeChanged) {
                                 Log.d(TAG, "Storing User Type ($userTypeValue)")
                                 dataRepository.storePhysiologicalMetricUserType(userTypeValue)
                             }
                             if (restingHeartRateChanged) {
                                 Log.d(TAG, "Storing Resting Heart Rate ($restingHeartRate)")
                                 dataRepository.storeRestingHeartRate(restingHeartRate)
                             }
                             if (dynamicReportingChanged){
                                 dataRepository.storeEnableDynamicReportingStrategy(enableDynamicReportingStrategy)
                             }
                             if (constantReportingIntervalChanged){
                                 dataRepository.storeConstantReportingInterval(constantReportingInterval)
                             }
                             if (stationaryReportingIntervalChanged){
                                 dataRepository.storeStationaryReportingInterval(stationaryReportingInterval)
                             }
                             if (onFootReportingIntervalChanged){
                                 dataRepository.storeOnFootReportingInterval(onFootReportingInterval)
                             }
                             if (vehicleReportingIntervalChanged){
                                 dataRepository.storeVehicleReportingInterval(vehicleReportingInterval)
                             }
                             if (whileAlertingReportingIntervalChanged){
                                 dataRepository.storeWhileAlertingReportingInterval(whileAlertingReportingInterval)
                             }
                             if (saveBattOnWifiChanged) {
                                  Log.d(TAG, "Storing batt optimization on wifi: enable($enableWifiBatteryOptimization), allWifi($enableAllWifiBatteryOptimization), wifiSsidList=($wifiSsidList)")
                                  runBlocking {
                                      dataRepository.storeEnableWifiBatteryOptimization(
                                          enableWifiBatteryOptimization
                                      )
                                      dataRepository.storeEnableAllWifiBatteryOptimization(
                                          enableAllWifiBatteryOptimization
                                      )
                                      dataRepository.storeWifiSsidList(wifiSsidList)
                                  }
                                  EventBus.getDefault().post(MessageEvent(OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED, 0))
                             }
                             if (takServerListChanged) {
                                 Log.d(TAG, "Storing updated TAK Server List: $takServerList")
                                 dataRepository.storeTakServerList(takServerList)
                             }

                             if (locationCheckedChanged) {
                                 Log.d(TAG, "Storing enableLocationService $enableLocationService")
                                 dataRepository.storeEnableLocationService(enableLocationService)
                             }
                             if (dittoCheckedChanged) {
                                 Log.d(TAG, "Storing enableDittoService $enableDittoService")
                                 dataRepository.storeEnableDittoService(enableDittoService)
                             }

                             if (chatCheckedChanged) {
                                 Log.d(TAG, "Storing enableChatService $enableChatService")
                                 dataRepository.storeEnableChatService(enableChatService)
                             }
                             if (bloodhoundCompassCheckedChanged) {
                                 Log.d(TAG, "Storing enableBloodhoundCompassService $enableBloodhoundCompassService")
                                 dataRepository.storeEnableBloodhoundCompassService(enableBloodhoundCompassService)
                             }
                             if (hailCheckedChanged) {
                                 Log.d(TAG, "Storing enableHailService $enableHailService")
                                 dataRepository.storeEnableHailService(enableHailService)
                             }
                             if (smsAlertsCheckedChanged) {
                                 Log.d(TAG, "Storing enableSmsForAlerts ${enableSmsForAlerts}")
                                 dataRepository.storeEnableSmsForAlerts(enableSmsForAlerts)
                             }
                             if (smsNumbersChanged){
                                 val smsNumbersListConversion: List<DataRepository.SmsNumber> =
                                        smsNumbersListString.split(",")
                                            .map { it.trim() } // Trim whitespace from each SSID
                                            .filter { it.isNotEmpty() } // Remove any empty values
                                            .map { DataRepository.SmsNumber(it) }
                                 Log.d(TAG, "Storing smsNumbersList ${smsNumbersListConversion}")

                                 dataRepository.storeSmsNumbersList(smsNumbersListConversion)
                                 smsNumbersList = smsNumbersListConversion
                             }

                             if (physioCheckedChanged) {
                                 Log.d(TAG, "Storing enablePhysiologicalServices $enablePhysiologicalServices")
                                 dataRepository.storeEnablePhysiologicalServices(enablePhysiologicalServices)
                             }

                             if (physiologicalAlertsCheckedChanged) {
                                 Log.d(TAG, "Storing enablePhysiologicalAlerts $enablePhysiologicalAlerts")
                                 dataRepository.storeEnablePhysiologicalAlerts(enablePhysiologicalAlerts)
                                 Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(MessageEvent(PHYSIOLOGICAL_ALERTS_ENABLED_CHANGED, if (enablePhysiologicalAlerts) {1} else {0}))}, EVENT_SEND_DELAY_MS)
                             }
                             if (physiologicalAlertsBpmThresholdCheckedChanged) {
                                 Log.d(TAG, "Storing hrHighAlertBpmThreshold $hrHighAlertBpmThreshold")
                                 dataRepository.storeHrHighAlertBpmThreshold(hrHighAlertBpmThreshold)
                             }
                             if (physiologicalAlertsBpmThresholdTimeCheckedChanged) {
                                 Log.d(TAG, "Storing hrHighAlertNumSecsThreshold $hrHighAlertNumSecsThreshold")
                                 dataRepository.storeHrHighAlertNumSecsThreshold(hrHighAlertNumSecsThreshold)
                             }
                             if (physiologicalAlertsBpmThresholdCheckedChanged || physiologicalAlertsBpmThresholdTimeCheckedChanged) {
                                 Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(MessageEvent(HR_ALERT_THRESHOLDS_CHANGED, if (enablePhysiologicalAlerts) {1} else {0}))}, EVENT_SEND_DELAY_MS)
                             }
                            Log.d(TAG, "Finished saving settings")
                        }
                    } else {
                        Log.v(TAG, "Settings not changed and hence NOT saved")
                    }
//                        navController.navigate("main_screen") {
//                            popUpTo(("main_screen")) {
//                                inclusive = true
//                            }
//                        }
                    settingsActivity.finish()
                    context.startActivity(Intent(context, MainActivity::class.java))

                })
        },
        text = stringResource(R.string.callsign_settings)
    )
}

fun convertSmsNumbersListToStringWithSeparator(smsNumbersList: List<DataRepository.SmsNumber>, separatorStr: String): String {
    var result = ""
    if (smsNumbersList.isNotEmpty()) {
        for (smsNumber in smsNumbersList) {
            result = if ("" == result) {
                smsNumber.telephoneNumber
            } else {
                "$result$separatorStr${smsNumber.telephoneNumber}"
            }
        }
    }
    return result
}

@Composable
fun ConfirmCancelButtons(
    onClickCancel: () -> Unit,
    onClickConfirm: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
    ) {
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            IconButton(
                onClick = onClickCancel,
                modifier = Modifier
                    .size(50.dp)
                    .border(1.dp, color = Color.Black, shape = CircleShape),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.Red,
                    containerColor = Color.DarkGray,
                ),
            ) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Close icon")

            }
            Spacer(modifier = Modifier.width(20.dp))
            IconButton(
                onClick = onClickConfirm,
                modifier = Modifier
                    .size(50.dp)
                    .border(1.dp, color = Color.Black, shape = CircleShape),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.Black,
                    containerColor = colors.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Confirm icon",
                    tint = Color.Black
                )

            }
        }
    }
}

@Composable
fun BackButton(onClick: () -> Unit) {
    Button(
        onClick = { onClick() },
        modifier = Modifier.size(height = 35.dp, width = 65.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Gray,
            contentColor = Color.Red
        ),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "Go back to Main Settings Screen",
        )
    }
}

fun requestHeartRateComplicationUpdate(context: Context) {
    val componentName = ComponentName(context, HeartRateComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}

@Preview(widthDp = 192, heightDp = 1200, showBackground = true)
@Composable
fun SettingsPreview() {
    var callsignState = MutableLiveData<String>("WEAROS-0")
    var licenseState = MutableLiveData<String>("AscentLicense")

    SettingsScreen(rememberSwipeDismissableNavController(),callsignState, licenseState, LocalContext.current)
}

@Preview(widthDp = 192, heightDp = 800, showBackground = true)
@Composable
fun PhysioSettingsPreview() {

    PhysiologicalMetricsSetting(rememberSwipeDismissableNavController())
}
@Preview(device = Devices.WEAR_OS_LARGE_ROUND, showSystemUi = true, showBackground = true)
@Composable
fun SexMetricPreview() {
    var licenseState = MutableLiveData<String>("")

    SexBloodtypeUsertypeSetting(rememberSwipeDismissableNavController(), licenseState, sexOptionsList, "Blood Type")
}
@Preview(device = Devices.WEAR_OS_LARGE_ROUND, showSystemUi = true, showBackground = true)
@Composable
fun RoleSettingPreview() {
    RoleSetting(rememberSwipeDismissableNavController())
}

@Preview(widthDp = 192, heightDp = 500, showBackground = true)
@Composable
fun AllergyMetricPreview() {

    AllergySetting(rememberSwipeDismissableNavController(),"Allergies")
}

@Preview(widthDp = 192, heightDp = 500, showBackground = true)
@Composable
fun PhysioAlertsPreview() {
    PhysiologicalAlertsSetting(rememberSwipeDismissableNavController())
}
@Preview(widthDp = 192, heightDp = 600, showBackground = true)
@Composable
fun ReportingStratPreview() {
    ReportingStrategySettings(rememberSwipeDismissableNavController())
}
@Preview(widthDp = 192, heightDp = 600, showBackground = true)
@Composable
fun ReportingStrategyPreview() {
    ReportingStrategyDynamicConstantSetting(rememberSwipeDismissableNavController())
}
@Preview(device = Devices.WEAR_OS_LARGE_ROUND, showSystemUi = true, showBackground = true)
@Composable
fun ReportingIntPreview() {
    ReportingStrategyIntervalSettings(rememberSwipeDismissableNavController(), constantReportingIntervalSettingsState, "Static Reporting Interval", "0")
}
