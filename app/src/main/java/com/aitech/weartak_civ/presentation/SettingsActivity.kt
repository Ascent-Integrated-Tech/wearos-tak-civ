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
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Gravity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.Utils.setAirplaneMode
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
import com.aitech.weartak_civ.presentation.MainActivity.Constants.birthYearSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.birthYearState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.birthYearValue
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
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableCompanionAppService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableCompanionAppServiceSettingsState
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
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBatdokCot
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBatdokCotSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBatteryAlerts
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBatteryAlertsSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBloodhoundCompassService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBloodhoundCompassServiceSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.loadoutWeight
import com.aitech.weartak_civ.presentation.MainActivity.Constants.loadoutWeightSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.loadoutWeightState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdAlert
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdAlertSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdAlertState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdWarning
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdWarningSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionThresholdWarningState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionWarningThresholdLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionWarningThresholdLengthSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionWarningThresholdLengthState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionAlertThresholdLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionAlertThresholdLengthSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.exertionAlertThresholdLengthState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrHighAlertBpmThreshold
import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrHighAlertBpmThresholdSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrHighAlertBpmThresholdState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrLowAlertBpmThreshold
import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrLowAlertBpmThresholdSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.restingHrLowAlertBpmThresholdState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.stationaryReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.stationaryReportingIntervalSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.stationaryReportingIntervalState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.strideLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.strideLengthSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.strideLengthState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformPantsLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformPantsLengthSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformPantsLengthState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformWaistSize
import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformWaistSizeSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.uniformWaistSizeState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableEsriEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableEsriEndpointSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableHighAtmPressureAlert
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableHighAtmPressureAlertSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableImmersionAlerts
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableImmersionAlertsSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLowAtmPressureAlert
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLowAtmPressureAlertSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableMulticastOnWifi
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableMulticastSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableSitxService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableSitxServiceSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableOnnxGunshotService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableOnnxGunshotServiceSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableTabletCommandEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableTabletCommandEndpointSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.esriEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.esriEndpointSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.highAtmPressureAlertThreshold
import com.aitech.weartak_civ.presentation.MainActivity.Constants.highAtmPressureAlertThresholdSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrAlertThresholdLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrAlertThresholdLengthSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrAlertThresholdLengthState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrWarningThresholdLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrWarningThresholdLengthSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.highRestingHrWarningThresholdLengthState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowAtmPressureAlertThreshold
import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowAtmPressureAlertThresholdSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrAlertThresholdLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrAlertThresholdLengthSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrAlertThresholdLengthState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrWarningThresholdLength
import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrWarningThresholdLengthSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.lowRestingHrWarningThresholdLengthState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastAddress
import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastAddressSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastProtocol
import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastProtocolSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastPort
import com.aitech.weartak_civ.presentation.MainActivity.Constants.multicastPortSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxRepo
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxTakClientId
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxTakClientIdSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxTakUrl
import com.aitech.weartak_civ.presentation.MainActivity.Constants.sitxTakUrlSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandApikey
import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandApikeySettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandEndpointSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.vehicleReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.vehicleReportingIntervalSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.vehicleReportingIntervalState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.whileAlertingReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.whileAlertingReportingIntervalSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.whileAlertingReportingIntervalState
import com.aitech.weartak_civ.presentation.viewmodel.SitxViewModel
import com.aitech.weartak_civ.service.HailService.HailLicenseStates
import com.aitech.weartak_civ.service.sitx.SitxServiceCommand
import com.aitech.weartak_civ.service.sitx.SitxServiceCommandBus
import com.aitech.weartak_civ.service.sitx.SitxServiceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.String


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
        const val LABEL_WARNING_LENGTH = "Warning Length"
        const val LABEL_ALERT_LENGTH = "Alert Length"
        const val UNIT_MINUTES = " minutes"
        const val UNIT_SECONDS = " seconds"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "SettingsActivity onCreate()")
        settingsActivity = this
        loadSettingsValues()
        Log.d(TAG, "SettingsActivity onCreate called with enablePhysiologicalAlertsSettingState=$enablePhysiologicalAlertsSettingState and enablePhysiologicalAlerts=$enablePhysiologicalAlerts")

        enableEdgeToEdge()
        setContent {
            val sitxVm: SitxViewModel = viewModel()
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WearTAKCivSettings(callsignState, licenseState, sitxVm = sitxVm)
                }
            }
        }
    }
}

suspend fun refreshTakServerSettings() {
    takServerList = dataRepository.getTakServerList.first()
    takServerListState.postValue(takServerList)
    constantReportingInterval = dataRepository.getConstantReportingInterval.first()
    constantReportingIntervalState.postValue(constantReportingInterval)
    constantReportingIntervalSettingsState.postValue(constantReportingInterval)
}

fun loadSettingsValues(){
    enableLocationServiceSettingsState.postValue(enableLocationService)
    enableOnnxGunshotServiceSettingsState.postValue(enableOnnxGunshotService)
    enableDittoServiceSettingsState.postValue(enableDittoService)
    enableChatServiceSettingsState.postValue(enableChatService)
    enableImmersionAlertsSettingsState.postValue(enableImmersionAlerts)
    enableLowAtmPressureAlertSettingsState.postValue(enableLowAtmPressureAlert)
    lowAtmPressureAlertThresholdSettingsState.postValue(lowAtmPressureAlertThreshold)
    enableHighAtmPressureAlertSettingsState.postValue(enableHighAtmPressureAlert)
    highAtmPressureAlertThresholdSettingsState.postValue(highAtmPressureAlertThreshold)
    enableCompanionAppServiceSettingsState.postValue(enableCompanionAppService)
    enableBatteryAlertsSettingsState.postValue(enableBatteryAlerts)
    enableSmsForAlertsSettingsState.postValue(enableSmsForAlerts)
    enablePhysiologicalServicesSettingsState.postValue(enablePhysiologicalServices)
    enableHailServiceSettingsState.postValue(enableHailService)
    enableBatdokCotSettingsState.postValue(enableBatdokCot)
    enableBloodhoundCompassServiceSettingsState.postValue(enableBloodhoundCompassService)
    callsignSettingsState.postValue(callsign)
    teamSettingsState.postValue(team)
    categorySettingsState.postValue(category)
    roleSettingsState.postValue(role)
    multicastAddressSettingsState.postValue(multicastAddress)
    multicastProtocolSettingsState.postValue(multicastProtocol)
    multicastPortSettingsState.postValue(multicastPort)
    tabletCommandEndpointSettingsState.postValue(tabletCommandEndpoint)
    tabletCommandApikeySettingsState.postValue(tabletCommandApikey)
    esriEndpointSettingsState.postValue(esriEndpoint)
    enableMulticastSettingsState.postValue(enableMulticastOnWifi)
    enableTabletCommandEndpointSettingsState.postValue(enableTabletCommandEndpoint)
    enableEsriEndpointSettingsState.postValue(enableEsriEndpoint)
    enableSitxServiceSettingsState.postValue(enableSitxService)
    sitxTakUrlSettingsState.postValue(sitxTakUrl)
    sitxTakClientIdSettingsState.postValue(sitxTakClientId)

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
    restingHrHighAlertBpmThresholdState.postValue(restingHrHighAlertBpmThreshold)
    restingHrLowAlertBpmThresholdState.postValue(restingHrLowAlertBpmThreshold)
    highRestingHrWarningThresholdLengthState.postValue(highRestingHrWarningThresholdLength)
    lowRestingHrWarningThresholdLengthState.postValue(lowRestingHrWarningThresholdLength)
    highRestingHrAlertThresholdLengthState.postValue(highRestingHrAlertThresholdLength)
    lowRestingHrAlertThresholdLengthState.postValue(lowRestingHrAlertThresholdLength)
    exertionThresholdWarningState.postValue(exertionThresholdWarning)
    exertionThresholdAlertState.postValue(exertionThresholdAlert)
    exertionWarningThresholdLengthState.postValue(exertionWarningThresholdLength)
    exertionAlertThresholdLengthState.postValue(exertionAlertThresholdLength)

    //hrHighAlertBpmThresholdState.postValue(hrHighAlertBpmThreshold)
    //hrHighAlertNumSecsThresholdState.postValue(hrHighAlertNumSecsThreshold)
    birthYearState.postValue(birthYearValue)
    heightFeetState.postValue(heightFeetValue)
    heightInchesState.postValue(heightInchesValue)
    weightState.postValue(weightValue)
    sexState.postValue(sexValue)
    bloodTypeState.postValue(bloodTypeValue)
    allergyListState.postValue(allergyList)
    userTypeState.postValue(userTypeValue)
    uniformWaistSizeState.postValue(uniformWaistSize)
    strideLengthState.postValue(strideLength)
    uniformPantsLengthState.postValue(uniformPantsLength)
    loadoutWeightState.postValue(loadoutWeight)
    restingHeartRateState.postValue(restingHeartRate)
}

