/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.presentation

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.provider.Settings.Secure
import android.text.format.DateFormat
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getString
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.curvedRow
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.curvedText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.DEFAULT_AGE
import com.aitech.weartak_civ.DEFAULT_ON_FOOT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_RESTING_HR
import com.aitech.weartak_civ.DEFAULT_STATIONARY_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_VEHICLE_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils.SettingsUtils.CategoryRolesEntry
import com.aitech.weartak_civ.Utils.SettingsUtils.defaultRoleCategoryEntriesFromRaw
import com.aitech.weartak_civ.Utils.isStringEmptyJson
import com.aitech.weartak_civ.Utils.listEqualsIgnoreOrder
import com.aitech.weartak_civ.Utils.secureRandom
import com.aitech.weartak_civ.complication.CallsignComplicationService
import com.aitech.weartak_civ.complication.ChatComplicationService
import com.aitech.weartak_civ.complication.ChatComplicationService.Companion.CHAT_DISABLED
import com.aitech.weartak_civ.complication.ChatComplicationService.Companion.CHAT_ENABLED_NEW_MESSAGE
import com.aitech.weartak_civ.complication.ChatComplicationService.Companion.CHAT_ENABLED_NO_NEW_MESSAGE
import com.aitech.weartak_civ.complication.StatusIconsComplicationService
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.CALLSIGN_TEAM_ROLE_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.HR_ALERT_THRESHOLDS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.LOCALIZATION_BEACONS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.PHYSIOLOGICAL_ALERTS_ENABLED_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_ADDED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_CHANGED
import com.aitech.weartak_civ.manager.NetworkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MainActivity.Constants.chatComplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableChatServiceSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationServiceSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalServicesSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.networkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takConnectService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamEntriesValuesList
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.ActivityRecognitionService
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import com.aitech.weartak_civ.service.BatteryService
import com.aitech.weartak_civ.service.BleBeaconService
import com.aitech.weartak_civ.service.ChatService
import com.aitech.weartak_civ.service.CotDispatchService
import com.aitech.weartak_civ.service.CustomAccessibilityService
import com.aitech.weartak_civ.service.DittoService
import com.aitech.weartak_civ.service.HeartrateService
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.LicenseManager.validateLicense
import com.aitech.weartak_civ.service.LocationService
import com.aitech.weartak_civ.service.OffBodyDetectService
import com.aitech.weartak_civ.service.PowerManagerService
import com.aitech.weartak_civ.service.ReportingIntervalStrategyService
import com.aitech.weartak_civ.service.ReportingIntervalStrategyService.Companion.calcDynamicReportingInterval
import com.aitech.weartak_civ.service.SkinTempService
import com.aitech.weartak_civ.service.TAKConnectService
import com.beust.klaxon.Klaxon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import com.aitech.weartak_civ.DEFAULT_BATT_ALERT_PERCENTAGE
import com.aitech.weartak_civ.DEFAULT_BATT_WARNING_PERCENTAGE
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTING_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTION_FAILED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_DISCONNECTED_STRING
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.aitech.weartak_civ.service.PassiveDataService
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalAlertsSettingState
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.aitech.weartak_civ.service.ReactiveServiceManager
import com.aitech.weartak_civ.service.ReactiveServiceManager.ServiceType
import com.aitech.weartak_civ.Utils.getNullableBoolean
import com.aitech.weartak_civ.WearTakApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.EnumMap
import kotlin.math.min


class MainActivity : ComponentActivity() {

    companion object Constants {
        val TAG = "WearTAK-" + MainActivity::class.java.simpleName
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        const val EVENT_SEND_DELAY_MS = 1000L
        const val MOVEMENT_DETECTION_SERVICE_START_DELAY = 20_000L
        lateinit var mainActivity : MainActivity
        var mainActivityIsInit : MainActivity? = null
        lateinit var device_uid : String
        var callsign = "WEAROS-0"
        var enableLocationService : Boolean =  true
        var enableLocationServiceSettingsState = MutableLiveData<Boolean>(true)
        var enableBleBeaconService : Boolean = false
        var localizationBleBeaconItemList : List<DataRepository.LocalizationBleBeaconItem> = listOf()
        var enablePhysiologicalServices : Boolean = true
        var enablePhysiologicalServicesSettingsState = MutableLiveData<Boolean>(true)
        var enableHeartrateService : Boolean = true
        var enableActivityRecognitionService: Boolean = false
        var enableSkinTempService: Boolean = true
        var enableDittoService : Boolean = false
        var enableDittoServiceSettingsState = MutableLiveData<Boolean>(false)
        var enableChatService : Boolean =  false
        var enableChatServiceSettingsState = MutableLiveData<Boolean>(false)
        var enableHailService: Boolean = false
        var enableHailServiceSettingsState = MutableLiveData<Boolean>(false)
        var enableMulticastOnWifi: Boolean = true
        var enableBatdokCot: Boolean = true

        //Enable Global Health Events
        var enablePassiveDataService = false

        var connectivityStatusComplication : String = ""
        var takServerStatusComplication : String = ""
        var chatComplication : String = ""

        var physioAlertComplication : String = ""


        var enableCellularService: Boolean = true
        var isConnected : Boolean = true
        lateinit var dataRepository: DataRepository
        @SuppressLint("StaticFieldLeak")
        var networkManager : NetworkManager? = null
        var takConnectService : TAKConnectService? = null
        private val klaxon = Klaxon()

        var team = ""
        // Unlike team and teamValue, role does not have a roleValue. So removing roleValue. Please use role wherever roleValue was previously used.
        var role = ""
        var categoryRolesEntryLists: List<CategoryRolesEntry> = emptyList()
        val categorySettingsState = MutableLiveData<String>("")
        var category = ""
        var license = "License"
        var teamValue = ""
        var dittoAppId = ""
        var dittoAuthToken = ""
        lateinit var teamEntriesValuesList : TeamsJson
        var callsignSettingsState = MutableLiveData<String>("WEAROS-0")
        val teamState = MutableLiveData<String>("")
        val teamSettingsState = MutableLiveData<String>("")
        val roleState = MutableLiveData<String>("")
        val roleSettingsState = MutableLiveData<String>("")
        val licenseSettingsState = MutableLiveData<String>("")

        var battWarnPercent = DEFAULT_BATT_WARNING_PERCENTAGE
        var battAlertPercent = DEFAULT_BATT_ALERT_PERCENTAGE
        var ageValue = DEFAULT_AGE
        val ageState = MutableLiveData<Int>(DEFAULT_AGE)
        val ageSettingsState = MutableLiveData<Int>(DEFAULT_AGE)
        var heightFeetValue = 6
        val heightFeetState = MutableLiveData<Int>(6)
        val heightFeetSettingsState = MutableLiveData<Int>(6)
        var heightInchesValue = 0
        val heightInchesState = MutableLiveData<Int>(0)
        val heightInchesSettingsState = MutableLiveData<Int>(0)
        var weightValue = 180
        val weightState = MutableLiveData<Int>(180)
        val weightSettingsState = MutableLiveData<Int>(180)
        var sexValue = "N/A"
        val sexState = MutableLiveData<String>("N/A")
        val sexSettingsState = MutableLiveData<String>("N/A")
        var bloodTypeValue = "N/A"
        val bloodTypeState = MutableLiveData<String>("N/A")
        val bloodTypeSettingsState = MutableLiveData<String>("N/A")
        var allergyList : List<DataRepository.AllergyItem> = listOf(DataRepository.AllergyItem(name="N/A"))
        val allergyListState = MutableLiveData<List<DataRepository.AllergyItem>>(listOf(DataRepository.AllergyItem("N/A")))
        val allergyListSettingsState = MutableLiveData<List<DataRepository.AllergyItem>>(listOf(DataRepository.AllergyItem("N/A")))
        var restingHeartRate = DEFAULT_RESTING_HR
        val restingHeartRateState = MutableLiveData<Int>(DEFAULT_RESTING_HR)
        val restingHeartRateSettingsState = MutableLiveData<Int>(DEFAULT_RESTING_HR)
        var userTypeValue = "N/A"
        val userTypeState = MutableLiveData<String>("N/A")
        val userTypeSettingsState = MutableLiveData<String>("N/A")

        var takServerList : List<DataRepository.TakServerItem> = listOf()
        val takServerListState = MutableLiveData<List<DataRepository.TakServerItem>>(listOf())

        var SOME_WIFI_NETWORKS = "Some WiFi Networks"
        var ALL_WIFI_NETWORKS = "All WiFi Networks"
        var NO_WIFI_NETWORKS = "No WiFi Networks"
        var saveBattOnWifiString: String = ALL_WIFI_NETWORKS
        val saveBattOnWifiStringState = MutableLiveData<String>(ALL_WIFI_NETWORKS)
        var enableWifiBatteryOptimization : Boolean = true
        val enableWifiBatteryOptimizationState = MutableLiveData<Boolean>(true)
        val enableWifiBatteryOptimizationSettingsState = MutableLiveData<Boolean>(true)
        var enableAllWifiBatteryOptimization : Boolean = true
        val enableAllWifiBatteryOptimizationState = MutableLiveData<Boolean>(true)
        val enableAllWifiBatteryOptimizationSettingsState = MutableLiveData<Boolean>(true)
        var wifiSsidList : List<DataRepository.WifiSsidItem> = listOf()
        val wifiSsidListState = MutableLiveData<List<DataRepository.WifiSsidItem>>(listOf())
        val wifiSsidListSettingsState = MutableLiveData<List<DataRepository.WifiSsidItem>>(listOf()) // This is the value that is obtained on clicking yes to the editSsidList (the 1 level above main Settings UI)
        var enableTakEndpoint : Boolean = true
        var enableEsriEndpoint : Boolean = false
        var esriEndpoint : String = ""
        var esriReportingUpdateInterval : Int = DEFAULT_REPORTING_INTERVAL
        var enableTabletCommandEndpoint: Boolean = false
        var tabletCommandEndpoint : String = ""
        var tabletCommandApikey : String = ""
        var tabletCommandReportingUpdateInterval : Int = DEFAULT_REPORTING_INTERVAL

        var enableDynamicReportingStrategy = false
        val enableDynamicReportingStrategyState = MutableLiveData<Boolean>(false)
        val enableDynamicReportingStrategySettingsState = MutableLiveData<Boolean>(false)
        var constantReportingInterval = DEFAULT_REPORTING_INTERVAL
        val constantReportingIntervalState = MutableLiveData<Int>(DEFAULT_REPORTING_INTERVAL)
        val constantReportingIntervalSettingsState = MutableLiveData<Int>(DEFAULT_REPORTING_INTERVAL)
        var stationaryReportingInterval = DEFAULT_STATIONARY_REPORTING_INTERVAL
        val stationaryReportingIntervalState = MutableLiveData<Int>(DEFAULT_STATIONARY_REPORTING_INTERVAL)
        val stationaryReportingIntervalSettingsState = MutableLiveData<Int>(DEFAULT_STATIONARY_REPORTING_INTERVAL)
        var onFootReportingInterval = DEFAULT_ON_FOOT_REPORTING_INTERVAL
        val onFootReportingIntervalState = MutableLiveData<Int>(DEFAULT_ON_FOOT_REPORTING_INTERVAL)
        val onFootReportingIntervalSettingsState = MutableLiveData<Int>(DEFAULT_ON_FOOT_REPORTING_INTERVAL)
        var vehicleReportingInterval = DEFAULT_VEHICLE_REPORTING_INTERVAL
        val vehicleReportingIntervalState = MutableLiveData<Int>(DEFAULT_VEHICLE_REPORTING_INTERVAL)
        val vehicleReportingIntervalSettingsState = MutableLiveData<Int>(DEFAULT_VEHICLE_REPORTING_INTERVAL)
        var whileAlertingReportingInterval = DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL
        val whileAlertingReportingIntervalState = MutableLiveData<Int>(DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL)
        val whileAlertingReportingIntervalSettingsState = MutableLiveData<Int>(DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL)

        var enableSmsForAlerts = false
        var enableSmsForAlertsSettingsState = MutableLiveData<Boolean>(false)
        var smsNumbersList : List<DataRepository.SmsNumber> = listOf()
        var smsNumbersListString : String = ""
        val smsNumbersListStringSettingsState = MutableLiveData<String>("")
        var enablePhysiologicalAlerts = false
        var enablePhysiologicalAlertsSettingState = MutableLiveData<Boolean>(false)

        var hrHighAlertBpmThreshold : Int = 140
        val hrHighAlertBpmThresholdState = MutableLiveData<Int>(140)
        val hrHighAlertBpmThresholdSettingsState = MutableLiveData<Int>(140)
        var hrHighAlertNumSecsThreshold : Int = 30
        val hrHighAlertNumSecsThresholdState = MutableLiveData<Int>(30)
        val hrHighAlertNumSecsThresholdSettingsState = MutableLiveData<Int>(30)

        var versionNumber : String = ""
        var logFile: java.io.File? = null

//        private lateinit var healthTrackingService: com.samsung.android.service.health.tracking.HealthTrackingService

        fun generateSaveBattOnWifiString(enableWifiOpt: Boolean, enableAllWifiOpt: Boolean) : String {
            return when {
                enableWifiOpt && enableAllWifiOpt -> ALL_WIFI_NETWORKS  // "All"
                !enableWifiOpt -> NO_WIFI_NETWORKS // "No"
                else -> SOME_WIFI_NETWORKS // "Some"
            }
        }

        data class RolesJson (
            val lrEntries: List<String>,
            val lrValues: List<String>
        ) {
            public fun toJson() = klaxon.toJsonString(this)

            companion object {
                public fun fromJson(json: String) = klaxon.parse<RolesJson>(json)
            }
        }

        data class TeamsJson (
            val ltEntries: List<String>,
            val ltValues: List<String>
        ) {
            public fun toJson() = klaxon.toJsonString(this)

            companion object {
                public fun fromJson(json: String) = klaxon.parse<TeamsJson>(json)
            }
        }

        fun getActivity(): MainActivity {
            return mainActivity
        }

        fun startReactiveServiceManager(context: Context) {
            val intent = Intent(context, ReactiveServiceManager::class.java)
            if (!ReactiveServiceManager.isRunning.get()) {
                Log.i(TAG, "MainActivity startReactiveServiceManager(): Starting ReactiveServiceManager")
                ContextCompat.startForegroundService(context, intent)
            } else {
                Log.i(
                    TAG,
                    "MainActivity startReactiveServiceManager(): ReactiveManager is already Running!"
                )
            }
        }

        fun stopReactiveServiceManager(context: Context) {
            val intent = Intent(context, ReactiveServiceManager::class.java)
            if (ReactiveServiceManager.isRunning.get()) {
                Log.i(TAG, "MainActivity stopReactiveServiceManager(): Stopping ReactiveServiceManager")
                context.stopService(intent)
            } else {
                Log.i(TAG, "MainActivity stopReactiveServiceManager(): ReactiveServiceManager is not Running!")
            }
        }
    }

    val callsignState = MutableLiveData<String>("")
    val licenseState = MutableLiveData<String>("")
    val nameState = MutableLiveData<String>("")
    val addressState = MutableLiveData<String>("")
    val portState = MutableLiveData<String>("")
    val usernameState = MutableLiveData<String>("")
    val passwordState = MutableLiveData<String>("")

    private lateinit var restrictionsManager: RestrictionsManager
    private lateinit var restrictionReceiver: BroadcastReceiver

    fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService?>): Boolean {
        val am: AccessibilityManager = context.getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices: List<AccessibilityServiceInfo> = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

        for (enabledService in enabledServices) {
            val enabledServiceInfo: ServiceInfo = enabledService.resolveInfo.serviceInfo
            if (enabledServiceInfo.packageName.equals(context.packageName) && enabledServiceInfo.name.equals(service.name)) return true
        }

        return false
    }

    fun gotoAccessibilitySettings(context: Context): Boolean {
        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        if (context !is Activity) {
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        var isOk = true
        try {
            context.startActivity(settingsIntent)
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "gotoAccessibilitySettings: ActivityNotFoundException")
            isOk = false
        }
        return isOk
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsMap ->
            val deniedPermissions = permissionsMap.filterValues { !it }.keys
            permissionsMap.entries.forEach {
                Log.d(TAG, "${it.key} = ${it.value}")
            }
            if (deniedPermissions.isEmpty()) {
                Log.d(TAG, """
                    All permissions granted.
                    =====================================
                    """.trimIndent())
            } else {
                Log.w(TAG, "Permissions denied: $deniedPermissions")
            }
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "MainActivity onNewIntent(): categories = (${intent.categories}), action = (${intent.action})")
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "MainActivity onPause()")
    }

    override fun onRestart() {
        super.onRestart()
        Log.i(TAG, "MainActivity onRestart()")
    }

    override fun onStart() {
        super.onStart()
        try { registerReceiver(restrictionReceiver, IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED)) } catch (_: Exception) {}
        Log.i(TAG, "MainActivity onStart()")
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "MainActivity onStop()")
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "MainActivity onResume()")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy()")
        stopReactiveServiceManager(applicationContext)
        networkManager?.deInit()
        try { unregisterReceiver(restrictionReceiver) } catch (_: Exception) {}
    }

    fun printSensorDetails(context: Context?) {
        var resSensorList: MutableList<Sensor> = ArrayList()
        try {
            val smm = context?.getSystemService(SENSOR_SERVICE) as SensorManager
            resSensorList.addAll(smm.getSensorList(Sensor.TYPE_ALL))
            Log.d(TAG, "Sensor List: ${resSensorList.size}")
            resSensorList.forEach { sensor ->
                Log.d(TAG, "    ${sensor.name}")
            }
        } catch (e: Exception) {
            resSensorList = ArrayList()
            Log.e(TAG, "Sensor List ERROR: Unable to obtain", e)
        }
    }

    fun initTeamEntriesValuesList(teamNamesList: List<String>, teamColorsList: List<String>) {
        var teamNames = teamNamesList
        var teamColors = teamColorsList
        val teamNamesSize = teamNames.size
        val teamColorsSize = teamColors.size
        if (teamNamesSize != teamColorsSize) {
            // Mistake maybe made in MDM. Make size of teamNames the same as that of teamColors
            Log.w(TAG, "Initial teamNames size($teamNamesSize) != teamColors size($teamColorsSize)")
            if (teamNamesSize > teamColorsSize) {
                // Reduce teamNames to that of teamColors
                teamNames = teamNames.take(teamColorsSize)
            } else {
                teamColors = teamColors.take(teamNamesSize)
            }
        }
        teamEntriesValuesList = TeamsJson(teamNames, teamColors)
    }

    private suspend fun initTeamAndTeamValue() {
        // which team member am I?
        team = dataRepository.getTeam.first()
        var teamIndex = teamEntriesValuesList.ltEntries.indexOf(team)
        // needed in the event my current team doesn't exist in the list of available teams.
        if (teamIndex == -1) {
            team = teamEntriesValuesList.ltEntries[0]
            teamIndex = 0
        }
        teamValue = teamEntriesValuesList.ltValues[teamIndex]
    }

    private suspend fun initCategoryAndRole() {
        // what role and in which category am I?
        role = dataRepository.getRole.first()
        category = dataRepository.getRoleCategory.first()

        val catIdx = categoryRolesEntryLists.indexOfFirst { it.category == category}
        val roleIdx = categoryRolesEntryLists.getOrNull(catIdx)?.roles?.indexOf(role) ?: -1

        // needed in the event my current role doesn't exist in the list of available roles.
        if (catIdx == -1 || roleIdx == -1) {
            category = categoryRolesEntryLists.first().category
            role = categoryRolesEntryLists.first().roles.first()
            dataRepository.storeRoleCategory(category)
            dataRepository.storeRole(role)
        }
        Log.i(TAG, "Exited initCategoryAndRole() w/ category ($category) and role ($role)")
    }

    private suspend fun initCategoryAndRoleAndCategoryRolesList(dataRepoCategoryRolesList: List<CategoryRolesEntry> ) {
        try {
            Log.i(TAG, "Just before loading category entries from DataStore")
            categoryRolesEntryLists = dataRepoCategoryRolesList

            if (categoryRolesEntryLists.isEmpty()) {
                Log.i(TAG, "DataStore empty → loading defaults from raw JSON")
                val defaults = applicationContext.defaultRoleCategoryEntriesFromRaw()

                if (defaults.isNotEmpty()) {
                    categoryRolesEntryLists = defaults
                    Log.i(TAG, "Caching ${defaults.size} defaults back to DataStore")
                    try {
                        // **Use the correct method name** from DataRepository:
                        dataRepository.storeCategoryRolesList(defaults)
                    } catch (storeEx: Exception) {
                        Log.e(TAG, "Failed to store default categories", storeEx)
                    }
                } else {
                    Log.w(TAG, "No defaults found in raw resource; UI will be empty")
                }
            }

            Log.i(TAG, "About to init category and role")
            initCategoryAndRole()

        } catch (e: Exception) {
            Log.e(TAG, "Uncaught error in initCategoryAndRoleAndCatogoryRolesList(): ${e.message}", e)
            // At this point you can either rethrow or fall back to a minimal safe state:
            categoryRolesEntryLists = emptyList()
            role = ""             // or some safe default
            category = ""
        }
    }

    fun seedVariablesFromRepo() {
        // This function reads in data from dataRepository and sets MainActivity settings (except those for UI settings screens) as well as other service settings
        runBlocking {
            Log.d(TAG, """=== === Initializing Settings === === ===""".trimIndent())
            // what is my callsign?
            callsign = dataRepository.getCallsign.first()
            // on the first install, callsign has no value, we need to fill it with a random number
            if (callsign.equals("")) {
                callsign = "WEAROS-" + (secureRandom.nextInt(9999)+1).toString()
                dataRepository.storeCallsign(callsign)
                Log.v(TAG, "MainActivity seedVariablesFromRepo(): First install --> Assigning a random callsign: $callsign")
            }

            // what are the teams I can be?
            val teamNames = dataRepository.getTeamNames.first().split(",")
            val teamColors = dataRepository.getTeamColors.first().split(",")
            initTeamEntriesValuesList(teamNames, teamColors)
            initTeamAndTeamValue()

            initCategoryAndRoleAndCategoryRolesList(dataRepository.getCategoryRolesList.first())

            license = dataRepository.getLicense.first()

            takServerList = dataRepository.getTakServerList.first()

            enableLocationService = dataRepository.getEnableLocationService.first()
            enablePhysiologicalServices = dataRepository.getEnablePhysiologicalServices.first()
            enableHeartrateService = dataRepository.getEnableHeartRateService.first()
            enableActivityRecognitionService = dataRepository.getEnableActivityRecognitionService.first()
            enableSkinTempService = dataRepository.getEnableSkinTempService.first()
            enableDittoService = dataRepository.getEnableDittoService.first()
            enableChatService = dataRepository.getEnableChatService.first()
            // TODO: For some reason, DataRepository's enableHailService is true in onCreate of CotDispatchService even if enableHailService here is false
            enableHailService = dataRepository.getEnableHailService.first()
            enableBatdokCot = dataRepository.getEnableBatdokCot.first()
            enableMulticastOnWifi = dataRepository.getEnableMulticast.first()

            enableDynamicReportingStrategy = dataRepository.getEnableDynamicReportingStrategy.first()
            constantReportingInterval = dataRepository.getConstantReportingInterval.first()
            stationaryReportingInterval = dataRepository.getStationaryReportingInterval.first()
            onFootReportingInterval = dataRepository.getOnFootReportingInterval.first()
            vehicleReportingInterval = dataRepository.getVehicleReportingInterval.first()
            whileAlertingReportingInterval = dataRepository.getWhileAlertingReportingInterval.first()

            enableTakEndpoint = dataRepository.getEnableTakEndpoint.first()
            enableEsriEndpoint = dataRepository.getEnableEsriEndpoint.first()
            enableTabletCommandEndpoint = dataRepository.getEnableTabletCommandEndpoint.first()

            esriEndpoint = dataRepository.getEsriEndpoint.first()
            esriReportingUpdateInterval = dataRepository.getEsriReportingUpdateInterval.first()

            tabletCommandEndpoint = dataRepository.getTabletCommandEndpoint.first()
            tabletCommandApikey = dataRepository.getTabletCommandApikey.first()
            tabletCommandReportingUpdateInterval = dataRepository.getTabletCommandReportingUpdateInterval.first()

            // Initially on start ActivityRecognized is UNKNOWN
            val dynamicReportingInterval = calcDynamicReportingInterval(false, ActivityRecognized.UNKNOWN,
                whileAlertingReportingInterval, onFootReportingInterval, vehicleReportingInterval, stationaryReportingInterval)
            dataRepository.storeDynamicReportingInterval(dynamicReportingInterval)
            val reportingUpdateInterval = if (enableDynamicReportingStrategy) {dynamicReportingInterval} else {constantReportingInterval}
            Log.d(TAG, "enableDynamicReportingStrategy($enableDynamicReportingStrategy): reportingUpdateInterval=$reportingUpdateInterval w/ constantReportingInterval=$constantReportingInterval, dynamicReportingInterval=$dynamicReportingInterval (activity=${ActivityRecognized.UNKNOWN.name}, alert=$whileAlertingReportingInterval, onFoot=$onFootReportingInterval, vehicle=$vehicleReportingInterval, stationary=$stationaryReportingInterval)")
            dataRepository.storeReportingUpdateInterval(reportingUpdateInterval)
            CotDispatchService.reportingUpdateIntervalSecs = reportingUpdateInterval
            CotDispatchService.currReportingIntervalInSecs = reportingUpdateInterval

            enableWifiBatteryOptimization = dataRepository.getEnableWifiBatteryOptimization.first()
            PowerManagerService.isWifiOpt = enableWifiBatteryOptimization
            enableAllWifiBatteryOptimization = dataRepository.getEnableAllWifiBatteryOptimization.first()
            PowerManagerService.isAllWifiOpt = enableAllWifiBatteryOptimization
            wifiSsidList = dataRepository.getWifiSsidList.first()
            saveBattOnWifiString = generateSaveBattOnWifiString(enableWifiBatteryOptimization, enableAllWifiBatteryOptimization)

            enableSmsForAlerts = dataRepository.getEnableSmsForAlerts.first()
            smsNumbersList = dataRepository.getSmsNumbersList.first()
            smsNumbersListString = convertSmsNumbersListToStringWithSeparator(dataRepository.getSmsNumbersList.first(), ",")

            enablePhysiologicalAlerts = dataRepository.getEnablePhysiologicalAlerts.first()
            hrHighAlertBpmThreshold = dataRepository.getHrHighAlertBpmThreshold.first()
            hrHighAlertNumSecsThreshold = dataRepository.getHrHighAlertNumSecsThreshold.first()

            enableBleBeaconService = dataRepository.getEnableBleBeaconService.first()
            localizationBleBeaconItemList = dataRepository.getLocalizationBleBeaconItemList.first()

            dittoAppId = dataRepository.getDittoAppId.first()
            dittoAuthToken = dataRepository.getDittoAuthToken.first()

            battWarnPercent = dataRepository.getBatteryWarnPercentage.first()
            battAlertPercent = dataRepository.getBatteryAlertPercentage.first()

            ageValue = dataRepository.getPhysiologicalMetricAge.first()
            heightFeetValue = dataRepository.getPhysiologicalMetricHeightFeet.first()
            heightInchesValue = dataRepository.getPhysiologicalMetricHeightInches.first()
            weightValue = dataRepository.getPhysiologicalMetricWeight.first()
            sexValue = dataRepository.getPhysiologicalMetricSex.first()
            bloodTypeValue = dataRepository.getPhysiologicalMetricBloodType.first()
            allergyList = dataRepository.getPhysiologicalMetricAllergyList.first()
            userTypeValue = dataRepository.getPhysiologicalMetricUserType.first()
            restingHeartRate = dataRepository.getRestingHeartRate.first()

//            Log.i(TAG, "MainActivity seedVariablesFromRepo(): End")
        }
    }

    private fun logSettings() {
        seedVariablesFromRepo()
        var logOutput = """
                    === === Configuration Settings === ===
                    ${String.format("%-25s %-30s", "[Settings]", "[Value]")}
                    === === === === === === === === === ==
                    ${String.format("%-25s %-30s", "Device ID:", device_uid)}
                    ${String.format("%-25s %-30s", "Callsign:", callsign)}
                    ${String.format("%-25s %-30s", "Team:", team)}
                    ${String.format("%-25s %-30s", "Role Category:", category)}
                    ${String.format("%-25s %-30s", "Role:", role)}
                    ${String.format("%-25s %-30s", "Teams:", teamEntriesValuesList.ltEntries)}
                    ${String.format("%-25s %-30s", "Colors:", teamEntriesValuesList.ltValues)}
                    ${String.format("%-25s %-30s", "Category Roles List:", categoryRolesEntryLists.toString())}

                    ${String.format("%-25s %-30s", "Enable Tak Endpoint:", enableTakEndpoint)}
                    ${String.format("%-25s %-30s", "Enable Esri Endpoint:", enableEsriEndpoint)}
                    ${String.format("%-25s %-30s", "Enable Tablet Command Endpoint:", enableTabletCommandEndpoint)}
                    
                    === === TAK Server Settings === ===
                    ${String.format("%-25s %-30s", "Number of Servers:", takServerList.size)}
                    
                    
                    """.trimIndent()

        for (i in takServerList.indices) {
            val takServer = takServerList.get(i)
            logOutput += """
                    ${String.format("%-25s %-30s", "Server $i Name:", takServer.name)}
                    ${String.format("%-25s %-30s", "Server $i Addr:", takServer.address)}
                    ${String.format("%-25s %-30s", "Server $i Port:", takServer.port)}
                    ${String.format("%-25s %-30s", "Server $i Uses Cert:", takServer.isP12Cert)}
                    ${String.format("%-25s %-30s", "Server $i User:", takServer.username)}
                    ${String.format("%-25s %-30s", "Server $i Pswd:", takServer.password)}
                    ${String.format("%-25s %-30s", "Server $i Cert:", takServer.p12Cert)}
                    ${String.format("%-25s %-30s", "Server $i Cert Pswd:", takServer.p12CertPassword)}
                       
                    
                    """.trimIndent()
        }

        // TODO: Maybe obfuscate Tablet Command Apikey
        logOutput += """
                    === === ESRI Server Settings === ===
                    ${String.format("%-25s %-30s", "ESRI Endpoint:", esriEndpoint)}
                    ${String.format("%-25s %-30s", "ESRI Reporting Interval:", esriReportingUpdateInterval)}
                    
                    === === Tablet Command Server Settings === ===
                    ${String.format("%-25s %-30s", "Tablet Command Endpoint:", tabletCommandEndpoint)}
                    ${String.format("%-25s %-30s", "Tablet Command Apikey:", tabletCommandApikey)}
                    ${String.format("%-25s %-30s", "Tablet Command Reporting Interval:", tabletCommandReportingUpdateInterval)}
                    
                    === === Service Settings === ===
                    ${String.format("%-25s %-30s", "License Key:", license)}
                    ${String.format("%-25s %-30s", "Ditto App Id:", dittoAppId)}
                    ${String.format("%-25s %-30s", "Ditto Auth Token:", dittoAuthToken)}
                    ${String.format("%-25s %-30s", "Reporting Strategy:", if (enableDynamicReportingStrategy) "Dynamic" else "Constant")}
                    ${String.format("%-25s %-30s", "Constant Reporting Interval (s):", constantReportingInterval.toString())}
                    ${String.format("%-25s %-30s", "Stationary Reporting Interval (s):", stationaryReportingInterval.toString())}
                    ${String.format("%-25s %-30s", "Vehicle Reporting Interval (s):", vehicleReportingInterval.toString())}
                    ${String.format("%-25s %-30s", "On Foot Reporting Interval (s):", onFootReportingInterval.toString())}
                    ${String.format("%-25s %-30s", "While Alerting Reporting Interval (s):", whileAlertingReportingInterval.toString())}
                    ${String.format("%-25s %-30s", "Enable Loc:", enableLocationService.toString())}
                    ${String.format("%-25s %-30s", "Enable Physio:", enablePhysiologicalServices.toString())}
                    ${String.format("%-25s %-30s", "Enable Activity Recognition:", enableActivityRecognitionService.toString())}
                    ${String.format("%-25s %-30s", "Enable Skin Temp:", enableSkinTempService.toString())}
                    ${String.format("%-25s %-30s", "Enable Ditto:", enableDittoService.toString())}
                    ${String.format("%-25s %-30s", "Enable wifi opt:", enableWifiBatteryOptimization.toString())}
                    ${String.format("%-25s %-30s", "Enable all wifi:", enableAllWifiBatteryOptimization.toString())}
                    ${String.format("%-25s %-30s", "Wifi ssids list:", wifiSsidList.toString())}
                    ${String.format("%-25s %-30s", "Enable SMS For Alerts:", enableSmsForAlerts.toString())}
                    ${String.format("%-25s %-30s", "SMS Numbers:", smsNumbersList.toString())}
                    ${String.format("%-25s %-30s", "Enable Chat:", enableChatService.toString())}
                    ${String.format("%-25s %-30s", "Enable Hail:", enableHailService.toString())}
                    ${String.format("%-25s %-30s", "Enable Batdok COT:", enableBatdokCot.toString())}
                    ${String.format("%-25s %-30s", "Enable Multicast on WiFi:", enableMulticastOnWifi.toString())}
                    ${String.format("%-25s %-30s", "Enable Physiological Alerts:", enablePhysiologicalAlerts.toString())}
                    ${String.format("%-25s %-30s", "${getString(R.string.hr_high_alert_bpm_threshold_title)}:", hrHighAlertBpmThreshold.toString())}
                    ${String.format("%-25s %-30s", "${getString(R.string.hr_high_alert_number_secs_threshold_title)}:", hrHighAlertNumSecsThreshold.toString())}
                    ${String.format("%-25s %-30s", "Battery Warn Percentage:", battWarnPercent.toString())}
                    ${String.format("%-25s %-30s", "Battery Alert Percentage:", battAlertPercent.toString())}
                    ${String.format("%-25s %-30s", "Enable BleBeacon:", enableBleBeaconService.toString())}
                    
                    === === Ble Beacons === ===
                    ${String.format("%-25s %-30s", "Number of Localization Ble Beacons:", localizationBleBeaconItemList.size)}
                    
                    
                    """.trimIndent()

        for (i in localizationBleBeaconItemList.indices) {
            val bleBeacon = localizationBleBeaconItemList.get(i)
            logOutput += """
                    ${String.format("%-25s %-30s", "Beacon $i Id:", "${bleBeacon.uuid}, ${bleBeacon.major}, ${bleBeacon.minor}")}
                    ${String.format("%-25s %-30s", "Beacon $i Location:", "${bleBeacon.lat}, ${bleBeacon.lon}, ${bleBeacon.alt}")}
                       
                    
                    """.trimIndent()
        }
        Log.v(TAG, logOutput)

        logOutput += """
                    === === Physiological User Metrics === ===
                    ${String.format("%-25s %-30s", "Age:", ageValue)}
                    ${String.format("%-25s %-30s", "Height:", "$heightFeetValue ft $heightInchesValue inches")}
                    ${String.format("%-25s %-30s", "Weight:", weightValue)}
                    ${String.format("%-25s %-30s", "Sex:", sexValue)}
                    ${String.format("%-25s %-30s", "BloodType:", bloodTypeValue)}
                    ${String.format("%-25s %-30s", "Allergies:", allergyList)}
                    ${String.format("%-25s %-30s", "UserType:", userTypeValue)}
                    ${String.format("%-25s %-30s", "RestingHeartRate:", restingHeartRate)}
                    
                    """.trimIndent()
        Log.v(TAG, logOutput)
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.SEND_SMS)

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        Log.d(TAG, "=== === ===  Permissions  === === ===)")
        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG,
                """Missing: $missingPermissions
                =====================================
                """.trimIndent()
            )
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
            //refreshPermissions()
        } else {
            Log.d(TAG, """
                All Permissions already granted.
                =====================================
                """.trimIndent()
            )
        }
        /*
        if (!Settings.canDrawOverlays(this)) {
            //If the draw over permission is not available open the settings screen
            //to grant the permission.

            //val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            //startActivityForResult(intent, 2084)
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        }

         */
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity onCreate(): Start...")
        mainActivity = this
        mainActivityIsInit = this
        device_uid = "WEAROS_" + Secure.getString(this.getContentResolver(), Secure.ANDROID_ID)

        val context = mainActivity.applicationContext
        dataRepository = (context.applicationContext as WearTakApplication).repo

        seedVariablesFromRepo()
        refreshUI(context)

        restrictionsManager = getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        restrictionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) {
                    Log.i(TAG, "MainActivity onCreate() restrictionReceiver: Received new Managed Configurations from Knox")
                    handleManagedConfigUpdate(false)
                }
            }
        }
        registerReceiver(restrictionReceiver, IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED))
        // Handle the initial configuration when the app starts
        handleManagedConfigUpdate(true)

        networkManager = NetworkManager.getInstance()
        networkManager?.init()
        takConnectService = TAKConnectService.getInstance()

        //Obtain version # from build.gradle
        try {
            val pInfo: PackageInfo =
                this.getPackageManager().getPackageInfo(this.getPackageName(), 0)
            versionNumber = pInfo.versionName.toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "Version number not found")
        }

        installSplashScreen()

        setTheme(android.R.style.Theme_DeviceDefault)

        //printSensorDetails(this)

        // Request all necessary permissions
        checkAndRequestPermissions()
        //startHealthCapabilitiesCollection()
        validateLicense(license)
        startReactiveServiceManager(applicationContext)

        // Kiosk needs to be allow com.samsung.accessibility for code below to work
        val isAccessibilityServiceEnabled = isAccessibilityServiceEnabled(mainActivity, CustomAccessibilityService::class.java)