@Composable
fun WearTAKCivSettings(callsignLiveDataState: MutableLiveData<String>, licenseLiveDataState: MutableLiveData<String>, sitxVm: SitxViewModel) {
    val navController = rememberSwipeDismissableNavController()
    val context = LocalContext.current
//    var selectedCategoryAndRoleIndex by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectedCategoryAndRoleIndex by remember {
        mutableStateOf(findRoleSelectionFromString(roleSettingsState.value))
    }
    // collect license from data repo so we can see if it changes.
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")

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
            StringEntryPage(
                navController,
                callsignSettingsState,
                stringResource(R.string.callsign),
                stringResource(R.string.callsign),
                "settings_screen"
            )
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
            NumericEntryPage(navController, constantReportingIntervalSettingsState, 1000000, 1, "Constant Reporting Interval", context, "reporting_settings_screen")
        }
        composable(
            "stationary_reporting_interval_screen") {
            NumericEntryPage(navController, stationaryReportingIntervalSettingsState, 1000000,1,  "Stationary Reporting Interval", context, "reporting_settings_screen")
        }
        composable(
            "on_foot_reporting_interval_screen") {
            NumericEntryPage(navController, onFootReportingIntervalSettingsState, 1000000, 1, "On Foot Reporting Interval", context, "reporting_settings_screen")
        }
        composable(
            "vehicle_reporting_interval_screen") {
            NumericEntryPage(navController, vehicleReportingIntervalSettingsState, 1000000, 1, "Vehicle Reporting Interval", context,"reporting_settings_screen" )
        }
        composable(
            "while_alerting_reporting_interval_screen") {
            NumericEntryPage(navController, whileAlertingReportingIntervalSettingsState, 1000000, 1, "While Alerting Reporting Interval", context, "reporting_settings_screen")
        }

        composable(
            "manage_outputs_screen") {
            ManageOutputsSetting(navController)
        }
        composable(
            "tak_sa_multicast_screen") {
            ManageOutputsMulticastSetting(navController)
        }
        composable(
            "tak_sa_multicast_address_screen") {
            StringEntryPage(
                navController,
                multicastAddressSettingsState,
                stringResource(R.string.address),
                stringResource(R.string.address),
                "tak_sa_multicast_screen"
            )
        }
        composable(
            "tak_sa_multicast_output_protocol_screen") {
            //val outputProtocolList = listOf(stringResource(R.string.udp), stringResource(R.string.tcp), stringResource(R.string.quic))
            val outputProtocolList = listOf(stringResource(R.string.udp))
            RadioChoiceSetting(navController, multicastProtocolSettingsState, outputProtocolList, stringResource(R.string.output_protocol), "tak_sa_multicast_screen")

        }
        composable(
            "tak_sa_multicast_server_port_screen") {
            NumericEntryPage(
                navController,
                multicastPortSettingsState,
                99999,
                0,
                stringResource(R.string.server_port),
                context,
                "tak_sa_multicast_screen")
        }
        composable("sitx_tak_screen") {
            ManageOutputsSitxSetting(navController, sitxVm = sitxVm)
        }
        composable("sitx_tak_url_screen") {
            StringEntryPage(
                navController,
                sitxTakUrlSettingsState,
                stringResource(R.string.address_url),
                stringResource(R.string.address_url),
                "sitx_tak_screen"
            )
        }
        composable("sitx_tak_client_id_screen") {
            StringEntryPage(
                navController,
                sitxTakClientIdSettingsState,
                stringResource(R.string.client_id),
                stringResource(R.string.client_id),
                "sitx_tak_screen"
            )
        }
        composable(
            "tablet_command_screen") {
            ManageOutputsTabletCommandSetting(navController)

        }
        composable(
            "tablet_command_endpoint_screen") {
            StringEntryPage(
                navController,
                tabletCommandEndpointSettingsState,
                stringResource(R.string.endpoint),
                stringResource(R.string.endpoint),
                "tablet_command_screen"
            )
        }
        composable(
            "tablet_command_apikey_screen") {
            StringEntryPage(
                navController,
                tabletCommandApikeySettingsState,
                stringResource(R.string.api_key),
                stringResource(R.string.api_key),
                "tablet_command_screen"
            )
        }
        composable(
            "esri_screen") {
            ManageOutputsEsriSetting(navController)

        }
        composable(
            "esri_endpoint_screen") {
            StringEntryPage(
                navController,
                esriEndpointSettingsState,
                stringResource(R.string.endpoint),
                stringResource(R.string.endpoint),
                "esri_screen"
            )
        }
        composable(
            "atm_pressure_alerts_screen") {
            AtmPressureAlertSettings(navController)
        }
        composable(
            "low_atm_pressure_threshold_screen") {
            NumericEntryPage(
                navController,
                lowAtmPressureAlertThresholdSettingsState,
                1500,
                0,
                stringResource(R.string.low_atm_pressure_threshold),
                context,
                "atm_pressure_alerts_screen")
        }
        composable(
            "high_atm_pressure_threshold_screen") {
            NumericEntryPage(
                navController,
                highAtmPressureAlertThresholdSettingsState,
                99999,
                500,
                stringResource(R.string.high_atm_pressure_threshold),
                context,
                "atm_pressure_alerts_screen")
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
            "physiological_heart_rate_alerts_setting_screen") {
            HeartRateAlertsSetting(navController)
        }
        composable(
            "high_resting_hr_bpm_threshold_screen") {
            NumericEntryPage(navController, restingHrHighAlertBpmThresholdSettingsState, 300, 0,"High HR Threshold", context,"physiological_heart_rate_alerts_setting_screen")
        }
        composable(
            "high_resting_hr_warning_threshold_length") {
            NumericEntryPage(navController, highRestingHrWarningThresholdLengthSettingsState, 300,0, "Warning Length - High HR", context,"physiological_heart_rate_alerts_setting_screen")
        }
        composable(
            "high_resting_hr_alert_threshold_length") {
            NumericEntryPage(navController, highRestingHrAlertThresholdLengthSettingsState, 300,0, "Alert Length - High HR", context,"physiological_heart_rate_alerts_setting_screen")
        }
        composable(
            "low_resting_hr_bpm_threshold_screen") {
            NumericEntryPage(navController, restingHrLowAlertBpmThresholdSettingsState, 300,0, "Low HR Threshold", context,"physiological_heart_rate_alerts_setting_screen")
        }
        composable(
            "low_resting_hr_warning_threshold_length") {
            NumericEntryPage(navController, lowRestingHrWarningThresholdLengthSettingsState, 300,0, "Warning Length - Low HR", context,"physiological_heart_rate_alerts_setting_screen")
        }
        composable(
            "low_resting_hr_alert_threshold_length") {
            NumericEntryPage(navController, lowRestingHrAlertThresholdLengthSettingsState, 300,0, "Alert Length - Low HR", context,"physiological_heart_rate_alerts_setting_screen")
        }
        composable(
            "physiological_exertion_alerts_setting_screen") {
            ExertionAlertsSetting(navController)
        }
        composable(
            "exertion_threshold_warning_screen") {
            NumericEntryPage(navController, exertionThresholdWarningSettingsState, 201,0, "ExertionWarning Threshold", context,"physiological_exertion_alerts_setting_screen")
        }
        composable(
            "exertion_warning_threshold_length") {
            NumericEntryPage(navController, exertionWarningThresholdLengthSettingsState, 201,0, SettingsActivity.LABEL_WARNING_LENGTH, context,"physiological_exertion_alerts_setting_screen")
        }
        composable(
            "exertion_threshold_alert_screen") {
            NumericEntryPage(navController, exertionThresholdAlertSettingsState, 1201,0, "Exertion Alert Threshold", context,"physiological_exertion_alerts_setting_screen")
        }
        composable(
            "exertion_alert_threshold_length") {
            NumericEntryPage(navController, exertionAlertThresholdLengthSettingsState, 1201,0, SettingsActivity.LABEL_ALERT_LENGTH, context,"physiological_exertion_alerts_setting_screen")
        }
//        composable(
//            "physiological_alerts_bpm_screen") {
////            PhysioAlertsBpmThresholdSetting(navController, hrHighAlertBpmThresholdSettingsState, stringResource(R.string.threshold_high_heart_rate), "140")
//            NumericEntryPage(navController, hrHighAlertBpmThresholdSettingsState, 300,1, stringResource(R.string.threshold_high_heart_rate), context,"physiological_alerts_setting_screen")
//        }
//        composable(
//            "physiological_alerts_bpm_length_screen") {
////            PhysioAlertsBpmThresholdSetting(navController, hrHighAlertNumSecsThresholdSettingsState, (stringResource(R.string.threshold_high_heart_rate_length)), "60" + "seconds")
//            NumericEntryPage(navController, hrHighAlertNumSecsThresholdSettingsState, 1000000, 1, (stringResource(R.string.threshold_high_heart_rate_length)), context, "physiological_alerts_setting_screen")
//        }
        //Physio Metrics
        composable(
            "physiological_metrics_setting_screen") {
            PhysiologicalMetricsSetting(navController)
        }
        composable("physiological_metrics_medical_profile_batdok") {
            PhysioMetricsMedicalProfileSetting(navController)
        }
        composable(
            "physiological_metrics_birth_year_screen") {
//            AgeWeightSetting(navController, birthYearSettingsState, 150, stringResource(R.string.age), context)
            NumericEntryPage(navController, birthYearSettingsState, Year.now().value, 1900, stringResource(R.string.birth_year), context, "physiological_metrics_medical_profile_batdok")
        }
        composable(
            "physiological_metrics_height_screen") {
            HeightPickerSetting(navController)
        }
        composable(
            "physiological_metrics_weight_screen") {
//            AgeWeightSetting(navController, weightSettingsState, 1000, stringResource(R.string.weight), context)
            NumericEntryPage(navController, weightSettingsState, 1000, 0,stringResource(R.string.weight), context, "physiological_metrics_medical_profile_batdok")
        }
        composable(
            "physiological_metrics_sex_screen") {
            RadioChoiceSetting(navController, sexSettingsState, sexOptionsList, stringResource(R.string.sex), "physiological_metrics_setting_screen")
        }
        composable(
            "physiological_metrics_bloodtype_screen") {
            RadioChoiceSetting(navController, bloodTypeSettingsState, bloodTypeOptionsList, stringResource(R.string.blood_type), "physiological_metrics_setting_screen")
        }
        composable(
            "physiological_metrics_allergies_screen") {
            AllergySetting(navController, stringResource(R.string.allergies))
        }
        composable(
            "physiological_metrics_usertype_screen") {
            RadioChoiceSetting(navController, userTypeSettingsState, userTypeOptionsList, stringResource(R.string.user_type), "physiological_metrics_setting_screen")
        }
        composable(
            "physiological_metrics_resting_hr_screen") {
//            AgeWeightSetting(navController, restingHeartRateSettingsState, 150, stringResource(R.string.resting_heart_rate), context)
            NumericEntryPage(navController, restingHeartRateSettingsState, 150, 0,stringResource(R.string.resting_heart_rate), context, "physiological_metrics_medical_profile_batdok")
        }
        composable("physiological_metrics_gait_tracking") {
            PhysioMetricsGaitTrackingSettings(navController)
        }
        composable(
            "physiological_metrics_uniform_waist_size") {
            NumericEntryPage(navController, uniformWaistSizeSettingsState, 100, 0,stringResource(R.string.uniform_waist_size_text), context, "physiological_metrics_gait_tracking")
        }
        composable(
            "physiological_metrics_stride_length") {
            NumericEntryPage(navController, strideLengthSettingsState, 300, 0,stringResource(R.string.stride_length_text), context, "physiological_metrics_gait_tracking")
        }
        composable(
            "physiological_metrics_uniform_pants_length") {
            NumericEntryPage(navController, uniformPantsLengthSettingsState, 100, 0,stringResource(R.string.uniform_pants_length_text), context, "physiological_metrics_gait_tracking")
        }
        composable(
            "physiological_metrics_loadout_weight") {
            NumericEntryPage(navController, loadoutWeightSettingsState, 1000, 0,stringResource(R.string.loadout_weight_text), context, "physiological_metrics_gait_tracking")
        }

        composable(
            "hail_setting_screen") {
            HailSetting(navController)
        }
        composable(
            "hail_pairing_screen") {
            InvalidHailScreen(navController)
        }
        composable("sitx_status_authorization_screen") {
            SitxStatusAuthorizationScreen(navController, sitxVm)
        }
    }

}

@Composable
fun GenericOutlinedTextField(
    liveDataState: MutableLiveData<String>,
    label: String,
    placeholder: String
): TextFieldValue {
    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(liveDataState.value ?: "null"))
    }
    OutlinedTextField(
        value = textFieldValue,
        onValueChange = {
            textFieldValue = it
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
                    TextFieldValue(name),
                    onValueChanged = { name = it },
                    false,
                    false,
                    true
                )
                ServerSettingText(
                    stringResource(R.string.address_url),
                    TextFieldValue(address),
                    onValueChanged = { address = it },
                    false,
                    false,
                    true
                )
                ServerSettingText(
                    stringResource(R.string.port),
                    TextFieldValue(port),
                    onValueChanged = { port = it },
                    false,
                    true,
                    true
                )
                ServerSettingText(
                    stringResource(R.string.username),
                    TextFieldValue(username),
                    onValueChanged = { username = it },
                    false,
                    false,
                    true
                )
                ServerSettingText(
                    stringResource(R.string.password),
                    TextFieldValue(password),
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
                                    mainActivity.getString(R.string.please_enter_valid_values_for_all_fields), Toast.LENGTH_LONG).show()
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
                    TextFieldValue(name),
                    onValueChanged = { name = it },
                    false,
                    false,
                    true
                )

                if (isP12Cert == true) {
                    ServerSettingText(
                        stringResource(R.string.address_url),
                        TextFieldValue(address),
                        onValueChanged = { address = it },
                        false,
                        false,
                        false
                    )
                    ServerSettingText(
                        stringResource(R.string.port),
                        TextFieldValue(port),
                        onValueChanged = { port = it },
                        false,
                        true,
                        true)
                } else {
                    ServerSettingText(
                        stringResource(R.string.address_url),
                        TextFieldValue(address),
                        onValueChanged = { address = it },
                        false,
                        false,
                        true
                    )
                    ServerSettingText(
                        stringResource(R.string.port),
                        TextFieldValue(port),
                        onValueChanged = { port = it },
                        false,
                        true,
                        true)
                    ServerSettingText(
                        stringResource(R.string.username),
                        TextFieldValue(username),
                        onValueChanged = { username = it },
                        false,
                        false,
                        true
                    )
                    ServerSettingText(
                        stringResource(R.string.password),
                        TextFieldValue(password),
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
                            Toast.makeText(mainActivity, mainActivity.getString(R.string.please_enter_valid_values_for_all_fields), Toast.LENGTH_LONG).show()
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
    value: TextFieldValue,
    onValueChanged: (String) -> Unit,
    isPassword : Boolean,
    isPort : Boolean,
    isEnabled: Boolean
) {
    val maxChar = 20

    var tfv by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(value) }

    OutlinedTextField(
        value = tfv,
        enabled = isEnabled,
        onValueChange = { newValue ->
            tfv = newValue
            onValueChanged(newValue.text)
        },
//        maxLines = 1,
        label = { Text(text = label, color = colors.primary, fontSize = 12.sp) },
        textStyle = TextStyle(color = colors.primary, fontSize = if (value.text.length <= maxChar) 12.sp else 10.sp),
        modifier = Modifier.padding(bottom = 5.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPort) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Done),
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
        var tfv by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }


        OutlinedTextField(
            value = tfv,
            onValueChange = {newValue ->
                tfv = newValue
            },
            //TODO: DUPLICATE CODE - Same outlinetextfield credentials for new and edited wifiSSID chivvv
            singleLine = true,
            label = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            placeholder = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            textStyle = TextStyle(color = colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
                    if (tfv.text.isNotBlank()) {
                        val mutableWifiSsidList = wifiSsidListSettingsState.value?.toMutableList() ?: emptyList<DataRepository.WifiSsidItem>().toMutableList()
                        mutableWifiSsidList.add(DataRepository.WifiSsidItem(tfv.text))
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
        var tfv by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(wifiSsidList.getOrNull(index)?.name.orEmpty())) }

        Log.d(TAG, "text = ${wifiSsidList.getOrNull(index)?.name.orEmpty()}")

        OutlinedTextField(
            value = tfv,
            onValueChange = { tfv = it },
            //TODO: DUPLICATE CODE - Same outlinetextfield credentials for new and edited wifiSSID chivvv
            singleLine = true,
            label = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            placeholder = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            textStyle = TextStyle(color = colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
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
                    mutableWifiSsidList[index] = DataRepository.WifiSsidItem(tfv.text)
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

//Used for callsign and output string entry
@Composable
fun StringEntryPage(
    navController: NavController,
    metricSettingState: MutableLiveData<String>,
    label: String,
    placeholder: String,
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
        val textField = GenericOutlinedTextField(metricSettingState, label, placeholder)
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "$label Settings NOT saved")
                    navController.navigate(route) {
                        popUpTo((route)) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    metricSettingState.setValue(textField.text)
                    Log.d(TAG, "Settings saved with $label: ${metricSettingState.value}")
                    navController.navigate(route) {
                        popUpTo((route)) {
                            inclusive = true
                        }
                    }
                })
        }
    }
}

//Used for PhysioAlerts, Reporting Rates, and some User Metrics
@Composable
fun NumericEntryPage(
    navController: NavController,
    metricSettingState: MutableLiveData<Int>,
    maximum: Int,
    minimum: Int,
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
        var metricTextFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(metricSettingState.value.toString())) }
        //Log.d(TAG, "metricTextFieldValue=" + metricTextFieldValue.text)
        val focusManager = LocalFocusManager.current

        OutlinedTextField(
            value = metricTextFieldValue,
            onValueChange = {
                metricTextFieldValue = it
            },
            singleLine = true,
            label = { Text(text = label, color = colors.primary) },
            placeholder = { Text(text = label, color = colors.primary) },
            textStyle = TextStyle(color = colors.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (metricTextFieldValue.text.isEmpty()) {
                        Toast.makeText(
                            mainActivity,
                            context.getString(R.string.please_enter_a_value),
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (metricTextFieldValue.text.toInt() > maximum) {
                        Toast.makeText(
                            mainActivity,
                            context.getString(R.string.value_exceeds_maximum_please_re_enter), Toast.LENGTH_LONG).show()
                    } else if (metricTextFieldValue.text.toInt() < minimum){
                        Toast.makeText(
                            mainActivity,
                            //TODO: WEAR-272 this doesnt recognize the new string resource for it?
                            " Value is less than minimum, please re-enter", Toast.LENGTH_LONG).show()                    } else {
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
                    val intValue = metricTextFieldValue.text.toIntOrNull()
                    if (null != intValue) {
                        metricSettingState.setValue(intValue)
                        Log.d(TAG, "Settings State saved with numeric entry threshold: ${metricSettingState.value}")
                        navController.navigate(route) {
                            popUpTo((route)) {
                                inclusive = true
                            }
                        }
                    } else {
                        Toast.makeText(mainActivity, context.getString(R.string.please_enter_a_value), Toast.LENGTH_LONG).show()
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
        var intervalSettingTextFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
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
                keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            singleLine = true,
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
                    val intValue = intervalSettingTextFieldValue.text.toIntOrNull()
                    if (null != intValue) {
                        intervalSettingState.postValue(intValue)
                        Log.d(TAG, "Settings saved with reporting interval: ${intervalSettingState.value}")
                        navController.navigate("reporting_settings_screen") {
                            popUpTo(("reporting_settings_screen")) {
                                inclusive = true
                            }
                        }
                    } else {
                        Toast.makeText(mainActivity, mainActivity.getString(R.string.please_enter_a_value), Toast.LENGTH_LONG).show()
                    }
                })
        }
    }
}

@Composable
fun enableDisableFromBoolean(boolean: Boolean?): String {
    val enableDisableString: String = when (boolean) {
        false -> stringResource(R.string.disabled)
        true -> stringResource(R.string.enabled)
        else -> stringResource(R.string.error)
    }
    return enableDisableString
}

@Composable
fun AtmPressureAlertSettings(navController: NavController) {
    Log.i(TAG, "AtmPressureAlertSettings called")
    val lowAtmPressureAlertCheckedLive by enableLowAtmPressureAlertSettingsState.observeAsState()
    val highAtmPressureAlertCheckedLive by enableHighAtmPressureAlertSettingsState.observeAsState()


    WearTakCivScreenDimensions(
        columnContent = {
            SettingSwitchToggleLiveData(
                lowAtmPressureAlertCheckedLive,
                { enableLowAtmPressureAlertSettingsState.postValue(it) },
                {},
                stringResource(R.string.low_atm_pressure_alert),
                stringResource(R.string.on_off)
            )
            DataMetricsChip("low_atm_pressure_threshold_screen", stringResource(R.string.low_atm_pressure_threshold), lowAtmPressureAlertThresholdSettingsState.value.toString(), navController)
            Spacer(modifier = Modifier.height(16.dp))
            SettingSwitchToggleLiveData(
                highAtmPressureAlertCheckedLive,
                { enableHighAtmPressureAlertSettingsState.postValue(it) },
                {},
                stringResource(R.string.high_atm_pressure_alert),
                stringResource(R.string.on_off)
            )
            DataMetricsChip("high_atm_pressure_threshold_screen", stringResource(R.string.high_atm_pressure_threshold), highAtmPressureAlertThresholdSettingsState.value.toString(), navController)
            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.i(TAG, "Atm Pressure Alert Settings NOT saved")
                    enableLowAtmPressureAlertSettingsState.setValue(enableLowAtmPressureAlert)
                    lowAtmPressureAlertThresholdSettingsState.setValue(lowAtmPressureAlertThreshold)
                    enableHighAtmPressureAlertSettingsState.setValue(enableHighAtmPressureAlert)
                    highAtmPressureAlertThresholdSettingsState.setValue(highAtmPressureAlertThreshold)
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                })
        },
        text = stringResource(R.string.atm_pressure_alerts)
    )
}
@Composable
fun ManageOutputsSetting(navController: NavController) {
    Log.d(TAG, "ManageOutputsSetting called")

    WearTakCivScreenDimensions(
        columnContent = {
            DataMetricsChip("tak_sa_multicast_screen", stringResource(R.string.tak_sa_multicast),
                enableDisableFromBoolean(enableMulticastSettingsState.value), navController)
            DataMetricsChip("sitx_tak_screen", stringResource(R.string.sitx_tak),
                enableDisableFromBoolean(enableSitxServiceSettingsState.value), navController)
            DataMetricsChip("tablet_command_screen", stringResource(R.string.tablet_command),
                enableDisableFromBoolean(enableTabletCommandEndpointSettingsState.value), navController)
            DataMetricsChip("esri_screen", stringResource(R.string.esri),
                enableDisableFromBoolean(enableEsriEndpointSettingsState.value), navController)

            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Manage Outputs Settings NOT saved")
                    enableMulticastSettingsState.setValue(enableMulticastOnWifi)
                    enableTabletCommandEndpointSettingsState.setValue(enableTabletCommandEndpoint)
                    enableEsriEndpointSettingsState.setValue(enableEsriEndpoint)
                    enableSitxServiceSettingsState.setValue(enableSitxService)

                    multicastAddressSettingsState.setValue(multicastAddress)
                    multicastProtocolSettingsState.setValue(multicastProtocol)
                    multicastPortSettingsState.setValue(multicastPort)
                    tabletCommandEndpointSettingsState.setValue(tabletCommandEndpoint)
                    tabletCommandApikeySettingsState.setValue(tabletCommandApikey)
                    esriEndpointSettingsState.setValue(esriEndpoint)
                    sitxTakUrlSettingsState.setValue(sitxTakUrl)
                    sitxTakClientIdSettingsState.setValue(sitxTakClientId)

                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                })
        },
        text = stringResource(R.string.manage_outputs)
    )
}