//        Log.d(TAG, "MainActivity onCreate(): isAccessibilityServiceEnabled $isAccessibilityServiceEnabled")
        if (!isAccessibilityServiceEnabled) {
            Log.w(TAG, "MainActivity onCreate(): isAccessibilityServiceEnabled=false. trying to open Accessibility Settings")
            /*
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mainActivity.startActivity(intent)
             */

            gotoAccessibilitySettings(mainActivity)
            for (i in 0..3) {
                Toast.makeText(mainActivity,
                    getString(R.string.weartak_accessibility_settings_toast), Toast.LENGTH_LONG).show()
            }
        }


        setContent {
            WearTAKCIVTheme {
                WearTAKCivApp(callsignSettingsState, licenseState)
            }
        }

        /*
        if (Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.aitech.weartak_civ/.service.CustomAccessibilityService")) {
            Log.d(TAG, "Able to set ENABLED_ACCESSIBILITY_SERVICES to com.aitech.weartak_civ/.service.CustomAccessibilityService")
        } else {
            Log.d(TAG, "NOT Able to set ENABLED_ACCESSIBILITY_SERVICES to com.aitech.weartak_civ/.service.CustomAccessibilityService")

        }
        if (Settings.Secure.putString(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1")) {
            Log.d(TAG, "Able to set ACCESSIBILITY_ENABLED to 1")
        } else {
            Log.d(TAG, "NOT Able to set ACCESSIBILITY_ENABLED to 1")
        }
         */
        Log.i(TAG, "MainActivity onCreate(): End.")
    }

    private fun refreshUI(context: Context) {
        Log.d(TAG, "=== === Refreshing UI  === === ")
        callsignState.postValue(callsign)
        callsignSettingsState.postValue(callsign)
        teamState.postValue(team) // liveData used by UI
        teamSettingsState.postValue(team)
        roleState.postValue(role) // liveData used by UI
        roleSettingsState.postValue(role)
        licenseState.postValue(license) // tell ui
        licenseSettingsState.postValue(license) // ephemeral ui value
        takServerListState.postValue(takServerList)
        enableWifiBatteryOptimizationState.postValue(enableWifiBatteryOptimization) // tell ui
        enableWifiBatteryOptimizationSettingsState.postValue(enableWifiBatteryOptimization) // ephemeral ui value
        enableAllWifiBatteryOptimizationState.postValue(enableAllWifiBatteryOptimization) // tell ui
        enableAllWifiBatteryOptimizationSettingsState.postValue(enableAllWifiBatteryOptimization) // ephemeral ui value
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

        wifiSsidListState.postValue(wifiSsidList) // tell ui
        wifiSsidListSettingsState.postValue(wifiSsidList) // ephemeral ui value
        saveBattOnWifiStringState.postValue(saveBattOnWifiString)
        enableLocationServiceSettingsState.postValue(enableLocationService)
        enableHailServiceSettingsState.postValue(enableHailService)
        enableDittoServiceSettingsState.postValue(enableDittoService)
        enablePhysiologicalServicesSettingsState.postValue(enablePhysiologicalServices)
        enableChatServiceSettingsState.postValue(enableChatService)
        enableSmsForAlertsSettingsState.postValue(enableSmsForAlerts)
        smsNumbersListStringSettingsState.postValue(convertSmsNumbersListToStringWithSeparator(smsNumbersList, ","))
        enablePhysiologicalAlertsSettingState.postValue(enablePhysiologicalAlerts)
        hrHighAlertBpmThresholdState.postValue(hrHighAlertBpmThreshold)
        hrHighAlertBpmThresholdSettingsState.postValue(hrHighAlertBpmThreshold)
        hrHighAlertNumSecsThresholdState.postValue(hrHighAlertNumSecsThreshold)
        hrHighAlertNumSecsThresholdSettingsState.postValue(hrHighAlertNumSecsThreshold)

        ageState.postValue(ageValue)
        ageSettingsState.postValue(ageValue)
        heightFeetState.postValue(heightFeetValue)
        heightFeetSettingsState.postValue(heightFeetValue)
        heightInchesState.postValue(heightInchesValue)
        heightInchesSettingsState.postValue(heightInchesValue)
        weightState.postValue(weightValue)
        weightSettingsState.postValue(weightValue)
        sexState.postValue(sexValue)
        sexSettingsState.postValue(sexValue)
        bloodTypeState.postValue(bloodTypeValue)
        bloodTypeSettingsState.postValue(bloodTypeValue)
        allergyListState.postValue(allergyList)
        allergyListSettingsState.postValue(allergyList)
        userTypeState.postValue(userTypeValue)
        userTypeSettingsState.postValue(userTypeValue)
        restingHeartRateState.postValue(restingHeartRate)
        restingHeartRateSettingsState.postValue(restingHeartRate)

        requestCallsignComplicationUpdate(context)
        requestHeartRateComplicationUpdate(context)
        requestSosComplicationUpdate(context)
        requestStatusIconsComplicationUpdate(context)
        requestChatComplicationUpdate(context)
        requestPhysioComplicationUpdate(context)
    }

    private fun takServerConnDetailsChanged(takServer1: DataRepository.TakServerItem, takServer2: DataRepository.TakServerItem) : Boolean {
        var changed = false
        if (takServer1.isEnabled != takServer2.isEnabled) {
            changed = true
        } else {
            if (takServer1.isP12Cert != takServer2.isP12Cert) {
                changed = true
            } else {
                if ((takServer1.address != takServer2.address) ||
                    (takServer1.port != takServer2.port)) {
                    changed = true
                } else {
                    if (takServer1.isP12Cert) {
                        if ((takServer1.p12Cert != takServer2.p12Cert) ||
                            (takServer1.p12CertPassword != takServer2.p12CertPassword)) {
                            changed = true
                        }
                    } else {
                        if ((takServer1.username != takServer2.username) ||
                            (takServer1.password != takServer2.password)) {
                            changed = true
                        }
                    }
                }
            }
        }
        return changed
    }

    fun vibrateWatch(context: Context, durationMs: Long, amplitude: Int) {
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.let {
            if (it.hasVibrator()) {
                /*
                val vibrationEffect = VibrationEffect.createOneShot(
                    100, // Duration in milliseconds
                    VibrationEffect.DEFAULT_AMPLITUDE
                )

                 */
                val vibrationEffect = VibrationEffect.createOneShot(
                    durationMs, // Duration in milliseconds
                    amplitude
                )
                it.vibrate(vibrationEffect)
            }
        }
    }

    fun convertWifiSsidListToStringWithSeparator(wifiSsidList: List<DataRepository.WifiSsidItem>, separatorStr: String): String {
        var result = ""
        if (!wifiSsidList.isEmpty()) {
            for (wifiSsid in wifiSsidList) {
                if ("".equals(result)) {
                    result = wifiSsid.name
                } else {
                    result = "$result$separatorStr${wifiSsid.name}"
                }
            }
        }
        return result
    }

    fun convertSmsNumbersListToStringWithSeparator(smsNumbersList: List<DataRepository.SmsNumber>, separatorStr: String): String {
        var result = ""
        if (!smsNumbersList.isEmpty()) {
            for (smsNumber in smsNumbersList) {
                if ("".equals(result)) {
                    result = smsNumber.telephoneNumber
                } else {
                    result = "$result$separatorStr${smsNumber.telephoneNumber}"
                }
            }
        }
        return result
    }

    private fun handleManagedConfigUpdate(calledOnCreate: Boolean) {
        Log.v(TAG, "=== === Handling Managed Config Update (calledOnCreate: $calledOnCreate) ===")
        seedVariablesFromRepo()

        val context = mainActivity.applicationContext
        val appRestrictions = restrictionsManager.applicationRestrictions
        if (appRestrictions == null) {
            Log.w(TAG, "No managed configuration received. Returning...")
            return
        }

        Log.d(TAG, "Managed Configuration Item Size: ${appRestrictions.keySet().size}")

        /*
        for (key in appRestrictions.keySet()) {
            val value = appRestrictions.get(key)
            // Retrieve the runtime type name, if available
            val typeName = value?.javaClass?.simpleName ?: "null"
            Log.v(TAG, "Key: $key, Value: $value, Type: $typeName")
        }
         */

        val manifestRestrictions = restrictionsManager.getManifestRestrictions("com.aitech.weartak_civ") ?: listOf()
        if (manifestRestrictions.size == 0) {
            Log.w(TAG, "Size of manifestRestrictions is 0. Returning...")
            return
        }

        /*
        for (entry in manifestRestrictions) {
            Log.v(TAG, "Manifest Key: ${entry.key}, Declared Type: ${entry.type}")
            // If the restriction is a bundle or bundle array, check nested restrictions.
            if (entry.type == RestrictionEntry.TYPE_BUNDLE ||
                entry.type == RestrictionEntry.TYPE_BUNDLE_ARRAY) {
                entry.restrictions?.forEach { nested ->
                    Log.v(TAG, "\tNested Key: ${nested.key}, Type: ${nested.type}")
                }
            }
        }
         */

        // Store previous license before getting new license and validating it
        val prevLicenseIsValid = isLicenseValid()
        // Get the new settings from the restrictions manager or null, so that we only change dataRepository if there are changes
        license = appRestrictions.getString("license_key", license)
        val teamsNames = appRestrictions.getString("custom_teams_names", null)
        val teamsColors = appRestrictions.getString("custom_teams_colors", null)
        val categoryRolesJsonStr = appRestrictions.getString("custom_roles", null)

        val dataEndpoints = appRestrictions.getStringArray("select_data_endpoints")
        var enableTakEndpointConfigUpdate = false
        var enableEsriEndpointConfigUpdate = false
        var enableTabletCommandEndpointConfigUpdate = false
        Log.d(TAG, "Send to data endpoints (${dataEndpoints?.size}): ${dataEndpoints?.joinToString(", ")}")
        if (null != dataEndpoints) {
            for (endpoint in dataEndpoints) {
                if ("ESRI" == endpoint) {
                    enableEsriEndpointConfigUpdate = true
                } else if ("Tablet Command" == endpoint) {
                    enableTabletCommandEndpointConfigUpdate = true
                } else if ("TAK" == endpoint) {
                    enableTakEndpointConfigUpdate = true
                }
            }
        }
        // TODO: Do we need this below
        /*
        if (!enableEsriEndpointConfigUpdate && !enableTakEndpointConfigUpdate) {
            enableTakEndpointConfigUpdate = true
        }

         */

        var serverEnableTAKConnectService: Boolean = true
        var serverName: String? = null
        var serverAddress: String? = null
        var serverPort: String? = null
        var serverUseCert: Boolean = false
        var serverUsername: String? = null
        var serverPassword: String? = null
        var serverCertPassword: String? = null
        var serverCertificate: String? = null
        val takServerListConfig: MutableList<DataRepository.TakServerItem> = mutableListOf()
        val handler = Handler(Looper.getMainLooper())

        val serverParcels = appRestrictions.getParcelableArray("tak_server_list")  // we don't know how many configurations will be set on the Knox Managed Configuration page, so we to loop through all the servers in the list.
        if (serverParcels != null) {
            val serverCount = serverParcels.size
            Log.v(TAG, "=== Number of Tak Server Connection Configurations: $serverCount ===")
            // Loop through each server connection
            for (i in serverParcels.indices) {
                // Each element should be a Bundle representing one server connection
                val serverBundle = serverParcels[i] as? Bundle
                if (serverBundle != null) {
                    Log.v(TAG, "=== Server Connection Conf [$i] ===")
                    // Iterate through all keys in this server connection Bundle
                    for (key in serverBundle.keySet()) {
                        val value = serverBundle.get(key)
                        // Format each key/value pair into a fixed-width layout for clarity.
                        Log.v(TAG, String.format("   %-30s : %s", key, value))
                    }

                    // set companion obj by looking for key in bundle, fallback on current
                    serverName = serverBundle.getString("tak_server_connection_name", null) // set companion obj by looking for key, fallback on current
                    serverEnableTAKConnectService = serverBundle.getBoolean("tak_server_connection_enabled", true)
                    serverAddress = serverBundle.getString("tak_server_address", null)
                    serverUseCert = serverBundle.getBoolean("tak_server_use_certificate", false)
                    serverPort = (serverBundle.getInt("tak_server_port", 8089)).toString()
                    serverUsername = serverBundle.getString("tak_server_username", null)
                    serverPassword = serverBundle.getString("tak_server_password", null)
                    serverCertPassword = serverBundle.getString("tak_server_cert_password", null)
                    serverCertificate = serverBundle.getString("tak_server_cert", null)
                    takServerListConfig.add(DataRepository.TakServerItem(serverEnableTAKConnectService, serverName, serverAddress, serverPort.toInt(), serverUseCert, serverUsername, serverPassword, serverCertificate, serverCertPassword))
                    Log.v(TAG, "========================================")
                } else {
                    Log.e(TAG, "Invalid server bundle at index $i")
                }
            }
        } else {
            Log.d(TAG, "tak_server_list is null or empty.")
        }

        val esriEndpointConfigUpdate = appRestrictions.getString("esri_endpoint", null)
        val esriReportingIntervalConfigUpdate = appRestrictions.getInt("esri_reporting_interval")
        Log.d(TAG, "esriEndpoint($esriEndpointConfigUpdate), esriReportingInterval($esriReportingIntervalConfigUpdate)")

        val tabletCommandEndpointConfigUpdate = appRestrictions.getString("tabletcommand_endpoint", null)
        val tabletCommandApikeyConfigUpdate = appRestrictions.getString("tabletcommand_apikey", null)
        val tabletCommandReportingIntervalConfigUpdate = appRestrictions.getInt("tabletcommand_reporting_interval")
        Log.d(TAG, "tabletCommandEndpoint($tabletCommandEndpointConfigUpdate), tabletCommandApikey($tabletCommandApikeyConfigUpdate), tabletCommandReportingInterval($tabletCommandReportingIntervalConfigUpdate)")

        val enableWifiBatteryOptimizationConfigUpdate = appRestrictions.getBoolean("wifiOptimization_service_enabled", enableWifiBatteryOptimization)
        val enableAllWifiBatteryOptimizationConfigUpdate = appRestrictions.getBoolean("wifiOptimization_all_enabled", enableAllWifiBatteryOptimization)
        val wifiOptimizationSsidString = appRestrictions.getString("wifiOptimization_ssid_list", convertWifiSsidListToStringWithSeparator(wifiSsidList, "||"))
        val wifiOptimizationSsidList: List<DataRepository.WifiSsidItem> = wifiOptimizationSsidString.split("||") // Split by two pipes, unlikely a random generated SSID has
            .map { it.trim() } // Trim whitespace from each SSID
            .filter { it.isNotEmpty() } // Remove any empty values
            .map { DataRepository.WifiSsidItem(it) } // Map each SSID to a WifiSsidItem

        /*
         *  Maps are used for debugging the status of all the Services.
         *   - knoxMap gives us the Knox Manage config value for each restriction.
         *        Null means it is unconfigured.
         *   - localMap gives the local companion object that we fall back to.
         *        This will be deprecated, but useful for the time being.
         *   - CfgUpdateMap gives use Boolean that is stored persistently in the
         *        data repository. If Knox is null, fall back on the companion object,
         *        which we recently obtained from persistent store in seedVariablesFromRepo()
         *
         *    Note that unconfigurable services will always be null (e.g. TakConnectService, CotDispatch).
         */

        val knoxMap      = EnumMap<ServiceType, Boolean?>(ServiceType::class.java)
        val localMap     = EnumMap<ServiceType, Boolean?>(ServiceType::class.java)
        val cfgUpdateMap = EnumMap<ServiceType, Boolean?>(ServiceType::class.java)

        knoxMap[ServiceType.LOCATION] = appRestrictions.getNullableBoolean("location_service_enabled")
        knoxMap[ServiceType.PHYSIO] =  appRestrictions.getNullableBoolean("physiological_services_enabled")
        knoxMap[ServiceType.HEART_RATE] = appRestrictions.getNullableBoolean("heartrate_service_enabled")
        knoxMap[ServiceType.ACTIVITY_RECOGNITION] = appRestrictions.getNullableBoolean("activity_recognition_service_enabled")
        knoxMap[ServiceType.SKIN_TEMP] = appRestrictions.getNullableBoolean("skin_temp_service_enabled")
        knoxMap[ServiceType.DITTO] = appRestrictions.getNullableBoolean("ditto_service_enabled")
        knoxMap[ServiceType.CHAT] = appRestrictions.getNullableBoolean("chat_service_enabled")
        knoxMap[ServiceType.BLE_BEACON] = appRestrictions.getNullableBoolean("ble_beacon_service_enabled")
        knoxMap[ServiceType.HAIL]       = appRestrictions.getNullableBoolean("hail_service_enabled")
        knoxMap[ServiceType.TAK_CONNECT]           = null
        knoxMap[ServiceType.COT_DISPATCH]          = null
        knoxMap[ServiceType.MOVEMENT_DETECTION]    = null
        knoxMap[ServiceType.BATTERY]               = null
        knoxMap[ServiceType.POWER_MGR]             = null

        runBlocking {
            dataRepository.updateDebugStageRemoteKnox(knoxMap)
        }

        localMap[ServiceType.LOCATION]   = enableLocationService
        localMap[ServiceType.PHYSIO]     = enablePhysiologicalServices
        localMap[ServiceType.HEART_RATE] = enableHeartrateService
        localMap[ServiceType.ACTIVITY_RECOGNITION] = enableActivityRecognitionService
        localMap[ServiceType.SKIN_TEMP]  = enableSkinTempService
        localMap[ServiceType.DITTO]      = enableDittoService
        localMap[ServiceType.CHAT]       = enableChatService
        localMap[ServiceType.BLE_BEACON] = enableBleBeaconService
        localMap[ServiceType.HAIL]        = enableHailService
        localMap[ServiceType.TAK_CONNECT]           = null
        localMap[ServiceType.COT_DISPATCH]          = null
        localMap[ServiceType.MOVEMENT_DETECTION]    = null
        localMap[ServiceType.BATTERY]               = null
        localMap[ServiceType.POWER_MGR]             = null

        runBlocking {
            dataRepository.updateDebugStageLocalPersisted(localMap)
        }

        val enableLocationServiceConfigUpdate =
            appRestrictions.getBoolean("location_service_enabled", enableLocationService)
        val enablePhysiologicalServicesConfigUpdate =
            appRestrictions.getBoolean("physiological_service_enabled", enablePhysiologicalServices)
        val enableHeartrateServiceConfigUpdate =
            appRestrictions.getBoolean("heartrate_service_enabled", enableHeartrateService)
        val enableActivityRecognitionServiceConfigUpdate =
            appRestrictions.getBoolean("activity_recognition_service_enabled", enableActivityRecognitionService)
        val enableSkinTempServiceConfigUpdate =
            appRestrictions.getBoolean("skin_temp_service_enabled", enableSkinTempService)
        val enableDittoServiceConfigUpdate =
            appRestrictions.getBoolean("ditto_service_enabled", enableDittoService)
        val enableChatServiceConfigUpdate =
            appRestrictions.getBoolean("chat_service_enabled", enableChatService)
        val enableBleBeaconServiceConfigUpdate =
            appRestrictions.getBoolean("ble_beacon_service_enabled", enableBleBeaconService)
        val enableHailServiceConfigUpdate = appRestrictions.getBoolean("hail_service_enabled", enableHailService)
        val enableBatdokCotConfigUpdate = appRestrictions.getBoolean("batdok_enabled", enableBatdokCot)
        val enableMulticastConfigUpdate = appRestrictions.getBoolean("multicast_service_enabled", enableMulticastOnWifi)

        cfgUpdateMap[ServiceType.LOCATION] = enableLocationServiceConfigUpdate
        cfgUpdateMap[ServiceType.PHYSIO]   = enablePhysiologicalServicesConfigUpdate
        cfgUpdateMap[ServiceType.HEART_RATE] = enableHeartrateServiceConfigUpdate
        cfgUpdateMap[ServiceType.ACTIVITY_RECOGNITION] = enableActivityRecognitionServiceConfigUpdate
        cfgUpdateMap[ServiceType.SKIN_TEMP] = enableSkinTempServiceConfigUpdate
        cfgUpdateMap[ServiceType.DITTO] = enableDittoServiceConfigUpdate
        cfgUpdateMap[ServiceType.CHAT] = enableChatServiceConfigUpdate
        cfgUpdateMap[ServiceType.BLE_BEACON] = enableBleBeaconServiceConfigUpdate
        cfgUpdateMap[ServiceType.HAIL]       = enableHailServiceConfigUpdate
        cfgUpdateMap[ServiceType.TAK_CONNECT]           = null
        cfgUpdateMap[ServiceType.COT_DISPATCH]          = null
        cfgUpdateMap[ServiceType.MOVEMENT_DETECTION]    = null
        cfgUpdateMap[ServiceType.BATTERY]               = null
        cfgUpdateMap[ServiceType.POWER_MGR]             = null

        runBlocking {
            dataRepository.updateDebugStageConfigUpdate(cfgUpdateMap)
        }

        val configJson = appRestrictions.getString("custom_developer_config_json", "")

        val dittoAppIdConfigUpdate = appRestrictions.getString("ditto_app_id", "")
        val dittoAuthTokenConfigUpdate = appRestrictions.getString("ditto_auth_token", "")

        val enableDynamicReportingStrategyConfigUpdate = appRestrictions.getBoolean("enable_dynamic_reporting_strategy", enableDynamicReportingStrategy)
        val constantReportingIntervalConfigUpdate = appRestrictions.getInt("constant_reporting_interval", constantReportingInterval)
        val whileAlertingReportingIntervalConfigUpdate = appRestrictions.getInt("alerting_reporting_interval", whileAlertingReportingInterval)
        val onFootReportingIntervalConfigUpdate = appRestrictions.getInt("on_foot_walking_reporting_interval", onFootReportingInterval)
        val vehicleReportingIntervalConfigUpdate = appRestrictions.getInt("in_vehicle_reporting_interval", vehicleReportingInterval)
        val stationaryReportingIntervalConfigUpdate = appRestrictions.getInt("stationary_reporting_interval", stationaryReportingInterval)
        Log.d(TAG, "ConfigUpdate: $enableDynamicReportingStrategyConfigUpdate, constRI=$constantReportingIntervalConfigUpdate, $whileAlertingReportingIntervalConfigUpdate, $onFootReportingIntervalConfigUpdate, $vehicleReportingIntervalConfigUpdate, $stationaryReportingIntervalConfigUpdate")

        val enableSmsForAlertsConfigUpdate = appRestrictions.getBoolean("enable_sms", enableSmsForAlerts)
        val smsNumbersString = appRestrictions.getString("sms_numbers_comma_separated", convertSmsNumbersListToStringWithSeparator(smsNumbersList, ","))
        val smsNumbersListConfigUpdate: List<DataRepository.SmsNumber> = smsNumbersString.split(",")
            .map { it.trim() } // Trim whitespace from each SSID
            .filter { it.isNotEmpty() } // Remove any empty values
            .map { DataRepository.SmsNumber(it) } // Map each SSID to a WifiSsidItem

        val battWarnPercentConfigUpdate = appRestrictions.getInt("batt_warn_percent", battWarnPercent)
        val battAlertPercentConfigUpdate = appRestrictions.getInt("batt_alert_percent", battAlertPercent)
        val enablePhysiologicalAlertsConfigUpdate = appRestrictions.getBoolean("enable_physiological_alerts", enablePhysiologicalAlerts)

        val physiologicalMetricsRestingHeartRateConfigUpdate = appRestrictions.getInt("physiological_metrics_resting_heart_rate", restingHeartRate)
        val physiologicalMetricsAgeServiceConfigUpdate = appRestrictions.getInt("physiological_metrics_age", ageValue)
        val physiologicalMetricsHeightFeetServiceConfigUpdate = appRestrictions.getInt("physiological_metrics_height_feet", heightFeetValue)
        val physiologicalMetricsHeightInchesServiceConfigUpdate = appRestrictions.getInt("physiological_metrics_height_inches", heightInchesValue)
        val physiologicalMetricsWeightServiceConfigUpdate = appRestrictions.getInt("physiological_metrics_weight", weightValue)
        val physiologicalMetricsSexServiceConfigUpdate = appRestrictions.getString("physiological_metrics_sex", sexValue)
        val physiologicalMetricsBloodTypeServiceConfigUpdate = appRestrictions.getString("physiological_metrics_blood_type", bloodTypeValue)
        val physiologicalMetricsAllergiesServiceConfigUpdate = appRestrictions.getStringArray("physiological_metrics_allergies")
        val physiologicalMetricsUserTypeServiceConfigUpdate = appRestrictions.getString("physiological_metrics_user_type", userTypeValue)


        val hrHighAlertBpmThresholdConfigUpdate = appRestrictions.getInt("hr_high_alert_bpm_threshold", hrHighAlertBpmThreshold)
        val hrHighAlertNumSecsThresholdConfigUpdate = appRestrictions.getInt("hr_high_alert_number_secs_threshold", hrHighAlertNumSecsThreshold)

        // NEW: GeoJSON-based iBeacon parsing (replaces parcel/bundle array)
        val bleBeaconMutableList: MutableList<DataRepository.LocalizationBleBeaconItem> = mutableListOf()
        val geoJsonStr = appRestrictions.getString("localization_iBeacon_geojson", null)

        if (!geoJsonStr.isNullOrBlank()) {
            try {
                val parsed = parseIBeaconGeoJsonToItems(geoJsonStr)
                if (parsed.isNotEmpty()) {
                    bleBeaconMutableList.addAll(parsed)
                    Log.v(TAG, "=== Number of Localization iBeacons (GeoJSON): ${parsed.size} ===")
                } else {
                    Log.w(TAG, "GeoJSON parsed but produced 0 iBeacons. Leaving prior list untouched.")
                }
            } catch (e: JsonSyntaxException) {
                Log.e(TAG, "Failed to parse iBeacon GeoJSON (syntax): ${e.message}", e)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to parse iBeacon GeoJSON: ${t.message}", t)
            }
        } else {
//            Log.d(TAG, "localization_iBeacon_list is null or empty.")
        }

        // store settings persistently & send out events as necessary
        validateLicense(license) // validate the license

        runBlocking {
            var changed = false
            if (enableWifiBatteryOptimization != enableWifiBatteryOptimizationConfigUpdate) {
                enableWifiBatteryOptimization = enableWifiBatteryOptimizationConfigUpdate
                dataRepository.storeEnableWifiBatteryOptimization(enableWifiBatteryOptimization)
                Log.d(
                    TAG,
                    "enableWifiBatteryOptimization changed to $enableWifiBatteryOptimization"
                )
                changed = true
            }
            if (enableAllWifiBatteryOptimization != enableAllWifiBatteryOptimizationConfigUpdate) {
                enableAllWifiBatteryOptimization = enableAllWifiBatteryOptimizationConfigUpdate
                dataRepository.storeEnableAllWifiBatteryOptimization(
                    enableAllWifiBatteryOptimization
                )
                Log.d(
                    TAG,
                    "enableAllWifiBatteryOptimization changed to $enableAllWifiBatteryOptimization"
                )
                changed = true
            }
            if (wifiSsidList != wifiOptimizationSsidList) {
                Log.d(
                    TAG,
                    "MC wifiSsidList($wifiOptimizationSsidList), wifiSsidList($wifiSsidList)"
                )
                wifiSsidList = wifiOptimizationSsidList
                dataRepository.storeWifiSsidList(wifiSsidList)
                changed = true
            }
            if (changed) {
                // Send PowerManager Event only if there is a change in wifi optimization settings
                EventBus.getDefault()
                    .post(MessageEvent(OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED, 0))
            }

            if (categoryRolesJsonStr != null) {
                // 1. Prepare a Gson instance and the List<RoleCategoryEntry> type token
                val gson = Gson()
                val listType = object : TypeToken<List<CategoryRolesEntry>>() {}.type
                lateinit var entries: List<CategoryRolesEntry>

                try {
                    // 2. Parse the managed‐config JSON into your model class
                    entries = gson.fromJson(categoryRolesJsonStr, listType)
                    Log.i(TAG, "Attempting to parse the managed configs JSON String:  $entries")

                    // 3. Cache it into DataRepository (which now uses Gson under the hood)
                    dataRepository.storeCategoryRolesList(entries)

                } catch (e: JsonSyntaxException) {
                    Log.w(TAG, "Failed to parse managed‐config JSON, falling back", e)

                    // 4. Fallback to last‐stored in DataStore or to raw‐resource defaults
                    val fallback = dataRepository.getCategoryRolesList.first()
                    entries = if (fallback.isNotEmpty()) {
                        fallback
                    } else {
                        applicationContext.defaultRoleCategoryEntriesFromRaw()
                    }
                    dataRepository.storeCategoryRolesList(entries)
                }
                initCategoryAndRoleAndCategoryRolesList(entries)
            }

            if ((null != teamsNames) && (null != teamsColors)) {
                Log.v(TAG, "teamsNames: $teamsNames")
                Log.v(TAG, "teamsColors: $teamsColors")
                if (null != teamsNames) {
                    dataRepository.storeTeamNames(teamsNames)
                }
                if (null != teamsColors) {
                    dataRepository.storeTeamColors(teamsColors)
                }
                initTeamEntriesValuesList(teamsNames.split(","), teamsColors.split(","))
                initTeamAndTeamValue()
            }
            if ((null != categoryRolesJsonStr) || ((null != teamsNames) && (null != teamsColors))) {
                // Send out EventBus message even if there is no actual change
                EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))
            }

            changed = false
            if (takServerListConfig.size > 0) {
                for (i in takServerListConfig.indices) {
                    if (i < takServerList.size) {
                        val takServerConfig = takServerListConfig.get(i)
                        val takServer = takServerList.get(i)
                        // Server already present
                        if (takServerConfig != takServer) {
                            Log.d(
                                TAG,
                                "takServerListConfig.get($i).toString(): ${
                                    takServerListConfig.get(i).toString()
                                }"
                            )
                            changed = true
                            dataRepository.updateTakServerItem(i, takServerListConfig.get(i))
                            if (takServerConnDetailsChanged(takServer, takServerConfig)) {
                                Log.d(
                                    TAG,
                                    "takServerListConfig.get($i) connection details has changed. Sending SERVER_DETAILS_CHANGED"
                                )
                                // TODO: is there a sync for dataRepository so that we are guaranteed change when storing OR read from there without need for EventBus
                                handler.postDelayed({
                                    EventBus.getDefault().post(MessageEvent(SERVER_CHANGED, i))
                                }, EVENT_SEND_DELAY_MS)
                            }
                        }
                    } else {
                        changed = true
                        Log.d(TAG, "Additional server($i) added. Sending SERVER_ADDED")
                        dataRepository.addTakServerItem(takServerListConfig.get(i))

                        handler.postDelayed({
                            EventBus.getDefault().post(MessageEvent(SERVER_ADDED, i))
                        }, EVENT_SEND_DELAY_MS)
                    }
                }
                if (changed) {
                    if (takServerListConfig.size >= takServerList.size) {
                        takServerList = takServerListConfig
                    } else {
                        val mutableTakServerList = takServerList.toMutableList()
                        for (i in takServerListConfig.indices) {
                            mutableTakServerList.set(i, takServerListConfig.get(i))
                        }
                        takServerList = mutableTakServerList.toList()
                        Log.d(TAG, "takServerList: ${takServerList}")
                    }
                }
            }

            if ((null != esriEndpointConfigUpdate) && (esriEndpoint != esriEndpointConfigUpdate)) {
                esriEndpoint = esriEndpointConfigUpdate
                dataRepository.storeEsriEndpoint(esriEndpointConfigUpdate)
            }
            if ((0 != esriReportingIntervalConfigUpdate) && (esriReportingUpdateInterval != esriReportingIntervalConfigUpdate)) {
                esriReportingUpdateInterval = esriReportingIntervalConfigUpdate
                dataRepository.storeEsriReportingUpdateInterval(esriReportingIntervalConfigUpdate)
            }
            if (enableEsriEndpointConfigUpdate != enableEsriEndpoint) {
                enableEsriEndpoint = enableEsriEndpointConfigUpdate
                dataRepository.storeEnableEsriEndpoint(enableEsriEndpointConfigUpdate)
            }

            if ((null != tabletCommandEndpointConfigUpdate) && (tabletCommandEndpoint != tabletCommandEndpointConfigUpdate)) {
                tabletCommandEndpoint = tabletCommandEndpointConfigUpdate
                dataRepository.storeTabletCommandEndpoint(tabletCommandEndpointConfigUpdate)
            }
            if ((null != tabletCommandApikeyConfigUpdate) && (tabletCommandApikey != tabletCommandApikeyConfigUpdate)) {
                tabletCommandApikey = tabletCommandApikeyConfigUpdate
                dataRepository.storeTabletCommandApikey(tabletCommandApikeyConfigUpdate)
            }
            if ((0 != tabletCommandReportingIntervalConfigUpdate) && (tabletCommandReportingUpdateInterval != tabletCommandReportingIntervalConfigUpdate)) {
                tabletCommandReportingUpdateInterval = tabletCommandReportingIntervalConfigUpdate
                dataRepository.storeTabletCommandReportingUpdateInterval(
                    tabletCommandReportingIntervalConfigUpdate
                )
            }
            if (enableTabletCommandEndpointConfigUpdate != enableTabletCommandEndpoint) {
                enableTabletCommandEndpoint = enableTabletCommandEndpointConfigUpdate
                dataRepository.storeEnableTabletCommandEndpoint(
                    enableTabletCommandEndpointConfigUpdate
                )
            }

            if (enableTakEndpointConfigUpdate != enableTakEndpoint) {
                enableTakEndpoint = enableTakEndpointConfigUpdate
                dataRepository.storeEnableTakEndpoint(enableTakEndpointConfigUpdate)
            }

            changed = false
            if (bleBeaconMutableList.size > 0) {
                if (bleBeaconMutableList.size != localizationBleBeaconItemList.size) {
                    changed = true
                } else {
                    for (i in bleBeaconMutableList.indices) {
                        val bleBeaconUpdate = bleBeaconMutableList[i]
                        val locBleBeacon = localizationBleBeaconItemList[i]
                        if ((bleBeaconUpdate.uuid != locBleBeacon.uuid) ||
                            (bleBeaconUpdate.major != locBleBeacon.major) ||
                            (bleBeaconUpdate.minor != locBleBeacon.minor) ||
                            (bleBeaconUpdate.lat != locBleBeacon.lat) ||
                            (bleBeaconUpdate.lon != locBleBeacon.lon) ||
                            (bleBeaconUpdate.alt != locBleBeacon.alt)
                        ) {
                            changed = true
                            break
                        }
                    }
                }
                if (true == changed) {
                    localizationBleBeaconItemList = bleBeaconMutableList.toList()
                    dataRepository.storeLocalizationBleBeaconList(localizationBleBeaconItemList)
                    Handler(Looper.getMainLooper()).postDelayed({
                        EventBus.getDefault().post(MessageEvent(LOCALIZATION_BEACONS_CHANGED, 0))
                    }, EVENT_SEND_DELAY_MS)
                }
            }

            dataRepository.storeLicense(license)
            if (!dittoAppIdConfigUpdate.isEmpty()) {
                if (dittoAppId != dittoAppIdConfigUpdate) {
                    dittoAppId = dittoAppIdConfigUpdate
                    dataRepository.storeDittoAppId(dittoAppIdConfigUpdate)
                }
            }
            if (!dittoAuthTokenConfigUpdate.isEmpty()) {
                if (dittoAuthToken != dittoAuthTokenConfigUpdate) {
                    dittoAuthToken = dittoAuthTokenConfigUpdate
                    dataRepository.storeDittoAuthToken(dittoAuthTokenConfigUpdate)
                }
            }

            dataRepository.storeEnableLocationService(enableLocationServiceConfigUpdate)
            dataRepository.storeEnablePhysiologicalServices(enablePhysiologicalServicesConfigUpdate)
            dataRepository.storeEnableHeartRateService(enableHeartrateServiceConfigUpdate)
            dataRepository.storeEnableActivityRecognitionService(
                enableActivityRecognitionServiceConfigUpdate
            )
            dataRepository.storeEnableSkinTempService(enableSkinTempServiceConfigUpdate)
            dataRepository.storeEnableDittoService(enableDittoServiceConfigUpdate)
            dataRepository.storeEnableBleBeaconService(enableBleBeaconServiceConfigUpdate)
            if (constantReportingInterval != constantReportingIntervalConfigUpdate) {
                constantReportingInterval = constantReportingIntervalConfigUpdate
                dataRepository.storeConstantReportingInterval(constantReportingIntervalConfigUpdate)
            }
            if (whileAlertingReportingInterval != whileAlertingReportingIntervalConfigUpdate) {
                whileAlertingReportingInterval = whileAlertingReportingIntervalConfigUpdate
                dataRepository.storeWhileAlertingReportingInterval(
                    whileAlertingReportingIntervalConfigUpdate
                )
            }
            if (onFootReportingInterval != onFootReportingIntervalConfigUpdate) {
                onFootReportingInterval = onFootReportingIntervalConfigUpdate
                dataRepository.storeOnFootReportingInterval(onFootReportingIntervalConfigUpdate)
            }
            if (vehicleReportingInterval != vehicleReportingIntervalConfigUpdate) {
                vehicleReportingInterval = vehicleReportingIntervalConfigUpdate
                dataRepository.storeVehicleReportingInterval(vehicleReportingIntervalConfigUpdate)
            }
            if (stationaryReportingInterval != stationaryReportingIntervalConfigUpdate) {
                stationaryReportingInterval = stationaryReportingIntervalConfigUpdate
                dataRepository.storeStationaryReportingInterval(
                    stationaryReportingIntervalConfigUpdate
                )
            }
            if (enableDynamicReportingStrategy != enableDynamicReportingStrategyConfigUpdate) {
                dataRepository.storeEnableDynamicReportingStrategy(
                    enableDynamicReportingStrategyConfigUpdate
                )
            }

            dataRepository.storeEnableChatService(enableChatServiceConfigUpdate)
            dataRepository.storeEnableHailService(enableHailServiceConfigUpdate)
            dataRepository.storeEnableBatdokCot(enableBatdokCotConfigUpdate)
            dataRepository.storeEnableMulticast(enableMulticastConfigUpdate)
            dataRepository.storeEnableSmsForAlerts(enableSmsForAlertsConfigUpdate)
            dataRepository.storeSmsNumbersList(smsNumbersListConfigUpdate)
            dataRepository.storeBatteryWarnPercentage(battWarnPercentConfigUpdate)
            dataRepository.storeBatteryAlertPercentage(battAlertPercentConfigUpdate)
            dataRepository.storeEnablePhysiologicalAlerts(enablePhysiologicalAlertsConfigUpdate)
            dataRepository.storeRestingHeartRate(physiologicalMetricsRestingHeartRateConfigUpdate)
            dataRepository.storePhysiologicalMetricAge(physiologicalMetricsAgeServiceConfigUpdate)
            dataRepository.storePhysiologicalMetricHeightFeet(
                physiologicalMetricsHeightFeetServiceConfigUpdate
            )
            dataRepository.storePhysiologicalMetricHeightInches(
                physiologicalMetricsHeightInchesServiceConfigUpdate
            )
            dataRepository.storePhysiologicalMetricWeight(
                physiologicalMetricsWeightServiceConfigUpdate
            )
            dataRepository.storePhysiologicalMetricSex(physiologicalMetricsSexServiceConfigUpdate)
            dataRepository.storePhysiologicalMetricBloodType(
                physiologicalMetricsBloodTypeServiceConfigUpdate
            )
            if ((null != physiologicalMetricsAllergiesServiceConfigUpdate) && physiologicalMetricsAllergiesServiceConfigUpdate.isNotEmpty()) {
                dataRepository.storePhysiologicalMetricAllergyList(
                    physiologicalMetricsAllergiesServiceConfigUpdate.map { str ->
                        DataRepository.AllergyItem(name = str)
                    } ?: listOf(DataRepository.AllergyItem("N/A")))
            }
            dataRepository.storePhysiologicalMetricUserType(
                physiologicalMetricsUserTypeServiceConfigUpdate
            )

            dataRepository.storeHrHighAlertBpmThreshold(hrHighAlertBpmThresholdConfigUpdate)
            dataRepository.storeHrHighAlertNumSecsThreshold(hrHighAlertNumSecsThresholdConfigUpdate)

            if (!isStringEmptyJson(configJson)) {
                // Currently don't check for any changes but just post to dataRepository
                dataRepository.storeCustomDeveloperConfigJson(configJson)
                Handler(Looper.getMainLooper()).postDelayed({
                    EventBus.getDefault()
                        .post(MessageEvent(CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED, 0))
                }, EVENT_SEND_DELAY_MS)
            }

            // At the end, enable / disable services and then enable/disable based on license
            if (enableLocationServiceConfigUpdate != enableLocationService) {
                enableLocationService = enableLocationServiceConfigUpdate
                Log.d(TAG, "enableLocationService=$enableLocationService")
                if (!calledOnCreate) dataRepository.storeEnableLocationService(enableLocationService)
            }
            if (enablePhysiologicalServicesConfigUpdate != enablePhysiologicalServices) {
                enablePhysiologicalServices = enablePhysiologicalServicesConfigUpdate
                Log.d(TAG, "enablePhysiologicalServices=$enablePhysiologicalServices")
                if (!calledOnCreate) {
                    dataRepository.storeEnablePhysioServices(enablePhysiologicalServices)
                }
            }
            if (enableHeartrateServiceConfigUpdate != enableHeartrateService) {
                enableHeartrateService = enableHeartrateServiceConfigUpdate
                Log.d(TAG, "enableHeartRateService=$enableHeartrateService")
                if (!calledOnCreate) {
                    dataRepository.storeEnableHeartRateService(enableHeartrateService)
                }
            }
            if (enableActivityRecognitionServiceConfigUpdate != enableActivityRecognitionService) {
                enableActivityRecognitionService = enableActivityRecognitionServiceConfigUpdate
                Log.d(TAG, "enableActivityRecognitionService=$enableActivityRecognitionService")
                if (!calledOnCreate) dataRepository.storeEnableActivityRecognitionService(
                    enableActivityRecognitionService
                )
            }
            if (enableSkinTempServiceConfigUpdate != enableSkinTempService) {
                enableSkinTempService = enableSkinTempServiceConfigUpdate
                Log.d(TAG, "enableSkinTempService=$enableSkinTempService")
                if (!calledOnCreate) dataRepository.storeEnableSkinTempService(enableSkinTempService)
            }
            if (enableDittoServiceConfigUpdate != enableDittoService) {
                enableDittoService = enableDittoServiceConfigUpdate
                Log.d(TAG, "enableDittoService=$enableDittoService")
                if (!calledOnCreate) dataRepository.storeEnableDittoService(enableDittoService)
            }
            if (enableBleBeaconServiceConfigUpdate != enableBleBeaconService) {
                enableBleBeaconService = enableBleBeaconServiceConfigUpdate
                Log.d(TAG, "enableBleBeaconService=$enableBleBeaconService")
                if (!calledOnCreate) dataRepository.storeEnableBleBeaconService(
                    enableBleBeaconService
                )
            }
            if (enableDynamicReportingStrategy != enableDynamicReportingStrategyConfigUpdate) {
                enableDynamicReportingStrategy = enableDynamicReportingStrategyConfigUpdate
                Log.d(TAG, "enableDynamicReportingStrategy=$enableDynamicReportingStrategy")
                if (!calledOnCreate) dataRepository.getEnableMovementDetectionService.emit(
                    enableDynamicReportingStrategy
                )
            }
            if (enableChatServiceConfigUpdate != enableChatService) {
                enableChatService = enableChatServiceConfigUpdate
                Log.d(TAG, "enableChatService=$enableChatService")
                if (!calledOnCreate) {
                    dataRepository.storeEnableChatService(enableChatService)
                }
            }
            enableHailService = enableHailServiceConfigUpdate
            enableBatdokCot = enableBatdokCotConfigUpdate
            enableMulticastOnWifi = enableMulticastConfigUpdate

            enableSmsForAlerts = enableSmsForAlertsConfigUpdate
            smsNumbersList = smsNumbersListConfigUpdate
            battWarnPercent = battWarnPercentConfigUpdate
            battAlertPercent = battAlertPercentConfigUpdate
            if (enablePhysiologicalAlerts != enablePhysiologicalAlertsConfigUpdate) {
                enablePhysiologicalAlerts = enablePhysiologicalAlertsConfigUpdate
                Log.d(TAG, "enablePhysiologicalAlerts changed to $enablePhysiologicalAlerts")
                Handler(Looper.getMainLooper()).postDelayed({
                    EventBus.getDefault().post(
                        MessageEvent(
                            PHYSIOLOGICAL_ALERTS_ENABLED_CHANGED,
                            if (enablePhysiologicalAlerts) {
                                1
                            } else {
                                0
                            }
                        )
                    )
                }, EVENT_SEND_DELAY_MS)
            }
            if (restingHeartRate != physiologicalMetricsRestingHeartRateConfigUpdate) {
                Log.d(
                    TAG,
                    "Changed detected restingHeartRate(prev=$restingHeartRate, curr=$physiologicalMetricsRestingHeartRateConfigUpdate"
                )
                restingHeartRate = physiologicalMetricsRestingHeartRateConfigUpdate
            }
            if (ageValue != physiologicalMetricsAgeServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected ageValue(prev=$ageValue, curr=$physiologicalMetricsAgeServiceConfigUpdate)"
                )
                ageValue = physiologicalMetricsAgeServiceConfigUpdate
            }
            if (heightFeetValue != physiologicalMetricsHeightFeetServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected heightFeetValue(prev=$heightFeetValue, curr=$physiologicalMetricsHeightFeetServiceConfigUpdate)"
                )
                heightFeetValue = physiologicalMetricsHeightFeetServiceConfigUpdate
            }
            if (heightInchesValue != physiologicalMetricsHeightInchesServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected heightInchesValue(prev=$heightInchesValue, curr=$physiologicalMetricsHeightInchesServiceConfigUpdate)"
                )
                heightInchesValue = physiologicalMetricsHeightInchesServiceConfigUpdate
            }
            if (weightValue != physiologicalMetricsWeightServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected weightValue(prev=$weightValue, curr=$physiologicalMetricsWeightServiceConfigUpdate)"
                )
                weightValue = physiologicalMetricsWeightServiceConfigUpdate
            }
            if (sexValue != physiologicalMetricsSexServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected sexValue(prev=$sexValue, curr=$physiologicalMetricsSexServiceConfigUpdate)"
                )
                sexValue = physiologicalMetricsSexServiceConfigUpdate
            }
            if (bloodTypeValue != physiologicalMetricsBloodTypeServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected bloodTypeValue(prev=$bloodTypeValue, curr=$physiologicalMetricsBloodTypeServiceConfigUpdate)"
                )
                bloodTypeValue = physiologicalMetricsBloodTypeServiceConfigUpdate
            }
            if ((null != physiologicalMetricsAllergiesServiceConfigUpdate) && physiologicalMetricsAllergiesServiceConfigUpdate.isNotEmpty()) {
                val allergyListConfigUpdate =
                    physiologicalMetricsAllergiesServiceConfigUpdate.map { str ->
                        DataRepository.AllergyItem(name = str)
                    } ?: listOf(DataRepository.AllergyItem("N/A"))
                if (!listEqualsIgnoreOrder(allergyList, allergyListConfigUpdate)) {
                    Log.d(
                        TAG,
                        "Change detected allergyList(prev=$allergyList, curr=$allergyListConfigUpdate)"
                    )
                    allergyList = allergyListConfigUpdate
                }

            }
            if (userTypeValue != physiologicalMetricsUserTypeServiceConfigUpdate) {
                Log.d(
                    TAG,
                    "Change detected userTypeValue(prev=$userTypeValue, curr=$physiologicalMetricsUserTypeServiceConfigUpdate)"
                )
                userTypeValue = physiologicalMetricsUserTypeServiceConfigUpdate
            }

            if ((hrHighAlertBpmThreshold != hrHighAlertBpmThresholdConfigUpdate) || (hrHighAlertNumSecsThreshold != hrHighAlertNumSecsThresholdConfigUpdate)) {
                Log.d(
                    TAG,
                    "Change detected hrHighAlertBpmThreshold(prev=$hrHighAlertBpmThreshold, curr=$hrHighAlertBpmThresholdConfigUpdate) OR hrHighAlertNumSecsThreshold(prev=$hrHighAlertNumSecsThreshold, curr=$hrHighAlertNumSecsThresholdConfigUpdate)"
                )
                hrHighAlertBpmThreshold = hrHighAlertBpmThresholdConfigUpdate
                hrHighAlertNumSecsThreshold = hrHighAlertNumSecsThresholdConfigUpdate
                Handler(Looper.getMainLooper()).postDelayed({
                    EventBus.getDefault().post(
                        MessageEvent(
                            HR_ALERT_THRESHOLDS_CHANGED, if (enablePhysiologicalAlerts) {
                                1
                            } else {
                                0
                            }
                        )
                    )
                }, EVENT_SEND_DELAY_MS)
            }
        }


        // ui needs to be refreshed with new settings changes
        refreshUI(context)

        // Log all settings to logcat
        //logSettings()
    }


    /*
     * geoJson format example:
     *
     *      {"type":"FeatureCollection","name":"Test-02","properties":{"uuid":"426c7565-4368-6172-6d42-6561636f6e74","setName":"Test-01","generatedAt":"2025-08-20T16:10:32.551Z"},"features":[{"type":"Feature","properties":{"major":1,"minor":2},"geometry":{"type":"Point","coordinates":[-87.967657,45.459174,98.5]}},{"type":"Feature","properties":{"major":3,"minor":4},"geometry":{"type":"Point","coordinates":[-87.669969,45.511368,54.3]}},{"type":"Feature","properties":{"major":4,"minor":5},"geometry":{"type":"Point","coordinates":[-87.799846,45.209974,52.9]}}]}
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseIBeaconGeoJsonToItems(
        geoJson: String
    ): List<DataRepository.LocalizationBleBeaconItem> {
        return try {
            val gson = Gson()

            val root = gson.fromJson(geoJson, Map::class.java) as? Map<String, Any?> ?: return emptyList()
            val collProps = (root["properties"] as? Map<String, Any?>).orEmpty()
            val collectionUuid = (collProps["uuid"] as? String)?.lowercase()

            val features = (root["features"] as? List<Map<String, Any?>>).orEmpty()

            features.mapNotNull { f ->
                val fProps = (f["properties"] as? Map<String, Any?>).orEmpty()
                val major = (fProps["major"] as? Number)?.toInt()
                val minor = (fProps["minor"] as? Number)?.toInt()

                // UUID precedence: collection-level -> per-feature
                val uuid = collectionUuid ?: (fProps["uuid"] as? String)?.lowercase()

                val geom = (f["geometry"] as? Map<String, Any?>).orEmpty()
                val coords = (geom["coordinates"] as? List<Any?>).orEmpty()

                // GeoJSON uses [lon, lat, alt]
                val lon = (coords.getOrNull(0) as? Number)?.toDouble()
                val lat = (coords.getOrNull(1) as? Number)?.toDouble()
                val alt = (coords.getOrNull(2) as? Number)?.toDouble() ?: 0.0

                if (uuid == null || major == null || major < 0 || minor == null || minor < 0 || lat == null || lon == null) {
                    null
                } else {
                    DataRepository.LocalizationBleBeaconItem(
                        uuid = uuid,
                        major = major,
                        minor = minor,
                        lat = lat,
                        lon = lon,
                        alt = alt
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /*
    @Subscribe(threadMode = ThreadMode.MAIN)
    public fun onMessageEvent(event: TakServerConnStatusEvent) {
        var toastText = ""
        val connEvent = event.connEvent
        if (connEvent == CONNECTING) {
            toastText = getString(R.string.connecting_to_toast, name)
            Log.d(TAG, "Received TakServerConnStatusEvent: CONNECTING [$connEvent]")
        } else if (connEvent == CONNECTION_SUCCESS) {
            toastText = getString(R.string.connected_to_toast, name)
            Log.d(TAG, "Received TakServerConnStatusEvent: CONNECTION_SUCCESS [$connEvent]")
        } else if (connEvent == CONNECTION_FAILURE) {
            toastText = getString(R.string.connection_failed)
            Log.d(TAG, "Received TakServerConnStatusEvent: CONNECTION_FAILURE [$connEvent]")
        }
        //Log.d(TAG, "Showing toast w/ text: $toastText")
//        Toast.makeText(mainActivity, toastText, Toast.LENGTH_SHORT).show()
    }
    */
}