@Composable
fun ManageOutputsMulticastSetting(navController: NavController) {
    Log.d(TAG, "ManageOutputsMulticastSetting called")
    val multicastCheckedLive by enableMulticastSettingsState.observeAsState()
//    Log.d(TAG, "enablePhysiologicalAlertsSettingState=$enablePhysiologicalAlertsSettingState")

    WearTakCivScreenDimensions(
        columnContent = {
            SettingSwitchToggleLiveData(
                multicastCheckedLive,
                { enableMulticastSettingsState.postValue(it) },
                {},
                stringResource(R.string.tak_sa_multicast),
                stringResource(R.string.on_off)
            )

            DataMetricsChip("tak_sa_multicast_address_screen", stringResource(R.string.address), multicastAddressSettingsState.value.toString(), navController)
            DataMetricsChip("tak_sa_multicast_output_protocol_screen", stringResource(R.string.output_protocol), multicastProtocolSettingsState.value.toString(), navController)
            DataMetricsChip("tak_sa_multicast_server_port_screen", stringResource(R.string.server_port), multicastPortSettingsState.value.toString(), navController)

            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Multicast Settings NOT saved")
                    enableMulticastSettingsState.setValue(enableMulticastOnWifi)
                    multicastAddressSettingsState.setValue(multicastAddress)
                    multicastProtocolSettingsState.setValue(multicastProtocol)
                    multicastPortSettingsState.setValue(multicastPort)

                    navController.navigate("manage_outputs_screen") {
                        popUpTo(("manage_outputs_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {

                    navController.navigate("manage_outputs_screen") {
                        popUpTo(("manage_outputs_screen")) {
                            inclusive = true
                        }
                    }
                })
        },
        text = stringResource(R.string.tak_sa_multicast)
    )
}

@Composable
fun SitxStatusAuthorizationScreen(navController: NavController, sitxVm: SitxViewModel) {
    val sitxEvent by sitxVm.lastEvent.collectAsStateWithLifecycle(initialValue=null)
    var title = ""
    var secondaryText = ""
    var userCode = ""

    when (val e = sitxEvent) {
        is SitxServiceEvent.NeedsUserAction -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Needs User Action: (${e.userCode}) and (${e.verificationUriComplete})")
            title = "Authorize Device"
            userCode = e.userCode
            secondaryText = "Authorize device at: ${e.verificationUriComplete} and then press Authorize below"
        }
        is SitxServiceEvent.Pending -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Pending:(${e.userCode}) and (${e.verificationUriComplete}) with (${e.message})")
            title = "Pending Auth"
            userCode = e.userCode
            secondaryText = "Device authorization pending: ${e.message}"
        }
        is SitxServiceEvent.Error -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Error: ${e.message}")
            title = "Error"
            secondaryText = "Error: ${e.message}"
        }
        is SitxServiceEvent.Info -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Info: ${e.message}")
            title = "Info"
            secondaryText = "Info: ${e.message}"
        }
        is SitxServiceEvent.Authorized -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Authorized: ${e.message}")
            title = "Device Authorized"
            secondaryText = "Device Authorized: ${e.message}"
        }
        is SitxServiceEvent.SocketsConnected -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) SocketsConnected(${e.count}): ${e.message}")
            title = "Sit(x) Connected"
            secondaryText = "Connected to Sit(x): ${e.message}"
        }
        is SitxServiceEvent.SocketStatus -> {
            title = "Socket Status"
            Log.d(TAG, "SitxStatusAuthorizationScreen Sit(x) SocketStatus(flowTag=${e.flowTag}, status=${e.status}): ${e.message}")
            secondaryText = "Connected to Sit(x) w/ ${e.status}: ${e.message}"
        }
        is SitxServiceEvent.Disconnected -> {
            Log.i(TAG, "SitxStatusAuthorizationScreen Sit(x) Disconnected")
            title = "Sit(x) Disconnected"
            secondaryText = "Disconnected from Sit(x)"
        }
        else -> Unit
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
                text = title,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(vertical = 10.dp, horizontal = 20.dp)
                    .align(Alignment.CenterHorizontally)
            )
            if (sitxEvent is SitxServiceEvent.NeedsUserAction) {
                Text(
                    text = userCode,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 25.sp,
                    modifier = Modifier
                        .padding(vertical = 7.dp)
                        .align(Alignment.CenterHorizontally)
                )
            }
            Text(
                text = secondaryText,
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
                        navController.navigate("sitx_tak_screen") {
                            popUpTo("sitx_tak_screen") { inclusive = true }
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

                if (sitxEvent is SitxServiceEvent.NeedsUserAction) {
                    Spacer(modifier = Modifier.size(10.dp))

                    Button(
                        onClick = {
                            SitxServiceCommandBus.trySend(SitxServiceCommand.PollDeviceToken)
                        },
                        contentPadding = PaddingValues(10.dp),
                        colors = ButtonDefaults.buttonColors(colors.primary),
                    ) {
                        Text(
                            text = "Authorize",
                            color = Color.Black
                        )
                    }
                } else if (sitxEvent is SitxServiceEvent.Error) {
                    Spacer(modifier = Modifier.size(10.dp))

                    Button(
                        onClick = {
                            runBlocking {
                                SitxServiceCommandBus.trySend(SitxServiceCommand.BeginAuth(
                                    baseUrl = sitxRepo.baseUrl.first(),
                                    clientId = sitxRepo.clientId.first() ?: "",
                                    callsign = callsign,
                                    deviceName = device_uid,
                                    deviceId = device_uid
                                )
                                )
                            }
                        },
                        contentPadding = PaddingValues(10.dp),
                        colors = ButtonDefaults.buttonColors(colors.primary),
                    ) {
                        Text(
                            text = "Re-Auth",
                            color = Color.Black
                        )
                    }
                }
            }
        }
        // Render the dialog ONCE, using the remembered state (no inline mutableStateOf(true))
        //LoadingDialog(shouldShowDialog = showLoadingDialog, secondaryText = "Checking License Status")
    }
}

@Composable
fun sitxStatusText(sitxEvent: SitxServiceEvent?): String {
    return when (sitxEvent) {
        is SitxServiceEvent.NeedsUserAction -> "Needs User Action"
        is SitxServiceEvent.Pending -> "Pending Authorization"
        is SitxServiceEvent.Authorized -> "Device Authorized"
        is SitxServiceEvent.SocketStatus -> "Connected"
        is SitxServiceEvent.SocketsConnected -> "Connected"
        is SitxServiceEvent.Info -> "Info"
        is SitxServiceEvent.Error -> "Error"
        is SitxServiceEvent.Disconnected -> "Disconnected"
        else -> "Unknown"
    }
}

@Composable
fun ManageOutputsSitxSetting(navController: NavController, sitxVm: SitxViewModel) {
    Log.d(TAG, "ManageOutputsSitxSetting called")
    val sitxServiceCheckedLive by enableSitxServiceSettingsState.observeAsState()
    val sitxEvent by sitxVm.lastEvent.collectAsStateWithLifecycle(initialValue=null)

    WearTakCivScreenDimensions(
        columnContent = {
            SettingSwitchToggleLiveData(
                sitxServiceCheckedLive,
                { enableSitxServiceSettingsState.postValue(it) },
                {},
                stringResource(R.string.sitx_tak),
                stringResource(R.string.on_off)
            )

            DataMetricsChip("sitx_tak_url_screen", stringResource(R.string.address), sitxTakUrlSettingsState.value.toString(), navController)
            DataMetricsChip("sitx_tak_client_id_screen", stringResource(R.string.client_id), sitxTakClientIdSettingsState.value.toString(), navController)
            if (true == enableSitxServiceSettingsState.value) {
                val nameSecondary = if (enableSitxService) sitxStatusText(sitxEvent) else "Service not enabled"
                DataMetricsChip("sitx_status_authorization_screen", "Sit(x) State", nameSecondary, navController)
            }
            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Sit(x) TAK Settings NOT saved")
                    enableSitxServiceSettingsState.setValue(enableSitxService)
                    sitxTakUrlSettingsState.setValue(sitxTakUrl)
                    sitxTakClientIdSettingsState.setValue(sitxTakClientId)

                    navController.navigate("manage_outputs_screen") {
                        popUpTo(("manage_outputs_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    navController.navigate("manage_outputs_screen") {
                        popUpTo(("manage_outputs_screen")) {
                            inclusive = true
                        }
                    }
                })
        },
        text = stringResource(R.string.sitx_tak)
    )
}