fun requestCallsignComplicationUpdate(context: Context) {
    val componentName = ComponentName(context, CallsignComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}

//TODO: Possibly move to chat service wherever it goes
fun requestChatComplicationUpdate(context: Context) {
    val componentName = ComponentName(context, ChatComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}

fun requestStatusIconsComplicationUpdate(context: Context) {
    Log.d(TAG, "requestStatusIconComplicationUpdate called")
    val componentName = ComponentName(context, StatusIconsComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}


//Contains functionality for all watch screens
@Composable
fun WearTAKCivApp(callsignLiveDataState: MutableLiveData<String>, licenseLiveDataState: MutableLiveData<String>) {
    val navController = rememberSwipeDismissableNavController()
    val context = LocalContext.current

    // collect license from data repo so we can see if it changes.
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")

    // react to changes in the license.
    LaunchedEffect(licenseKey) {
        if (isLicenseValid()) {
            navController.navigate("main_screen") {
//                popUpTo("invalid_license_screen") {
//                    inclusive = true
//                }
            }
        } else {
            navController.navigate("invalid_license_screen") {
                popUpTo("main_screen") {
                    inclusive = true
                }
            }
        }
    }


    //Log.d(TAG, "WearTAKCivApp composable called")
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = if (isLicenseValid()) "main_screen" else "invalid_license_screen"
    ) {
        composable("main_screen" ) {
            MainScreen(
                navController = navController,
                callsignState = callsignLiveDataState,
                dataRepository = dataRepository,
                context = context
            )
        }
//        composable(
//            "license_setting_screen") {
//            LicenseKeyScreen(navController, licenseSettingsState, context)
//        }
        composable(
            "invalid_license_screen") {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}

//Contains functionality for main screen
@Composable
fun MainScreen(
    navController: NavController,
    callsignState: MutableLiveData<String>,
    dataRepository: DataRepository, // Add dataRepository parameter
    context: Context // Add context parameter
) {
    //Log.d(TAG, "MainScreen called")
    val dismissState = rememberSwipeToDismissBoxState()
    val hasUnreadMessages by dataRepository.hasUnread.collectAsState() // monitor the number of unread messages
    val chatCheckedLive by enableChatServiceSettingsState.observeAsState()
    val isAlerting by SosActivity.isAlertingLiveData.observeAsState()

    LaunchedEffect(Unit) {
        dataRepository.refreshUnreadCount()
    }

    WearTAKCIVTheme {
        SwipeToDismissBox(
            state = dismissState,
            modifier = Modifier.unswipeable(),
            ) {
            Box(
                modifier = Modifier
                    .background(colors.background)
                    .fillMaxSize()
                    .padding(5.dp)
                    .unswipeable()
            ) {

                Column(
                    modifier = Modifier
                        .background(colors.background)
                        .padding(5.dp),
                    //                horizontalAlignment = Alignment.CenterHorizontally,
                    //                verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        modifier = Modifier
                            .padding(top = 30.dp)
                            .fillMaxWidth()
                            .weight(0.8f)
                    ) {
                        HeartRate(context)
                    }
                    Spacer(
                        modifier = Modifier.height(10.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(.9f),
//                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        SOSButton(context, isAlerting)
                        Spacer(
                            modifier = Modifier.width(10.dp)
                        )
                        MarkerDropButton(dataRepository = dataRepository, context = context)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 0.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        requestChatComplicationUpdate(context)
                        FilledIconButton(
                            onClick = { context.startActivity(Intent(context, ChatActivity::class.java))
                            },
                            modifier = Modifier.size(45.dp),
                            colors = IconButtonColors(
                                containerColor = Color.DarkGray,
                                contentColor = Color.Unspecified,
                                disabledContentColor = Color.Gray,
                                disabledContainerColor = Color.Black
                            ),
                            enabled = if (chatCheckedLive == true) true else false
                        ) {
                            if (chatCheckedLive == true) {
                                if (hasUnreadMessages) {
                                    requestChatComplicationUpdate(context)
                                    Icon(
                                        painter = painterResource(R.drawable.chat_newmessage),
                                        contentDescription = "Favorite icon",
                                        tint = Color.Unspecified,
                                        modifier = Modifier.size(25.dp),
                                    )
                                } else {
                                    requestChatComplicationUpdate(context)
                                    Icon(
                                        painter = painterResource(R.drawable.chat_nomessage),
                                        contentDescription = "Favorite icon",
                                        tint = Color.Unspecified,
                                        modifier = Modifier.size(25.dp),
                                    )
                                }
                        } else {
                                requestChatComplicationUpdate(context)
                                Icon(
                                    painter = painterResource(R.drawable.chat_nomessage),
                                    contentDescription = "Favorite icon",
                                    tint = Color.DarkGray,
                                    modifier = Modifier.size(25.dp),
                                )
                            }
                        }
                        Spacer(
                            modifier = Modifier.width(10.dp)
                        )
                        Callsign(context, callsignState)
                    }

                }
                TroubleshootingIcons()

                TimeWithSecondsAware(context)
            }
        }
    }
}

@Composable
fun TimeWithSecondsAware(context: Context) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isActive by remember { mutableStateOf(true) }
    var currentTime by remember { mutableStateOf(LocalTime.now()) }

    val is24Hour = remember { DateFormat.is24HourFormat(context) }

    val formatter = remember(is24Hour) {
//        if (is24Hour) {
            DateTimeFormatter.ofPattern("HH:mm:ss")
//        } else {
//            DateTimeFormatter.ofPattern("hh:mm:ss") // a = AM/PM
//        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isActive = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isActive) {
        while (isActive) {
            currentTime = LocalTime.now()
            delay(1000L)
        }
    }
    CurvedLayout(
        anchor = 90f,                 // bottom (6 o’clock)
        anchorType = AnchorType.Center,
        angularDirection = CurvedDirection.Angular.Reversed
    ) {
        curvedRow(modifier = CurvedModifier.padding()) {
            curvedText(currentTime.format(formatter),
                style = CurvedTextStyle())
        }
    }
}

fun findTeamIndex(teamName: String?) : Int {
    if (null == teamName) {
        return 0
    }
    val index = teamEntriesValuesList.ltEntries.indexOf(teamName)
    if (index == -1) {
        return 0
    }
    return index
}

fun displayServerDialogs(
    isNetworkConnected: Boolean,
    loadingDialog: String?,
    shouldShowLoadingDialog: MutableState<Boolean>,
    shouldShowConfirmDialog: MutableState<Boolean>,
    isServerConnected: MutableState<Boolean>) {
    Log.d(TAG, "displayServerDialogs isNetworkConnected=$isNetworkConnected, loadingDialog=$loadingDialog, shouldShowLoadingDialog=${shouldShowLoadingDialog.value}, shouldShowConfirmDialog=${shouldShowConfirmDialog.value}, isServerConnected=${isServerConnected.value}")
    if (isNetworkConnected) {
        if (loadingDialog == TAK_SERVER_CONNECTING_STRING) {
            Log.d(TAG, "displayServerDialogs: TAK_SERVER_CONNECTING_STRING")
            shouldShowLoadingDialog.value = true
            shouldShowConfirmDialog.value = false
        } else if (loadingDialog == TAK_SERVER_CONNECTED_STRING) {
            Log.d(TAG, "displayServerDialogs: TAK_SERVER_CONNECTED_STRING")
            shouldShowLoadingDialog.value = false
            shouldShowConfirmDialog.value = true
            isServerConnected.value = true
        } else if ((loadingDialog == TAK_SERVER_CONNECTION_FAILED_STRING) ||
            (loadingDialog == TAK_SERVER_DISCONNECTED_STRING)
        ) {
            Log.d(TAG, "displayServerDialogs: $loadingDialog")
            shouldShowLoadingDialog.value = false
            shouldShowConfirmDialog.value = true
            isServerConnected.value = false
        }
        Log.d(TAG, "displayServerDialogs: $loadingDialog")
    } else {
        Log.d(TAG, "displayServerDialogs: Network is NOT connected")
        shouldShowLoadingDialog.value = false
        shouldShowConfirmDialog.value = true
        isServerConnected.value = false
    }
    Log.d(TAG, "Exiting displayServerDialogs: isNetworkConnected=$isNetworkConnected, loadingDialog=$loadingDialog, shouldShowLoadingDialog=${shouldShowLoadingDialog.value}, shouldShowConfirmDialog=${shouldShowConfirmDialog.value}, isServerConnected=${isServerConnected.value}")
}

@Composable
fun alertChipColors(state: Utils.AlertChipState): Color = when (state) {
    Utils.AlertChipState.Emergency ->
        Color.Red
    Utils.AlertChipState.Warning   ->
        Color.Yellow
    Utils.AlertChipState.None      ->
        Color.Unspecified
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun HeartRate(context: Context) {
    val heartRateText by HeartrateService.hrStringLiveData.observeAsState()
    val physioCheckedLive by enablePhysiologicalServicesSettingsState.observeAsState()
    val physioAlertsCheckedLive by enablePhysiologicalAlertsSettingState.observeAsState()
    val alerts by dataRepository.physioAlertsFlow.collectAsState() // or collectAsStateWithLifecycle()

    // Derive the chip state from the latest list
    val chipState by remember(alerts) {
        derivedStateOf { alerts.alertChipState(physioAlertsCheckedLive) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        Chip(
            border = ChipDefaults.outlinedChipBorder(alertChipColors(chipState), borderWidth = 2.dp),
            onClick = {
                context.startActivity(Intent(context, PhysioAlertsActivity::class.java))
            },
            colors =  ChipDefaults.chipColors(
            backgroundColor = Color.Transparent,
            contentColor = Color.White,
        ),
            enabled = if (physioAlertsCheckedLive == true) true else false,
            icon = {
                Icon(
                    painter = if (physioAlertsCheckedLive == true) {
                        if (alerts.isNotEmpty()) painterResource(R.drawable.warning) else painterResource(R.drawable.vector)
                    } else painterResource(R.drawable.vector) ,
                    contentDescription = "Favorite icon",
                    tint = alertChipColors(chipState),
                    modifier = Modifier.size(17.dp),
                )
            },
            label = {
                Row(modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
                ) {
                    if (physioCheckedLive == true) {
                        Text(
                            text = " $heartRateText",
                            textAlign = TextAlign.Start,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = stringResource(R.string.bpm),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Light
                        )
                    } else {
                        Text(
                            text = " --- ",
                            textAlign = TextAlign.Start,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            },
        )
    }
}

enum class Connectivity { CELL, WIFI, NONE }
enum class TakServer { CONNECTED, CONNECTING, DISCONNECTED }
//2 functions below are meant to connect to complication service, TODO: Is there a better way to do this?
fun readConnectivity(networkManager: NetworkManager?): Connectivity {
    if (networkManager == null) return Connectivity.NONE
    networkManager.checkNetworkConnected()

    val net = NetworkManager.isNetworkConnectedLiveData.value == true
    val cell = NetworkManager.isCellularConnectedLiveData.value == true

    return when {
        net && cell -> Connectivity.CELL
        net && !cell -> Connectivity.WIFI
        else -> Connectivity.NONE
    }
}

fun readTakStatus(takConnectService: TAKConnectService?): TakServer {
    if (takConnectService == null) return TakServer.DISCONNECTED

    val connected = TAKConnectService.isAnyTakConnectedLiveData.value == true
    val connecting = TAKConnectService.isAnyTakConnectingLiveData.value == true

    return when {
        connected -> TakServer.CONNECTED
        connecting -> TakServer.CONNECTING
        else -> TakServer.DISCONNECTED
    }
}

fun buildComplicationString(
    context: Context,
    connectivity: Connectivity,
    tak: TakServer,
    locationEnabled: Boolean,
    coerceImpossible: Boolean = true // optional: see note below
): String {
    // If you never want to show "No Connect, Server Connected", coerce it:
    val effectiveTak = if (coerceImpossible && connectivity == Connectivity.NONE && tak == TakServer.CONNECTED) {
        TakServer.DISCONNECTED
    } else tak

    val connLabel = when (connectivity) {
        Connectivity.CELL -> getString(context, R.string.cell)
        Connectivity.WIFI -> getString(context, R.string.wifi)
        Connectivity.NONE -> getString(context, R.string.no_connection)
    }
    val takLabel = when (effectiveTak) {
        TakServer.CONNECTED -> getString(context, R.string.server_connected)
        TakServer.CONNECTING -> getString(context, R.string.server_connecting)
        TakServer.DISCONNECTED -> getString(context, R.string.server_disconnected)
    }
    val locLabel = if (locationEnabled) getString(context, R.string.location_enabled) else getString(context, R.string.location_disabled)

    return "$connLabel, $takLabel, $locLabel"
}

fun overallComplicationString(context: Context): String {
    val connectivity = readConnectivity(networkManager)
    val tak = readTakStatus(takConnectService)
    return buildComplicationString(context, connectivity, tak, enableLocationService)
}

fun chatComplicationString() {
    if (enableChatServiceSettingsState.value == true) {
        var hasUnreadMessages = false
        runBlocking {
            hasUnreadMessages = dataRepository.hasUnread.first()
        }
        if (hasUnreadMessages == true) {
            chatComplication = CHAT_ENABLED_NEW_MESSAGE
        } else {
            chatComplication = CHAT_ENABLED_NO_NEW_MESSAGE
        }
    } else {
        chatComplication = CHAT_DISABLED
    }
}

@Composable
fun TroubleshootingIcons() {
    val iconSizeSmall= 20.dp
    networkManager?.checkNetworkConnected()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp),
            contentAlignment = Alignment.TopCenter
    ) {
        val locationCheckedLive by enableLocationServiceSettingsState.observeAsState()
        val battery by BatteryService.batteryPercentageLiveData.observeAsState()
        //val takServer by remember{ mutableStateOf(enableTAKConnectService)}
        val anyTakServerConnected by TAKConnectService.isAnyTakConnectedLiveData.observeAsState()
        val anyTakServerConnecting by TAKConnectService.isAnyTakConnectingLiveData.observeAsState()
        val isConnected by NetworkManager.isNetworkConnectedLiveData.observeAsState()
        val cellConnected by NetworkManager.isCellularConnectedLiveData.observeAsState()
        val wifiConnected by NetworkManager.isWifiConnectedLiveData.observeAsState()


        // Icon on the top left curve
        Image(
            painter = if (anyTakServerConnected == true) painterResource(R.drawable.tak_server_connected) else if (anyTakServerConnecting == true) painterResource(R.drawable.tak_server_connecting) else painterResource(R.drawable.tak_server_disconnected),
            contentDescription = "Top Left Icon",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(
                    x = (40).dp, // Adjust this value based on the curve
                    y = (-10).dp // Adjust this value based on the curve
                )
                .size(25.dp) // Adjust icon size as needed
        )
        //Log.d(TAG, "AnyTakServerConnected = $anyTakServerConnected; AnyTakServerConnecting = $anyTakServerConnecting")


//         Icon on the bottom left curve
        Icon(
            painter =
            if (isConnected == true) {
                if (cellConnected == true) {
                    painterResource(R.drawable.cell)
                } else {
                    painterResource(R.drawable.wifi) // Replace with your icon
                }
            } else {
                painterResource(R.drawable.no_cell_wifi) // Replace with your icon
            },
            contentDescription = "Bottom Left Icon",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(
                    x = (15).dp, // Adjust this value
                    y = (10).dp // Adjust this value
                )
                .size(25.dp),
            tint = if (isConnected == true) Color.White else Color.Unspecified
        )

        // Icon on the top right curve
        Icon(
            if (locationCheckedLive == true) painterResource(R.drawable.location_on) else painterResource(R.drawable.location_off),
            contentDescription = "Top Right Icon",
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(
                    x = (-40).dp, // Adjust this value
                    y = (-10).dp // Adjust this value
                )
                .size(iconSizeSmall),
            tint = if (locationCheckedLive == true) Color.White else Color.DarkGray
        )


        // Icon on the bottom right curve

        val batteryIcon = when {
            battery!! <= 20 -> R.drawable.battery_1_bar
            battery!! <= 40 -> R.drawable.battery_3
            battery!! <= 60 -> R.drawable.battery_4_bar
            battery!! <= 80 -> R.drawable.battery_5
            else -> R.drawable.battery_full
        }
        Icon(
            painter = painterResource(batteryIcon), // Replace with your icon
            contentDescription = "Bottom Right Icon",
            tint = if (battery!! <= 20) Color.Red else Color.White,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(
                    x = (-15).dp, // Adjust this value
                    y = (10).dp // Adjust this value
                )
                .size(iconSizeSmall)

        )
        Log.d("Iscellconnected", cellConnected.toString())
    }
}


@Composable
fun Callsign(context: Context, callsignState: MutableLiveData<String>) {
    val greetingName: String? by callsignState.observeAsState()
    val maxChar = 10
    AssistChip(
        onClick = {
            context.startActivity(Intent(context, SettingsActivity::class.java))
//            navController.navigate("settings_screen")
                  },
        label = { Text(
            "${greetingName}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = if (greetingName!!.length <= maxChar) 15.sp else 13.sp,
            modifier = Modifier.padding(8.dp),

        )},
        shape = RoundedCornerShape(40.dp),
        )
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun SOSButton(context: Context, isAlertingLiveData: Boolean?) {
//    val shouldShowDialog = remember { mutableStateOf(false) }
//    val successBoolean = remember {mutableStateOf(false)}
//    val shouldShowConfirmationDialog = remember { mutableStateOf(false) }
//    val isConfirmed = remember { mutableStateOf(false) }
//    val takServer by TAKConnectService.isAnyTakConnectedLiveData.observeAsState()
//    val location = dataRepository.latestLocation.value
//    var locationChecked by remember{ mutableStateOf(enableLocationService)}
    var sosText = stringResource(R.string.sos)
    var sosLiveText = stringResource(R.string.sos_active)
    //Log.d(TAG, "SOSButtton")

//    if (shouldShowConfirmationDialog.value) {
//        ConfirmationDialog(
//            shouldShowConfirmationDialog,
//            rememberSwipeDismissableNavController(),
//            stringResource(R.string.sos_alert_prompt),
//            Icons.Default.Warning,
//            Color.Red,
//            "main_screen",
//            isConfirmed
//        )
//    }
//    if (isConfirmed.value) {
//        shouldShowDialog.value = true
//        if (takServer == true) {
//            if (location != null && locationChecked) {
//                successBoolean.value = true
//                SOSButtonEmergencyMessage()
//            }
//        } else {
//            if (MainActivity.enableDittoService && DittoService.isRunning.get()) {
//                Log.w(TAG, "Not connected to tak server, but Ditto is enabled and running. SOS will be sent through here.")
//                successBoolean.value = true
//                SOSButtonEmergencyMessage()
//            } else {
//                Log.e(TAG, "Not connected to tak server, Ditto is either not enabled or not running. Sending a failed SOS to the UI.")
//                successBoolean.value = false
//            }
//        }
//        SuccessDialog(shouldShowDialog = shouldShowDialog, successBoolean = successBoolean, isConfirmed)
//    }

    Button(
        onClick = {
            context.startActivity(Intent(context, SosActivity::class.java))
        },
        modifier = Modifier
            .border(
                width = 4.dp,
                color = Color.Red,
                shape = RoundedCornerShape(40.dp),
            )
            .size(110.dp, 60.dp),
        shape = RoundedCornerShape(40.dp),
        contentPadding = PaddingValues.Absolute(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isAlertingLiveData == true) Color.Red else Color.Transparent,
            contentColor = if (isAlertingLiveData == true) Color.White else Color.Red
        )
    ) {
        if (isAlertingLiveData == true) {
            Text (
                sosLiveText,
                fontSize = if (sosLiveText.length <= 15) 17.sp else 15.sp,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White,
            )
        } else {
            Text(
                sosText,
                fontSize = if (sosText.length <= 10) 25.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@SuppressLint("SuspiciousIndentation", "StateFlowValueCalledInComposition")
@Composable
fun MarkerDropButton(
    dataRepository: DataRepository,
    context: Context
) {
    IconButton(
        onClick = {
            context.startActivity(Intent(context, MarkerActivity::class.java))

            Log.d(TAG, "DropMarkerButton Clicked")
        },
        modifier = Modifier
            .border(
                width = 2.dp, color = Color.White, shape = CircleShape
            )
            .size(100.dp, 60.dp),
    ) {
        Icon(
            painterResource(R.drawable.markerdrop),
            contentDescription = "Marker Drop Button",
            modifier = Modifier.size(30.dp)
        )
    }
//    val shouldShowDialog = remember { mutableStateOf(false) }
//    val successBoolean = remember {mutableStateOf(false)}
//    val shouldShowConfirmationDialog = remember { mutableStateOf(false) }
//    val isConfirmed = remember { mutableStateOf(false) }
//    val takServer by TAKConnectService.isAnyTakConnectedLiveData.observeAsState()
//    val location = dataRepository.latestLocation.value
//    var locationChecked by remember{ mutableStateOf(enableLocationService)}
//
//
//    // State for button color
//    var isPressed by remember { mutableStateOf(false) }
//    val buttonColor by animateColorAsState(
//        targetValue = if (isPressed) Color.Green else Color.White,
//        label = "ButtonColorAnimation"
//    )
//
//    val coroutineScope = rememberCoroutineScope()
//
//    if (shouldShowConfirmationDialog.value) {
//        ConfirmationDialog(
//            shouldShowConfirmationDialog,
//            rememberSwipeDismissableNavController(),
//            stringResource(R.string.marker_drop_prompt),
//            Icons.Default.LocationOn,
//            Color.Green,
//            "main_screen",
//            isConfirmed
//        )
//    }
//    if (isConfirmed.value) {
//        shouldShowDialog.value = true
//        if (takServer == true) {
//            if (location != null && locationChecked) {
//                successBoolean.value = true
//                EventBus.getDefault().post(MessageEvent(DROP_MARKER, 0))
//            }
//        } else {
//            successBoolean.value = false
//        }
//        SuccessDialog(shouldShowDialog = shouldShowDialog, successBoolean = successBoolean, isConfirmed)
//    }
//
//    IconButton(
//        onClick = {
//		    shouldShowConfirmationDialog.value = true
//            vibrateWatch(context)
//
//            isPressed = true
//            coroutineScope.launch {
//                delay(500)  // temporary color change
//                isPressed = false
//            }
//            Log.d(TAG, "DropMarkerButton Clicked")
//        },
//        modifier = Modifier
//            .border(
//                width = 2.dp,
//                color = Color.White,
//                shape = CircleShape
//            )
//            .size(100.dp),
//    ) {
//        Icon(
//            painterResource(R.drawable.markerdrop),
//            contentDescription = "Localized description",
//            modifier = Modifier.size(30.dp)
//        )
//    }
}

@Composable
fun ActivityDisabledScreen(
    navController: NavController,
    exitActivity: () -> Unit,
    titleText: String,
    description: String) {
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
                text = titleText,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(5.dp))
            Text(
                text = description,
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.size(10.dp))
            Button(
                onClick = {
                    navController.navigate("invalid_license_screen") {
                        popUpTo("invalid_license_screen") { inclusive = true }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = "Go to Settings",
                    color = Color.Black,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = { exitActivity() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.DarkGray,
                    contentColor = Color.White
                ),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    text = "Exit",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true, showBackground = true)
@Composable
fun DefaultPreview() {
    var callsignState = MutableLiveData<String>("WEAROS-0")
    var licenseState = MutableLiveData<String>("WEAROS-0")

    WearTAKCivApp(callsignState, licenseState)
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun CallsignSettingsPreview() {
    var callsignState = MutableLiveData<String>("WEAROS-0")
    CallsignSetting(rememberSwipeDismissableNavController(), callsignState)
}



@Preview(widthDp = 192, heightDp = 500)
@Composable
fun OptimizeBatteryOnWifiSelectScreenPreview() {
    OptimizeBatteryOnWifiSelectScreen(rememberSwipeDismissableNavController())
}

@Preview(widthDp = 192, heightDp = 400)
@Composable
fun WifiSsidSelectionPreview() {
    WifiSsidSelectScreen(rememberSwipeDismissableNavController())
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun WifiSsidSettingPreview() {
    EditedWiFiSSIDSetting(rememberSwipeDismissableNavController(), 2)
}

//@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
//@Composable
//fun WifiSsidchipPreview() {
//    WifiSsidItemChip("a")
//}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun TAKServerChipPreview() {
    var takServerChecked by remember{ mutableStateOf(true)}
    SwipeableTakServerChip(
        0,
        "pp",
        "pp",
        checked = takServerChecked,
        onCheckedChange =  { takServerChecked },
        onPrimaryActionClick = {},
        onSecondaryActionClick = {}
    )
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun TAKSettingsPreview() {
    TakServerSetting(rememberSwipeDismissableNavController())
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun ServerDialogPreview() {
    val trueBoolean = remember { mutableStateOf(true) }
    val falseBoolean = remember { mutableStateOf(false) }

    ServerRegistrationDialog (
        falseBoolean,
        trueBoolean,
        trueBoolean,
        rememberSwipeDismissableNavController(),
        falseBoolean,
        falseBoolean
    )
}

@Preview(widthDp = 192, heightDp = 600, showBackground = true)
@Composable
fun EditServerPreview() {
    val isP12Cert = remember { mutableStateOf(true) }
    EditServerPage(rememberSwipeDismissableNavController(), 1)
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun ConfirmationDialogPreview() {
    val shouldShowDialog = remember { mutableStateOf(true) }
    val isConfirmed = remember { mutableStateOf(false) }

    ConfirmationDialog(
        shouldShowDialog = shouldShowDialog,
        rememberSwipeDismissableNavController(),
        "Do you want to trigger an SOS alert at your location?",
        Icons.Default.Warning,
        Color.Red,
        "pp",
        isConfirmed
    )
}

public fun Modifier.unswipeable() =
    this.then(
        Modifier.draggable(
            orientation = Orientation.Horizontal,
            enabled = true,
            state = DraggableState {}
        )
    )