@Composable
fun ManageOutputsTabletCommandSetting(navController: NavController) {
    Log.d(TAG, "ManageOutputsTabletCommandSetting called")
    val enableTabletCommandCheckedLive by enableTabletCommandEndpointSettingsState.observeAsState()
//    Log.d(TAG, "enablePhysiologicalAlertsSettingState=$enablePhysiologicalAlertsSettingState")

    WearTakCivScreenDimensions(
        columnContent = {
            SettingSwitchToggleLiveData(
                enableTabletCommandCheckedLive,
                { enableTabletCommandEndpointSettingsState.postValue(it) },
                {},
                stringResource(R.string.tablet_command),
                stringResource(R.string.on_off)
            )

            DataMetricsChip("tablet_command_endpoint_screen", stringResource(R.string.endpoint), tabletCommandEndpointSettingsState.value.toString(), navController)
            DataMetricsChip("tablet_command_apikey_screen", stringResource(R.string.api_key), tabletCommandApikeySettingsState.value.toString(), navController)

            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Tablet Command Settings NOT saved")
                    enableTabletCommandEndpointSettingsState.setValue(enableTabletCommandEndpoint)
                    tabletCommandEndpointSettingsState.setValue(tabletCommandEndpoint)
                    tabletCommandApikeySettingsState.setValue(tabletCommandApikey)

                    navController.navigate("manage_outputs_screen") {
                        popUpTo(("manage_outputs_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {

                    navController.navigate("manage_outputs_screen") {
                        popUpTo(("manage_outputs_screen")) {
                            inclusive = true
                        }
                    }
                })
        },
        text = stringResource(R.string.tablet_command)
    )
}

@Composable
fun ManageOutputsEsriSetting(navController: NavController) {
    Log.d(TAG, "ManageOutputsEsriSetting called")
    val enableEsriCheckedLive by enableEsriEndpointSettingsState.observeAsState()
//    Log.d(TAG, "enablePhysiologicalAlertsSettingState=$enablePhysiologicalAlertsSettingState")

    WearTakCivScreenDimensions(
        columnContent = {
            SettingSwitchToggleLiveData(
                enableEsriCheckedLive,
                { enableEsriEndpointSettingsState.postValue(it) },
                {},
                stringResource(R.string.esri),
                stringResource(R.string.on_off)
            )

            DataMetricsChip("esri_endpoint_screen", stringResource(R.string.endpoint), esriEndpointSettingsState.value.toString(), navController)

            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Esri Settings NOT saved")
                    enableEsriEndpointSettingsState.setValue(enableEsriEndpoint)
                    esriEndpointSettingsState.setValue(esriEndpoint)

                    navController.navigate("manage_outputs_screen") {
                        popUpTo(("manage_outputs_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {

                    navController.navigate("manage_outputs_screen") {
                        popUpTo(("manage_outputs_screen")) {
                            inclusive = true
                        }
                    }
                })
        },
        text = stringResource(R.string.esri)
    )
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
                        requestSosComplicationUpdate(context)
                        requestStatusIconsComplicationUpdate(context)
                        requestChatComplicationUpdate(context)
                        requestBloodhoundComplicationUpdate(context)
                        requestPhysioStatusAndAlertingComplicationUpdate(context)

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

            SettingSwitchToggleLiveData(
                physiologicalAlertsCheckedLive,
                { enablePhysiologicalAlertsSettingState.postValue(it) },
                {},
                stringResource(R.string.physiological_alerts),
                stringResource(R.string.on_off)
            )

//        DataMetricsChip("physiological_alerts_bpm_screen", stringResource(R.string.threshold_high_heart_rate), hrHighAlertBpmThresholdSettingsState.value.toString(), navController)
//        DataMetricsChip("physiological_alerts_bpm_length_screen", stringResource(R.string.threshold_high_heart_rate_length), hrHighAlertNumSecsThresholdSettingsState.value.toString() + " seconds", navController)
            Chip(
                onClick = { navController.navigate("physiological_heart_rate_alerts_setting_screen") {
                    popUpTo("physiological_heart_rate_alerts_setting_screen") { inclusive = true }
                } },
                enabled = true,
                label = { Text(text = "Resting Heart Rate Alerts") },
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
                onClick = { navController.navigate("physiological_exertion_alerts_setting_screen") {
                    popUpTo("physiological_exertion_alerts_setting_screen") { inclusive = true }
                } },
                enabled = true,
                label = { Text(text = "Exertion Alerts") },
                colors = ChipDefaults.chipColors(
                    backgroundColor = Color.DarkGray,
                    contentColor = Color.White,
                    secondaryContentColor = Color.LightGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "PhysiologicalAlertsSetting Settings NOT saved")
                    restingHrHighAlertBpmThresholdSettingsState.setValue(restingHrHighAlertBpmThreshold)
                    highRestingHrWarningThresholdLengthSettingsState.setValue(highRestingHrWarningThresholdLength)
                    highRestingHrAlertThresholdLengthSettingsState.setValue(highRestingHrAlertThresholdLength)
                    restingHrLowAlertBpmThresholdSettingsState.setValue(restingHrLowAlertBpmThreshold)
                    lowRestingHrWarningThresholdLengthSettingsState.setValue(lowRestingHrWarningThresholdLength)
                    lowRestingHrAlertThresholdLengthSettingsState.setValue(lowRestingHrAlertThresholdLength)
                    exertionThresholdWarningSettingsState.setValue(exertionThresholdWarning)
                    exertionWarningThresholdLengthSettingsState.setValue(exertionWarningThresholdLength)
                    exertionThresholdAlertSettingsState.setValue(exertionThresholdAlert)
                    exertionAlertThresholdLengthSettingsState.setValue(exertionAlertThresholdLength)
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
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
fun HeartRateAlertsSetting(navController: NavController) {
    Log.d(TAG, "HeartRateAlertsSetting called")

    WearTakCivScreenDimensions(
        columnContent = {
            Text(
                text = "High Resting Heart Rate:",
                textAlign = TextAlign.Left,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(bottom = 5.dp, top = 10.dp)
            )
            DataMetricsChip("high_resting_hr_bpm_threshold_screen", "High HR Threshold", restingHrHighAlertBpmThresholdSettingsState.value.toString() + " bpm", navController)
            DataMetricsChip("high_resting_hr_warning_threshold_length", SettingsActivity.LABEL_WARNING_LENGTH, highRestingHrWarningThresholdLengthSettingsState.value.toString() + SettingsActivity.UNIT_MINUTES, navController)
            DataMetricsChip("high_resting_hr_alert_threshold_length", SettingsActivity.LABEL_ALERT_LENGTH, highRestingHrAlertThresholdLengthSettingsState.value.toString() + SettingsActivity.UNIT_MINUTES, navController)
            Spacer(modifier = Modifier.height(15.dp))
            Text(
                text = "Low Resting Heart Rate:",
                textAlign = TextAlign.Left,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(bottom = 5.dp, top = 10.dp)
            )
            DataMetricsChip("low_resting_hr_bpm_threshold_screen", "Low HR Threshold", restingHrLowAlertBpmThresholdSettingsState.value.toString() + " bpm", navController)
            DataMetricsChip("low_resting_hr_warning_threshold_length", SettingsActivity.LABEL_WARNING_LENGTH, lowRestingHrWarningThresholdLengthSettingsState.value.toString() + SettingsActivity.UNIT_MINUTES, navController)
            DataMetricsChip("low_resting_hr_alert_threshold_length", SettingsActivity.LABEL_ALERT_LENGTH, lowRestingHrAlertThresholdLengthSettingsState.value.toString() + SettingsActivity.UNIT_MINUTES, navController)
            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Heart Rate Alert Settings NOT saved")
                    restingHrHighAlertBpmThresholdSettingsState.setValue(restingHrHighAlertBpmThreshold)
                    highRestingHrWarningThresholdLengthSettingsState.setValue(highRestingHrWarningThresholdLength)
                    highRestingHrAlertThresholdLengthSettingsState.setValue(highRestingHrAlertThresholdLength)
                    restingHrLowAlertBpmThresholdSettingsState.setValue(restingHrLowAlertBpmThreshold)
                    lowRestingHrWarningThresholdLengthSettingsState.setValue(lowRestingHrWarningThresholdLength)
                    lowRestingHrAlertThresholdLengthSettingsState.setValue(lowRestingHrAlertThresholdLength)

                    navController.navigate("physiological_alerts_setting_screen") {
                        popUpTo(("physiological_alerts_setting_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    restingHrHighAlertBpmThresholdState.setValue(restingHrHighAlertBpmThresholdSettingsState.value)
                    highRestingHrWarningThresholdLengthState.setValue(highRestingHrWarningThresholdLengthSettingsState.value)
                    highRestingHrAlertThresholdLengthState.setValue(highRestingHrAlertThresholdLengthSettingsState.value)
                    restingHrLowAlertBpmThresholdState.setValue(restingHrLowAlertBpmThresholdSettingsState.value)
                    lowRestingHrWarningThresholdLengthState.setValue(lowRestingHrWarningThresholdLengthSettingsState.value)
                    lowRestingHrAlertThresholdLengthState.setValue(lowRestingHrAlertThresholdLengthSettingsState.value)

                    navController.navigate("physiological_alerts_setting_screen") {
                        popUpTo(("physiological_alerts_setting_screen")) {
                            inclusive = true
                        }
                    }
                })
        },
        text = "Resting Heart Rate Alerts"
    )
}

@Composable
fun ExertionAlertsSetting(navController: NavController) {
    Log.d(TAG, "ExertionAlertsSetting called")

    WearTakCivScreenDimensions(
        columnContent = {

            DataMetricsChip("exertion_threshold_warning_screen", "Exertion Warning Threshold", exertionThresholdWarningSettingsState.value.toString() + "%", navController)
            DataMetricsChip("exertion_warning_threshold_length", SettingsActivity.LABEL_WARNING_LENGTH, exertionWarningThresholdLengthSettingsState.value.toString() + SettingsActivity.UNIT_SECONDS, navController)
            Spacer(modifier = Modifier.height(15.dp))
            DataMetricsChip("exertion_threshold_alert_screen", "Exertion Alert Threshold", exertionThresholdAlertSettingsState.value.toString() + "%", navController)
            DataMetricsChip("exertion_alert_threshold_length", SettingsActivity.LABEL_ALERT_LENGTH, exertionAlertThresholdLengthSettingsState.value.toString() + SettingsActivity.UNIT_SECONDS, navController)

            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Exertion Alerts Settings NOT saved")
                    exertionThresholdWarningSettingsState.setValue(exertionThresholdWarning)
                    exertionWarningThresholdLengthSettingsState.setValue(exertionWarningThresholdLength)
                    exertionThresholdAlertSettingsState.setValue(exertionThresholdAlert)
                    exertionAlertThresholdLengthSettingsState.setValue(exertionAlertThresholdLength)

                    navController.navigate("physiological_alerts_setting_screen") {
                        popUpTo(("physiological_alerts_setting_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    exertionThresholdWarningState.setValue(exertionThresholdWarningSettingsState.value)
                    exertionWarningThresholdLengthState.setValue(exertionWarningThresholdLengthSettingsState.value)
                    exertionThresholdAlertState.setValue(exertionThresholdAlertSettingsState.value)
                    exertionAlertThresholdLengthState.setValue(exertionAlertThresholdLengthSettingsState.value)

                    navController.navigate("physiological_alerts_setting_screen") {
                        popUpTo(("physiological_alerts_setting_screen")) {
                            inclusive = true
                        }
                    }
                })
        },
        text = "Exertion Alerts"
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
                            if (selectedColumn == column) colors.secondary
                            else colors.onBackground,
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
                Text(text = "'", style = textStyle, color = colors.onBackground)
                Spacer(Modifier.width(8.dp))
                Picker(
                    readOnly = selectedColumn != 1,
                    state = inchesState,
                    modifier = Modifier.size(64.dp, 100.dp),
                    contentDescription = inchesContentDescription,
                    option = { inch: Int -> Option(1, "%02d".format(inch)) },
                )
                Text(text = "''", style = textStyle, color = colors.onBackground)
            }
        },
        rowContent = {
            val feetIndex = feetInchesIndex(heightFeetSettingsState.value, feetState)
            val inchesIndex = feetInchesIndex(heightInchesSettingsState.value, inchesState)

            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "HeightPickerSetting Settings NOT saved")
                    navController.navigate("physiological_metrics_medical_profile_batdok") {
                        popUpTo(("physiological_metrics_medical_profile_batdok")) {
                            inclusive = true
                        }
                    } },
                onClickConfirm = {
                    heightFeetSettingsState.postValue(feetIndex+1)
                    heightInchesSettingsState.postValue(inchesIndex)
                    navController.navigate("physiological_metrics_medical_profile_batdok") {
                        popUpTo(("physiological_metrics_medical_profile_batdok")) {
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

//Used for User metrics and output protocol
@Composable
fun RadioChoiceSetting (
    navController: NavController,
    metricSettingState: MutableLiveData<String>,
    dataList: List<String>,
    title: String,
    route: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = 20.dp, bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val index = findRadioIndex(metricSettingState.value, dataList)
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
                    Log.d(TAG, "RadioChoiceSetting Settings NOT saved: $title")
                    navController.navigate(route) {
                        popUpTo((route)) {
                            inclusive = true
                        }
                    }                },
                onClickConfirm = {
                    metricSettingState.setValue(dataList[selectedRadioIndex])
                    Log.d(TAG, "RadioChoiceSetting Settings saved ($title): ${metricSettingState.value}")
                    navController.navigate(route) {
                        popUpTo((route)) {
                            inclusive = true
                        }
                    }
                })
        }
    }
}

fun findRadioIndex(dataName: String?, dataList: List<String>) : Int {
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
                    navController.navigate("physiological_metrics_medical_profile_batdok") {
                        popUpTo(("physiological_metrics_medical_profile_batdok")) {
                            inclusive = true
                        }
                    }                },
                onClickConfirm = {
                    val toSave = selectedIndices
                        .map { idx -> DataRepository.AllergyItem(allergiesOptionsList[idx]) }
                    allergyListSettingsState.postValue(toSave)
                    navController.navigate("physiological_metrics_medical_profile_batdok") {
                        popUpTo(("physiological_metrics_medical_profile_batdok")) {
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
            Chip(
                onClick = {
                    navController.navigate("physiological_metrics_medical_profile_batdok") {
                        popUpTo("physiological_metrics_medical_profile_batdok") { inclusive = true }
                    }
                },
                enabled = true,
                label = { Text(text = stringResource(R.string.medical_profile_batdok)) },
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
                    navController.navigate("physiological_metrics_gait_tracking") {
                        popUpTo("physiological_metrics_gait_tracking") { inclusive = true }
                    }
                },
                enabled = true,
                label = { Text(text = stringResource(R.string.gait_tracking)) },
                colors = ChipDefaults.chipColors(
                    backgroundColor = Color.DarkGray,
                    contentColor = Color.White,
                    secondaryContentColor = Color.LightGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Physiological Settings NOT saved")

                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {

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
fun PhysioMetricsMedicalProfileSetting(navController: NavController) {
    Log.d(TAG, "PhysioMetricsMedicalProfileSetting called")

    WearTakCivScreenDimensions(
        columnContent = {
            DataMetricsChip("physiological_metrics_birth_year_screen", stringResource(R.string.birth_year), birthYearSettingsState.value.toString(), navController)
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
                    Log.d(TAG, "Physio Metrics Med Profile Settings NOT saved")
                    birthYearState.setValue(birthYearValue)
                    heightFeetSettingsState.setValue(heightFeetValue)
                    heightInchesSettingsState.setValue(heightInchesValue)
                    weightSettingsState.setValue(weightValue)
                    sexSettingsState.setValue(sexValue)
                    bloodTypeSettingsState.setValue(bloodTypeValue)
                    //TODO: vv this might break
                    allergyListSettingsState.setValue(allergyList)
                    userTypeSettingsState.setValue(userTypeValue)
                    restingHeartRateSettingsState.setValue(restingHeartRate)

                    navController.navigate("physiological_metrics_setting_screen") {
                        popUpTo(("physiological_metrics_setting_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    birthYearState.setValue(birthYearSettingsState.value)
                    heightFeetState.setValue(heightFeetSettingsState.value)
                    heightInchesState.setValue(heightInchesSettingsState.value)
                    weightState.setValue(weightSettingsState.value)
                    sexState.setValue(sexSettingsState.value)
                    bloodTypeState.setValue(bloodTypeSettingsState.value)
                    allergyListState.setValue(allergyListSettingsState.value)
                    userTypeState.setValue(userTypeSettingsState.value)
                    restingHeartRateState.setValue(restingHeartRateSettingsState.value)

                    navController.navigate("physiological_metrics_setting_screen") {
                        popUpTo(("physiological_metrics_setting_screen")) {
                            inclusive = true
                        }
                    }
                })
        },
        text = stringResource(R.string.medical_profile_batdok)
    )
}

@Composable
fun PhysioMetricsGaitTrackingSettings(navController: NavController) {
    Log.d(TAG, "PhysioMetricsGaitTrackingSettings called")

    WearTakCivScreenDimensions(
        columnContent = {

            DataMetricsChip("physiological_metrics_uniform_waist_size", stringResource(R.string.uniform_waist_size_text), uniformWaistSizeSettingsState.value.toString() + "''", navController)
            DataMetricsChip("physiological_metrics_stride_length", stringResource(R.string.stride_length_text), strideLengthSettingsState.value.toString() + "''", navController)
            DataMetricsChip("physiological_metrics_uniform_pants_length", stringResource(R.string.uniform_pants_length_text), uniformPantsLengthSettingsState.value.toString() + "''", navController)
            DataMetricsChip("physiological_metrics_loadout_weight", stringResource(R.string.loadout_weight_text), loadoutWeightSettingsState.value.toString() + "lbs", navController)

            Spacer(modifier = Modifier.height(16.dp))
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Gait Tracking Settings NOT saved")
                    uniformWaistSizeSettingsState.setValue(uniformWaistSize)
                    strideLengthSettingsState.setValue(strideLength)
                    uniformPantsLengthSettingsState.setValue(uniformPantsLength)
                    loadoutWeightSettingsState.setValue(loadoutWeight)

                    navController.navigate("physiological_metrics_setting_screen") {
                        popUpTo(("physiological_metrics_setting_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    uniformWaistSizeState.setValue(uniformWaistSizeSettingsState.value)
                    strideLengthState.setValue(strideLengthSettingsState.value)
                    uniformPantsLengthState.setValue(uniformPantsLengthSettingsState.value)
                    loadoutWeightState.setValue(loadoutWeightSettingsState.value)

                    navController.navigate("physiological_metrics_setting_screen") {
                        popUpTo(("physiological_metrics_setting_screen")) {
                            inclusive = true
                        }
                    }
                })
        },
        text = stringResource(R.string.gait_tracking)
    )
}

// vv No longer used
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
        val parser = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.ENGLISH)
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

// ^^ No longer used

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
    val companionAppCheckedLive by enableCompanionAppServiceSettingsState.observeAsState()
    val chatCheckedLive by enableChatServiceSettingsState.observeAsState()
    val immersionAlertsCheckedLive by enableImmersionAlertsSettingsState.observeAsState()
    val batteryAlertsCheckedLive by enableBatteryAlertsSettingsState.observeAsState()
    val enableSmsCheckedLive by enableSmsForAlertsSettingsState.observeAsState()
    var smsListTextFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(smsNumbersListString)) }
    val physioCheckedLive by enablePhysiologicalServicesSettingsState.observeAsState()
    val hailCheckedLive by enableHailServiceSettingsState.observeAsState()
    val hailLicenseState = dataRepository.hailLicenseState.collectAsState(initial = null).value
    val batdokCheckedLive by enableBatdokCotSettingsState.observeAsState()
    val bloodhoundCompassCheckedLive by enableBloodhoundCompassServiceSettingsState.observeAsState()
    val displayedSecondaryText by saveBattOnWifiStringState.observeAsState()
    val onnxGunshotDetectCheckedLive by enableOnnxGunshotServiceSettingsState.observeAsState()
    val wearTakDevModeEnabled by dataRepository.getEnableWearTakDevMode
        .collectAsStateWithLifecycle(initialValue = false)

    // WearTAK Developer Debug
    val coroutineScope = rememberCoroutineScope()
    var versionTapCount by remember { mutableStateOf(0) }
    var lastVersionTapTime by remember { mutableStateOf(0L) }
    val tapsRequired = 8
    val timeoutMs = 1500L

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
                    navController.navigate("manage_outputs_screen") {
                        popUpTo("manage_outputs_screen") { inclusive = true }
                    }
                },
                enabled = true,
                label = { Text(text = stringResource(R.string.manage_outputs)) },
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


            /*
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
            */
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
                companionAppCheckedLive,
                { enableCompanionAppServiceSettingsState.postValue(it) },
                {},
                stringResource(R.string.companion_app),
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
                immersionAlertsCheckedLive,
                { enableImmersionAlertsSettingsState.postValue(it) },
                {},
                stringResource(R.string.immersion_alerts),
                stringResource(R.string.on_off)
            )
            Chip(
                onClick = {
                    navController.navigate("atm_pressure_alerts_screen") {
                        popUpTo("atm_pressure_alerts_screen") { inclusive = true }
                    }
                },
                enabled = true,
                label = { Text(text = stringResource(R.string.atm_pressure_alerts)) },
                colors = ChipDefaults.chipColors(
                    backgroundColor = Color.DarkGray,
                    contentColor = Color.White,
                    secondaryContentColor = Color.LightGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp)
            )

            if (wearTakDevModeEnabled) {
                SettingSwitchToggleLiveData(
                    onnxGunshotDetectCheckedLive,
                    { enableOnnxGunshotServiceSettingsState.postValue(it) },
                    {},
                    stringResource(R.string.gunshot_detection),
                    stringResource(R.string.service_on_off)
                )
            }

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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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

            SettingSwitchToggleLiveData(
                batteryAlertsCheckedLive,
                { enableBatteryAlertsSettingsState.postValue(it) },
                {},
                stringResource(R.string.battery_alerts),
                stringResource(R.string.on_off)
            )
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
            /*
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
             */
            SettingSwitchToggleLiveData(
                batdokCheckedLive,
                { enableBatdokCotSettingsState.postValue(it) },
                { Icon(
                    painter = painterResource(id = R.drawable.batdok),
                    tint = Color.Unspecified,
                    contentDescription = "null",
                    modifier = Modifier
                        .size(20.dp),
                ) },
                stringResource(R.string.batdok),
                stringResource(R.string.on_off)
            )
            Chip(
                onClick = {
                    //hrHighAlertBpmThresholdSettingsState.postValue(hrHighAlertBpmThresholdState.value)
                    //hrHighAlertNumSecsThresholdSettingsState.postValue(hrHighAlertNumSecsThresholdState.value)
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
                    birthYearSettingsState.postValue(birthYearState.value)
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

            var versionTapCount by remember { mutableStateOf(0) }
            var lastVersionTapTime by remember { mutableStateOf(0L) }

            DebugVersionText(
                versionNumber = versionNumber,
                dataRepository = dataRepository,
                onDebugToggled = { enabled ->
                    // Optional: if you want to immediately test something when debug is enabled
                    if (enabled) {
                        // e.g., trigger a manual battery-alert test hook
                        // triggerTestBatteryAlert()
                    }
                    setAirplaneMode("0")
                }
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
                    var batdokCheckedChanged: Boolean = false
                    var dittoCheckedChanged: Boolean = false
                    var chatCheckedChanged: Boolean = false
                    var immersionAlertsChanged = false
                    var lowPressureAlertChanged = false
                    var lowPressureAlertThresholdChanged = false
                    var highPressureAlertChanged = false
                    var highPressureAlertThresholdChanged = false
                    var onnxGunDetectChanged: Boolean = false
                    var batteryAlertsCheckedChanged: Boolean = false
                    var bloodhoundCompassCheckedChanged: Boolean = false
                    var dynamicReportingChanged: Boolean = false
                    var constantReportingIntervalChanged: Boolean = false
                    var stationaryReportingIntervalChanged: Boolean = false
                    var onFootReportingIntervalChanged: Boolean = false
                    var vehicleReportingIntervalChanged: Boolean = false
                    var whileAlertingReportingIntervalChanged: Boolean = false
                    var smsAlertsCheckedChanged: Boolean = false
                    var smsNumbersChanged: Boolean = false
                    var restingHeartRateHighThresholdChanged: Boolean = false
                    var highRestingHrWarningThresholdLengthChanged: Boolean = false
                    var highRestingHrAlertThresholdLengthChanged: Boolean = false
                    var restingHeartRateLowThresholdChanged: Boolean = false
                    var lowRestingHrWarningThresholdLengthChanged: Boolean = false
                    var lowRestingHrAlertThresholdLengthChanged: Boolean = false
                    var exertionWarningThresholdChanged: Boolean = false
                    var exertionWarningThresholdLengthChanged: Boolean = false
                    var exertionAlertThresholdChanged: Boolean = false
                    var exertionAlertThresholdLengthChanged: Boolean = false
                    var physiologicalAlertsCheckedChanged: Boolean = false
                    var saveBattOnWifiChanged = false
                    var takServerListChanged = false
                    var birthYearChanged: Boolean = false
                    var enableMulticastChanged = false
                    var multicastAddressChanged = false
                    var multicastOutputProtocolChanged = false
                    var multicastServerPortChanged = false
                    var enableTabletCommandChanged = false
                    var tabletCommandEndpointChanged = false
                    var tabletCommandAPIKeyChanged = false
                    var enableEsriChanged = false
                    var esriEndpointChanged = false
                    var sitxCheckedChanged = false
                    var sitxUrlChanged = false
                    var sitxClientIdChanged = false
                    var heightFeetChanged: Boolean = false
                    var heightInchesChanged: Boolean = false
                    var weightChanged: Boolean = false
                    var sexChanged: Boolean = false
                    var bloodTypeChanged: Boolean = false
                    var allergiesChanged: Boolean = false
                    var userTypeChanged: Boolean = false
                    var uniformWaistSizeChanged: Boolean = false
                    var strideLengthChanged: Boolean = false
                    var uniformPantsLengthChanged: Boolean = false
                    var loadoutWeightChanged: Boolean = false
                    var restingHeartRateChanged: Boolean = false
                    var companionAppCheckedChanged: Boolean = false

                    if (restingHrHighAlertBpmThreshold != restingHrHighAlertBpmThresholdState.value) {
                        settingsChanged = true; restingHeartRateHighThresholdChanged = true
                        restingHrHighAlertBpmThreshold = restingHrHighAlertBpmThresholdState.value ?: restingHrHighAlertBpmThreshold
                    }
                    if (highRestingHrWarningThresholdLength != highRestingHrWarningThresholdLengthState.value) {
                        settingsChanged = true; highRestingHrWarningThresholdLengthChanged = true
                        highRestingHrWarningThresholdLength = highRestingHrWarningThresholdLengthState.value ?: highRestingHrWarningThresholdLength
                    }
                    if (highRestingHrAlertThresholdLength != highRestingHrAlertThresholdLengthState.value) {
                        settingsChanged = true; highRestingHrAlertThresholdLengthChanged = true
                        highRestingHrAlertThresholdLength = highRestingHrAlertThresholdLengthState.value ?: highRestingHrAlertThresholdLength
                    }
                    if (restingHrLowAlertBpmThreshold != restingHrLowAlertBpmThresholdState.value) {
                        settingsChanged = true; restingHeartRateLowThresholdChanged = true
                        restingHrLowAlertBpmThreshold = restingHrLowAlertBpmThresholdState.value ?: restingHrLowAlertBpmThreshold
                    }
                    if (lowRestingHrWarningThresholdLength != lowRestingHrWarningThresholdLengthState.value) {
                        settingsChanged = true; lowRestingHrWarningThresholdLengthChanged = true
                        lowRestingHrWarningThresholdLength = lowRestingHrWarningThresholdLengthState.value ?: lowRestingHrWarningThresholdLength
                    }
                    if (lowRestingHrAlertThresholdLength != lowRestingHrAlertThresholdLengthState.value) {
                        settingsChanged = true; lowRestingHrAlertThresholdLengthChanged = true
                        lowRestingHrAlertThresholdLength = lowRestingHrAlertThresholdLengthState.value ?: lowRestingHrAlertThresholdLength
                    }
                    if (exertionThresholdWarning != exertionThresholdWarningState.value) {
                        settingsChanged = true; exertionWarningThresholdChanged = true
                        exertionThresholdWarning = exertionThresholdWarningState.value ?: exertionThresholdWarning
                    }
                    if (exertionWarningThresholdLength != exertionWarningThresholdLengthState.value) {
                        settingsChanged = true; exertionWarningThresholdLengthChanged = true
                        exertionWarningThresholdLength = exertionWarningThresholdLengthState.value ?: exertionWarningThresholdLength
                    }
                    if (exertionThresholdAlert != exertionThresholdAlertState.value) {
                        settingsChanged = true; exertionAlertThresholdChanged = true
                        exertionThresholdAlert = exertionThresholdAlertState.value ?: exertionThresholdAlert
                    }
                    if (exertionAlertThresholdLength != exertionAlertThresholdLengthState.value) {
                        settingsChanged = true; exertionAlertThresholdLengthChanged = true
                        exertionAlertThresholdLength = exertionAlertThresholdLengthState.value ?: exertionAlertThresholdLength
                    }
                    if (birthYearValue != birthYearState.value) {
                        settingsChanged = true; birthYearChanged = true
                        birthYearValue = birthYearState.value ?: birthYearValue
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
                    if (uniformWaistSize != uniformWaistSizeState.value) {
                        settingsChanged = true; uniformWaistSizeChanged = true
                        uniformWaistSize = uniformWaistSizeState.value ?: uniformWaistSize
                    }
                    if (strideLength != strideLengthState.value) {
                        settingsChanged = true; strideLengthChanged = true
                        strideLength = strideLengthState.value ?: strideLength
                    }
                    if (uniformPantsLength != uniformPantsLengthState.value) {
                        settingsChanged = true; uniformPantsLengthChanged = true
                        uniformPantsLength = uniformPantsLengthState.value ?: uniformPantsLength
                    }
                    if (loadoutWeight != loadoutWeightState.value) {
                        settingsChanged = true; loadoutWeightChanged = true
                        loadoutWeight = loadoutWeightState.value ?: loadoutWeight
                    }

                    if (enableCompanionAppService != enableCompanionAppServiceSettingsState.value) {
                        settingsChanged = true; companionAppCheckedChanged = true
                        enableCompanionAppService =
                            enableCompanionAppServiceSettingsState.value ?: enableCompanionAppService
                        runBlocking {
                            // keep SettingsState in sync with whatever we ended up using
                            enableCompanionAppServiceSettingsState.postValue(enableCompanionAppService)
                        }
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

                    if (enableMulticastOnWifi != enableMulticastSettingsState.value) {
                        settingsChanged = true; enableMulticastChanged = true
                        enableMulticastOnWifi = enableMulticastSettingsState.value ?: enableMulticastOnWifi
                    }
                    if (multicastAddress != multicastAddressSettingsState.value) {
                        settingsChanged = true; multicastAddressChanged = true
                        multicastAddress = multicastAddressSettingsState.value ?: multicastAddress
                    }
                    if (multicastProtocol != multicastProtocolSettingsState.value) {
                        settingsChanged = true; multicastOutputProtocolChanged = true
                        multicastProtocol = multicastProtocolSettingsState.value ?: multicastProtocol
                    }
                    if (multicastPort != multicastPortSettingsState.value) {
                        settingsChanged = true; multicastServerPortChanged = true
                        multicastPort = multicastPortSettingsState.value ?: multicastPort
                    }
                    if (enableTabletCommandEndpoint != enableTabletCommandEndpointSettingsState.value) {
                        settingsChanged = true; enableTabletCommandChanged = true
                        enableTabletCommandEndpoint = enableTabletCommandEndpointSettingsState.value ?: enableTabletCommandEndpoint
                    }
                    if (tabletCommandEndpoint != tabletCommandEndpointSettingsState.value) {
                        settingsChanged = true; tabletCommandEndpointChanged = true
                        tabletCommandEndpoint = tabletCommandEndpointSettingsState.value ?: tabletCommandEndpoint
                    }
                    if (tabletCommandApikey != tabletCommandApikeySettingsState.value) {
                        settingsChanged = true; tabletCommandAPIKeyChanged = true
                        tabletCommandApikey = tabletCommandApikeySettingsState.value ?: tabletCommandApikey
                    }
                    if (enableEsriEndpoint != enableEsriEndpointSettingsState.value) {
                        settingsChanged = true; enableEsriChanged = true
                        enableEsriEndpoint = enableEsriEndpointSettingsState.value ?: enableEsriEndpoint
                    }
                    if (esriEndpoint != esriEndpointSettingsState.value) {
                        settingsChanged = true; esriEndpointChanged = true
                        esriEndpoint = esriEndpointSettingsState.value ?: esriEndpoint
                    }

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

                    if (enableSitxService != enableSitxServiceSettingsState.value) {
                        settingsChanged = true; sitxCheckedChanged = true
                        enableSitxService = enableSitxServiceSettingsState.value ?: enableSitxService
                        runBlocking {
                            enableSitxServiceSettingsState.postValue(enableSitxService)
                        }
                    }
                    if (sitxTakUrl != sitxTakUrlSettingsState.value) {
                        settingsChanged = true; sitxUrlChanged = true
                        sitxTakUrl = sitxTakUrlSettingsState.value ?: sitxTakUrl
                    }
                    if (sitxTakClientId != sitxTakClientIdSettingsState.value) {
                        settingsChanged = true; sitxClientIdChanged = true
                        sitxTakClientId = sitxTakClientIdSettingsState.value ?: sitxTakClientId
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

                    }
                    if (enableImmersionAlerts != enableImmersionAlertsSettingsState.value) {
                        settingsChanged = true; immersionAlertsChanged = true
                        enableImmersionAlerts = enableImmersionAlertsSettingsState.value ?: enableImmersionAlerts
                        runBlocking {
                            enableImmersionAlertsSettingsState.postValue(enableImmersionAlerts)
                        }
                    }
                    if (enableLowAtmPressureAlert != enableLowAtmPressureAlertSettingsState.value) {
                        settingsChanged = true; lowPressureAlertChanged = true
                        enableLowAtmPressureAlert = enableLowAtmPressureAlertSettingsState.value ?: enableLowAtmPressureAlert
                        runBlocking {
                            enableLowAtmPressureAlertSettingsState.postValue(enableLowAtmPressureAlert)
                        }
                    }
                    if (lowAtmPressureAlertThreshold != lowAtmPressureAlertThresholdSettingsState.value) {
                        settingsChanged = true; lowPressureAlertThresholdChanged = true
                        lowAtmPressureAlertThreshold = lowAtmPressureAlertThresholdSettingsState.value ?: lowAtmPressureAlertThreshold
                        runBlocking {
                            lowAtmPressureAlertThresholdSettingsState.postValue(lowAtmPressureAlertThreshold)
                        }
                    }
                    if (enableHighAtmPressureAlert != enableHighAtmPressureAlertSettingsState.value) {
                        settingsChanged = true; highPressureAlertChanged = true
                        enableHighAtmPressureAlert = enableHighAtmPressureAlertSettingsState.value ?: enableHighAtmPressureAlert
                        runBlocking {
                            enableHighAtmPressureAlertSettingsState.postValue(enableHighAtmPressureAlert)
                        }
                    }
                    if (highAtmPressureAlertThreshold != highAtmPressureAlertThresholdSettingsState.value) {
                        settingsChanged = true; highPressureAlertThresholdChanged = true
                        highAtmPressureAlertThreshold = highAtmPressureAlertThresholdSettingsState.value ?: highAtmPressureAlertThreshold
                        runBlocking {
                            highAtmPressureAlertThresholdSettingsState.postValue(highAtmPressureAlertThreshold)
                        }
                    }

                    if (enableOnnxGunshotService != enableOnnxGunshotServiceSettingsState.value) {
                        settingsChanged = true; onnxGunDetectChanged = true
                        enableOnnxGunshotService = enableOnnxGunshotServiceSettingsState.value ?: enableOnnxGunshotService
                        runBlocking {
                            enableOnnxGunshotServiceSettingsState.postValue(enableOnnxGunshotService)
                        }
                    }

                    if (enableBatteryAlerts != enableBatteryAlertsSettingsState.value) {
                        settingsChanged = true; batteryAlertsCheckedChanged = true
                        enableBatteryAlerts = enableBatteryAlertsSettingsState.value
                            ?: enableBatteryAlerts
                        runBlocking {
                            enableBatteryAlertsSettingsState.postValue(enableBatteryAlerts)
                        }
                    }

                    if (enableBloodhoundCompassService != enableBloodhoundCompassServiceSettingsState.value) {
                        settingsChanged = true; bloodhoundCompassCheckedChanged = true
                        enableBloodhoundCompassService = enableBloodhoundCompassServiceSettingsState.value ?: enableBloodhoundCompassService
                        runBlocking {
                            enableBloodhoundCompassServiceSettingsState.postValue(enableBloodhoundCompassService)
                        }
                        requestBloodhoundComplicationUpdate(context)
                    }

                    if (enableHailService != enableHailServiceSettingsState.value) {
                        settingsChanged = true; hailCheckedChanged = true
                        enableHailService = enableHailServiceSettingsState.value ?: enableHailService
                        runBlocking {
                            // This is in case enableLocationService above is NOT set to enableLocationServiceSettingsState.value
                            enableHailServiceSettingsState.postValue(enableHailService)
                        }
                    }

                    if (enableBatdokCot != enableBatdokCotSettingsState.value) {
                        settingsChanged = true; batdokCheckedChanged = true
                        enableBatdokCot = enableBatdokCotSettingsState.value ?: enableBatdokCot
                        runBlocking {
                            enableBatdokCotSettingsState.postValue(enableBatdokCot)
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
                        requestPhysioStatusAndAlertingComplicationUpdate(context)

                    }

                    if (enablePhysiologicalAlerts != enablePhysiologicalAlertsSettingState.value) {
                        settingsChanged = true; physiologicalAlertsCheckedChanged = true
                        enablePhysiologicalAlerts = enablePhysiologicalAlertsSettingState.value ?: enablePhysiologicalAlerts
                        runBlocking {
                            enablePhysiologicalAlertsSettingState.postValue(enablePhysiologicalAlerts)
                        }
                        requestPhysioStatusAndAlertingComplicationUpdate(context)
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
                            if (birthYearChanged) {
                                Log.d(TAG, "Storing Birth Year ($birthYearValue)")
                                dataRepository.storeYearOfBirth(birthYearValue)
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
                            if (uniformWaistSizeChanged) {
                                Log.d(TAG, "Storing Uniform Waist Size ($uniformWaistSize)")
                                dataRepository.storePhysiologicalMetricUniformWaistSize(uniformWaistSize)
                            }
                            if (strideLengthChanged) {
                                Log.d(TAG, "Storing Stride Length ($strideLength)")
                                dataRepository.storePhysiologicalMetricStrideLength(strideLength)
                            }
                            if (uniformPantsLengthChanged) {
                                Log.d(TAG, "Storing Uniform Pants Length ($uniformPantsLength)")
                                dataRepository.storePhysiologicalMetricUniformPantsLength(uniformPantsLength)
                            }
                            if (loadoutWeightChanged) {
                                Log.d(TAG, "Storing Loadout Weight ($loadoutWeight)")
                                dataRepository.storePhysiologicalMetricLoadoutWeight(loadoutWeight)
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

                            if(enableMulticastChanged){
                                Log.d(TAG, "Storing updated Enable Multicast: $enableMulticastOnWifi")
                                dataRepository.storeEnableMulticast(enableMulticastOnWifi)
                            }
                            if (multicastAddressChanged) {
                                Log.d(TAG, "Storing updated Multicast Address: $multicastAddress")
                                dataRepository.storeMulticastAddress(multicastAddress)
                            }
                            if (multicastOutputProtocolChanged) {
                                Log.d(TAG, "Storing updated Multicast Output Protocol: $multicastProtocol")
                                dataRepository.storeMulticastProtocol(multicastProtocol)
                            }
                            if (multicastServerPortChanged) {
                                Log.d(TAG, "Storing updated Multicast Server Port: $multicastPort")
                                dataRepository.storeMulticastPort(multicastPort)
                            }
                            if (enableTabletCommandChanged){
                                Log.d(TAG, "Storing updated Enable Tablet Command: $enableTabletCommandEndpoint")
                                dataRepository.storeEnableTabletCommandEndpoint(enableTabletCommandEndpoint)
                            }
                            if (tabletCommandEndpointChanged) {
                                Log.d(TAG, "Storing updated Tablet Command Endpoint: $tabletCommandEndpoint")
                                dataRepository.storeTabletCommandEndpoint(tabletCommandEndpoint)
                            }
                            if (tabletCommandAPIKeyChanged) {
                                Log.d(TAG, "Storing updated Tablet Command API Key: $tabletCommandApikey")
                                dataRepository.storeTabletCommandApikey(tabletCommandApikey)
                            }
                            if (enableEsriChanged){
                                Log.d(TAG, "Storing updated Enable Esri: $enableEsriEndpoint")
                                dataRepository.storeEnableEsriEndpoint(enableEsriEndpoint)
                            }
                            if (esriEndpointChanged) {
                                Log.d(TAG, "Storing updated ESRI Endpoint: $esriEndpoint")
                                dataRepository.storeEsriEndpoint(esriEndpoint)
                            }
                            if (locationCheckedChanged) {
                                Log.d(TAG, "Storing enableLocationService $enableLocationService")
                                dataRepository.storeEnableLocationService(enableLocationService)
                            }
                            if (dittoCheckedChanged) {
                                Log.d(TAG, "Storing enableDittoService $enableDittoService")
                                dataRepository.storeEnableDittoService(enableDittoService)
                            }
                            if (sitxCheckedChanged) {
                                Log.d(TAG, "Storing enableSitxService $enableSitxService")
                                dataRepository.storeEnableSitxService(enableSitxService)
                            }
                            if (sitxUrlChanged) {
                                Log.d(TAG, "Storing Sitx URL: $sitxTakUrl")
                                sitxRepo.setBaseUrl(sitxTakUrl)
                                // TODO Senni: Need to restart the device authorization
                            }
                            if (sitxClientIdChanged) {
                                Log.d(TAG, "Storing Sitx Client ID: $sitxTakClientId")
                                sitxRepo.setClientId(sitxTakClientId)
                                // TODO Senni: Need to restart the device authorization
                            }
                            if (companionAppCheckedChanged) {
                                Log.d(TAG, "Storing enableCompanionAppService $enableCompanionAppService")
                                dataRepository.storeEnableCompanionAppService(enableCompanionAppService)
                            }
                            if (chatCheckedChanged) {
                                Log.d(TAG, "Storing enableChatService $enableChatService")
                                dataRepository.storeEnableChatService(enableChatService)
                            }
                            if (immersionAlertsChanged) {
                                Log.d(TAG, "Storing enableImmersionAlerts $enableImmersionAlerts")
                                dataRepository.storeEnableImmersionAlerts(enableImmersionAlerts)
                            }
                            if (lowPressureAlertChanged) {
                                Log.d(TAG, "Storing enableLowAtmPressureAlert $enableLowAtmPressureAlert")
                                dataRepository.storeEnableLowAtmPressureAlert(enableLowAtmPressureAlert)
                            }
                            if (lowPressureAlertThresholdChanged) {
                                Log.d(TAG, "Storing lowAtmPressureAlertThreshold $lowAtmPressureAlertThreshold")
                                dataRepository.storeLowAtmPressureAlertThreshold(lowAtmPressureAlertThreshold)
                            }
                            if (highPressureAlertChanged) {
                                Log.d(TAG, "Storing enableHighAtmPressureAlert $enableHighAtmPressureAlert")
                                dataRepository.storeEnableHighAtmPressureAlert(enableHighAtmPressureAlert)
                            }
                            if (highPressureAlertThresholdChanged) {
                                Log.d(TAG, "Storing highAtmPressureAlertThreshold $highAtmPressureAlertThreshold")
                                dataRepository.storeHighAtmPressureAlertThreshold(highAtmPressureAlertThreshold)
                            }
                            if (onnxGunDetectChanged) {
                                Log.d(TAG, "Storing enableOnnxGunshotService $enableOnnxGunshotService")
                                dataRepository.storeEnableOnnxGunshotService(enableOnnxGunshotService)
                            }
                            if (batteryAlertsCheckedChanged) {
                                Log.d(TAG, "Storing enableBatteryAlerts $enableBatteryAlerts")
                                dataRepository.storeEnableBatteryAlerts(enableBatteryAlerts)
                            }
                            if (bloodhoundCompassCheckedChanged) {
                                Log.d(TAG, "Storing enableBloodhoundCompassService $enableBloodhoundCompassService")
                                dataRepository.storeEnableBloodhoundCompassService(enableBloodhoundCompassService)
                            }
                            if (hailCheckedChanged) {
                                Log.d(TAG, "Storing enableHailService $enableHailService")
                                dataRepository.storeEnableHailService(enableHailService)
                            }
                            if (batdokCheckedChanged) {
                                Log.d(TAG, "Storing enableBatdokCot $enableBatdokCot")
                                dataRepository.storeEnableBatdokCot(enableBatdokCot)
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

                            if (restingHeartRateHighThresholdChanged) {
                                Log.d(TAG, "Storing restingHrHighAlertBpmThreshold $restingHrHighAlertBpmThreshold")
                                dataRepository.storeRestingHrHighAlertBpmThreshold(restingHrHighAlertBpmThreshold)
                            }
                            if (highRestingHrWarningThresholdLengthChanged) {
                                Log.d(TAG, "Storing highRestingHrWarningThresholdLength $highRestingHrWarningThresholdLength")
                                dataRepository.storeHighRestingHrWarningThresholdLength(highRestingHrWarningThresholdLength)
                            }
                            if (highRestingHrAlertThresholdLengthChanged) {
                                Log.d(TAG, "Storing highRestingHrAlertThresholdLength $highRestingHrAlertThresholdLength")
                                dataRepository.storeHighRestingHrAlertThresholdLength(highRestingHrAlertThresholdLength)
                            }
                            if (restingHeartRateLowThresholdChanged) {
                                Log.d(TAG, "Storing hrHighAlertBpmThreshold $restingHrLowAlertBpmThreshold")
                                dataRepository.storeRestingHrLowAlertBpmThreshold(restingHrLowAlertBpmThreshold)
                            }
                            if (lowRestingHrWarningThresholdLengthChanged) {
                                Log.d(TAG, "Storing lowRestingHrWarningThresholdLength $lowRestingHrWarningThresholdLength")
                                dataRepository.storeLowRestingHrWarningThresholdLength(lowRestingHrWarningThresholdLength)
                            }
                            if (lowRestingHrAlertThresholdLengthChanged) {
                                Log.d(TAG, "Storing lowRestingHrAlertThresholdLength $lowRestingHrAlertThresholdLength")
                                dataRepository.storeLowRestingHrAlertThresholdLength(lowRestingHrAlertThresholdLength)
                            }
                            if (exertionWarningThresholdChanged) {
                                Log.d(TAG, "Storing exertionThresholdWarning $exertionThresholdWarning")
                                dataRepository.storeExertionThresholdWarning(exertionThresholdWarning)
                            }
                            if (exertionWarningThresholdLengthChanged) {
                                Log.d(TAG, "Storing exertionWarningThresholdLength $exertionWarningThresholdLength")
                                dataRepository.storeExertionWarningThresholdLength(exertionWarningThresholdLength)
                            }
                            if (exertionAlertThresholdChanged) {
                                Log.d(TAG, "Storing exertionThresholdAlert $exertionThresholdAlert")
                                dataRepository.storeExertionThresholdAlert(exertionThresholdAlert)
                            }
                            if (exertionAlertThresholdLengthChanged) {
                                Log.d(TAG, "Storing exertionAlertThresholdLength $exertionAlertThresholdLength")
                                dataRepository.storeExertionAlertThresholdLength(exertionAlertThresholdLength)
                            }

                            if (restingHeartRateChanged ||
                                restingHeartRateHighThresholdChanged ||
                                highRestingHrWarningThresholdLengthChanged ||
                                highRestingHrAlertThresholdLengthChanged ||
                                restingHeartRateLowThresholdChanged ||
                                lowRestingHrWarningThresholdLengthChanged ||
                                lowRestingHrAlertThresholdLengthChanged ||
                                exertionWarningThresholdChanged ||
                                exertionWarningThresholdLengthChanged ||
                                exertionAlertThresholdChanged ||
                                exertionAlertThresholdLengthChanged) {
                                Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(MessageEvent(HR_ALERT_THRESHOLDS_CHANGED, if (enablePhysiologicalAlerts) {1} else {0}))}, EVENT_SEND_DELAY_MS)
                            }
                            if (restingHeartRateChanged ||
                                restingHeartRateHighThresholdChanged ||
                                highRestingHrWarningThresholdLengthChanged ||
                                highRestingHrAlertThresholdLengthChanged ||
                                restingHeartRateLowThresholdChanged ||
                                lowRestingHrWarningThresholdLengthChanged ||
                                lowRestingHrAlertThresholdLengthChanged) {
                                CepProvider.getOrCreateEngine(context).updateHrProfile()
                            }
                            if (exertionWarningThresholdChanged || exertionAlertThresholdChanged) {
                                CepProvider.getOrCreateEngine(context).updateHrrProfile()
                            }
                            if (highRestingHrWarningThresholdLengthChanged ||
                                highRestingHrAlertThresholdLengthChanged ||
                                lowRestingHrWarningThresholdLengthChanged ||
                                lowRestingHrAlertThresholdLengthChanged ||
                                exertionWarningThresholdLengthChanged ||
                                exertionAlertThresholdLengthChanged) {
                                mainActivity.updateCepSustainOverridesMap()
                                CepProvider.getOrCreateEngine(context).updateSustainOverrides()
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

@Composable
fun DebugVersionText(
    versionNumber: String,
    dataRepository: DataRepository,
    onDebugToggled: ((Boolean) -> Unit)? = null, // optional hook if you want to do extra stuff
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colors = colors

    val devModeEnabled by MainActivity.Constants.dataRepository
        .getEnableWearTakDevMode
        .collectAsState(false)

    var versionTapCount by remember { mutableStateOf(0) }
    var lastVersionTapTime by remember { mutableStateOf(0L) }
    var currentToast by remember { mutableStateOf<Toast?>(null) }

    val tapsRequired = 8
    val timeoutMs = 1500L   // max gap between taps

    fun showTopToast(message: String) {
        // Cancel any old toast so they don't queue up
        currentToast?.cancel()

        val toast = Toast.makeText(context, message, Toast.LENGTH_SHORT)
        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 50)
        toast.show()
        currentToast = toast
    }

    Text(
        modifier = Modifier
            .padding(top = 7.dp)
            .clickable {
                val now = System.currentTimeMillis()

                // Reset sequence if taps are too slow
                if (now - lastVersionTapTime > timeoutMs) {
                    versionTapCount = 0
                }

                versionTapCount++
                lastVersionTapTime = now

                // Countdown toasts on taps 5, 6, 7
                if (versionTapCount in 5 until tapsRequired) {
                    val remaining = tapsRequired - versionTapCount
                    val msg = if (remaining == 1) {
                        "1 more tap to toggle debug"
                    } else {
                        "$remaining more taps to toggle debug"
                    }
                    showTopToast(msg)
                }

                if (versionTapCount >= tapsRequired) {
                    versionTapCount = 0

                    coroutineScope.launch {
                        // Read & toggle flag off main thread
                        val currentlyEnabled =
                            dataRepository.getEnableWearTakDevMode.first()
                        val newValue = !currentlyEnabled

                        dataRepository.storeEnableWearTakDevMode(newValue)

                        Log.d(
                            TAG,
                            "~~~ DEBUG MODE ${if (newValue) "ENABLED" else "DISABLED"} ~~~"
                        )

                        withContext(Dispatchers.Main) {
                            val toastText = if (newValue) {
                                "Debug tools enabled (airplane mode off)"
                            } else {
                                "Debug tools disabled (airplane mode off)"
                            }
                            showTopToast(toastText)
                        }

                        // Optional hook if you want to e.g. trigger test alerts on enable
                        onDebugToggled?.invoke(newValue)
                    }
                }
            },
        text = stringResource(R.string.version, versionNumber),
        fontSize = 11.sp,
        color = if (devModeEnabled) Color.Red else colors.primary,
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

    RadioChoiceSetting(rememberSwipeDismissableNavController(), licenseState, sexOptionsList, "Blood Type", "physiological_metrics_setting_screen")
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

@Preview(widthDp = 192, heightDp = 800, showBackground = true)
@Composable
fun PhysioAlertsPreview() {
    HeartRateAlertsSetting(rememberSwipeDismissableNavController())
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
