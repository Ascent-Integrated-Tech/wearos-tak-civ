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
import android.content.RestrictionEntry
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.provider.Settings.Secure
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.MutableLiveData
import androidx.navigation.NavController
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.CustomEmergencyEvent
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.CAT_MANUAL
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.DESC_SOS_ALERT
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.CALLSIGN_TEAM_ROLE_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_ADDED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.UPDATE_INTERVAL_CHANGED
import com.aitech.weartak_civ.presentation.MainActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsignSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimization
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimizationSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimizationState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimization
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimizationSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimizationState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.networkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.saveBattOnWifiString
import com.aitech.weartak_civ.presentation.MainActivity.Constants.saveBattOnWifiStringState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerListState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.team
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.wifiSsidList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.wifiSsidListSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.wifiSsidListState
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.BatteryService
import com.aitech.weartak_civ.service.CotDispatchService
import com.aitech.weartak_civ.service.CustomAccessibilityService
import com.aitech.weartak_civ.service.DittoService
import com.aitech.weartak_civ.service.HeartrateService
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.LicenseManager.validateLicense
import com.aitech.weartak_civ.service.LocationService
import com.aitech.weartak_civ.service.OffBodyDetectService
import com.aitech.weartak_civ.service.PowerManagerService
import com.aitech.weartak_civ.service.TAKConnectService
import com.beust.klaxon.Klaxon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import kotlin.random.Random
import android.content.RestrictionsManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedModifier
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.curvedRow
import androidx.wear.compose.foundation.padding
import androidx.wear.compose.material.LocalContentAlpha
import androidx.wear.compose.material.curvedText
import com.aitech.weartak_civ.events.MessageEvent.Companion.LOCALIZATION_BEACONS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_ADDED_VIA_UI
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_REMOVED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_TOGGLED
import com.aitech.weartak_civ.presentation.MainActivity.Constants.EVENT_SEND_DELAY_MS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.getMinReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startStopServicesLicense
import com.aitech.weartak_civ.service.BleBeaconService
import com.aitech.weartak_civ.service.MovementDetectionService
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTING_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTION_FAILED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_DISCONNECTED_STRING
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.min
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.Utils.isStringEmptyJson
import com.aitech.weartak_civ.complication.CallsignComplicationService
import com.aitech.weartak_civ.complication.ChatComplicationService
import com.aitech.weartak_civ.complication.ChatComplicationService.Companion.CHAT_DISABLED
import com.aitech.weartak_civ.complication.ChatComplicationService.Companion.CHAT_ENABLED_NEW_MESSAGE
import com.aitech.weartak_civ.complication.ChatComplicationService.Companion.CHAT_ENABLED_NO_NEW_MESSAGE
import com.aitech.weartak_civ.complication.LocationComplicationService
import com.aitech.weartak_civ.events.MessageEvent.Companion.CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED
import com.aitech.weartak_civ.presentation.MainActivity.Constants.chatComplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.connectivityStatusComplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBiometricsServiceLiveData
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableChatService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableChatServiceLiveData
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationServiceLiveData
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takConnectService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerStatusComplication


class MainActivity : ComponentActivity() {

    companion object Constants {
        val TAG = "WearTAK-" + MainActivity::class.java.simpleName
        const val EVENT_SEND_DELAY_MS = 1000L
        const val MOVEMENT_DETECTION_SERVICE_START_DELAY = 20_000L
        lateinit var mainActivity : MainActivity
        var mainActivityIsInit : MainActivity? = null
        lateinit var device_uid : String
        var callsign = "WEAROS-0"
        var enableLocationService : Boolean =  true
        var enableLocationServiceLiveData = MutableLiveData<Boolean>(true)
        var enableBleBeaconService : Boolean = false
        var localizationBleBeaconItemList : List<DataRepository.LocalizationBleBeaconItem> = listOf()
        var locationServiceRunning : Boolean = false
        var enableBiometricsService : Boolean = true
        var enableBiometricsServiceLiveData = MutableLiveData<Boolean>(false)
        var biometricServiceRunning : Boolean = false
        var enableDittoService : Boolean = false
        var dittoServiceRunning : Boolean = false
        var enableChatService : Boolean =  false
        var enableChatServiceLiveData = MutableLiveData<Boolean>(false)

        var connectivityStatusComplication : String = ""
        var takServerStatusComplication : String = ""
        var chatComplication : String = ""

        var enableCellularService: Boolean = true
        var isConnected : Boolean = true
        lateinit var dataRepository: DataRepository
        @SuppressLint("StaticFieldLeak")
        var networkManager : NetworkManager? = null
        var takConnectService : TAKConnectService? = null
        private val klaxon = Klaxon()

        var team = ""
        var role = ""
        var license = "License"
        var teamValue = ""
        var roleValue = ""
        var dittoAppId = ""
        var dittoAuthToken = ""
        lateinit var teamEntriesValuesList : TeamsJson
        lateinit var roleEntriesValuesList : RolesJson
        var callsignSettingsState = MutableLiveData<String>("WEAROS-0")
        val teamState = MutableLiveData<String>("")
        val teamSettingsState = MutableLiveData<String>("")
        val roleState = MutableLiveData<String>("")
        val roleSettingsState = MutableLiveData<String>("")
        val licenseSettingsState = MutableLiveData<String>("")

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

        var enableDynamicReportingStrategy = false
        var versionNumber : String = ""
        var logFile: java.io.File? = null

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

        fun startStopBiometricService() {
            if (isLicenseValid()) {
                if (enableBiometricsService) {
                    startBiometricService()
                } else {
                    if (HeartrateService.isRunning.get()) mainActivity.stopService(Intent(mainActivity, HeartrateService::class.java))
                }
            } else {
                if (HeartrateService.isRunning.get()) mainActivity.stopService(Intent(mainActivity, HeartrateService::class.java))
            }
        }

        fun startBiometricService() {
            if (enableBiometricsService) {
                if ((ActivityCompat.checkSelfPermission(
                        mainActivity.applicationContext,
                        Manifest.permission.BODY_SENSORS
                    ) != PackageManager.PERMISSION_GRANTED) &&
                    (ActivityCompat.checkSelfPermission(
                        mainActivity.applicationContext,
                        Manifest.permission.BODY_SENSORS_BACKGROUND
                    ) != PackageManager.PERMISSION_GRANTED)
                ) {
                    Log.d(TAG, "Don't have permissions to start body sensors in background. So NOT invoking HeartRateService")
                } else {
                    if (!HeartrateService.isRunning.get()) {
                        Log.d(TAG, "Starting HeartRateService")
                        mainActivity.startService(
                            Intent(
                                mainActivity,
                                HeartrateService::class.java
                            )
                        )
                    } else {
                        Log.d(TAG, "HeartRateService is already running." )
                    }
                }
            }  else {
                Log.d(TAG, "BiometricServices are disabled. Not starting HeartRateService")
            }
        }
        private fun hasGps(): Boolean = mainActivity.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

        fun startStopTAKConnectService() {
            if (isLicenseValid()) {
                startTAKConnectService()
            } else {
                if (TAKConnectService.isRunning.get()) mainActivity.stopService(Intent(mainActivity, TAKConnectService::class.java))
            }
        }

        fun startTAKConnectService() {
            if (!TAKConnectService.isRunning.get()) {
                Log.d(TAG, "Starting TAKConnectService")
                mainActivity.startForegroundService(Intent(mainActivity, TAKConnectService::class.java))
            } else {
                Log.d(TAG, "TAKConnectService is already running.")
            }
        }

        fun startStopDittoService() {
            if (isLicenseValid()) {
                if (enableDittoService) {
                    startDittoService()
                } else {
                    if (DittoService.isRunning.get()) mainActivity.stopService(Intent(mainActivity, DittoService::class.java))
                }
            } else {
                if (DittoService.isRunning.get()) mainActivity.stopService(Intent(mainActivity, DittoService::class.java))
            }
        }

        fun startDittoService() {
            if ((ActivityCompat.checkSelfPermission(
                mainActivity.applicationContext,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) != PackageManager.PERMISSION_GRANTED) && (ActivityCompat.checkSelfPermission(
                mainActivity.applicationContext,
                Manifest.permission.BLUETOOTH
            ) != PackageManager.PERMISSION_GRANTED))
            {
                Log.d(TAG, "Missing Nearby WiFi permissions or Bluetooth. So NOT invoking DittoService")
//                ActivityCompat.requestPermissions(this, Manifest.permission.BLUETOOTH)
            } else {
                if (enableDittoService) {
                    if (!DittoService.isRunning.get()) {
                        Log.d(TAG, "Starting DittoService")
                        mainActivity.startForegroundService(Intent(mainActivity, DittoService::class.java))
                    } else {
                        Log.d(TAG, "DittoService is already running")
                    }
                } else {
                    Log.d(TAG, "DittoService is disabled")
                }
            }
        }

        fun startStopLocationService() {
            if (isLicenseValid()) {
                if (enableLocationService) {
                    startLocationService()
                } else {
                    if (LocationService.isRunning.get()) mainActivity.stopService(Intent(mainActivity, LocationService::class.java))
                }
            } else {
                if (LocationService.isRunning.get()) mainActivity.stopService(Intent(mainActivity, LocationService::class.java))
            }
        }

        fun startLocationService() {
            if ((ActivityCompat.checkSelfPermission(
                    mainActivity.applicationContext,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED) &&
                (ActivityCompat.checkSelfPermission(
                    mainActivity.applicationContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED)
            ) {
                Log.d(TAG, "Don't have permissions. So NOT invoking requestLocationUpdates")
            } else {
                if (enableLocationService) {
                    if (!LocationService.isRunning.get()) {
                        Log.d(TAG, "Starting LocationService")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            mainActivity.startForegroundService(Intent(mainActivity, LocationService::class.java))
                        } else {
                            mainActivity.startService(Intent(mainActivity, LocationService::class.java))
                        }
                    } else {
                        Log.d(TAG, "LocationService is already running")
                    }
                } else {
                    Log.d(TAG, "enableLocationService is false. So NOT starting location service")
                }
            }
        }

        fun startStopBleBeaconService() {
            if (isLicenseValid()) {
                if (enableBleBeaconService) {
                    startBleBeaconService()
                } else {
                    if (BleBeaconService.isRunning.get()) mainActivity.stopService(Intent(mainActivity, BleBeaconService::class.java))
                }
            } else {
                if (BleBeaconService.isRunning.get()) mainActivity.stopService(Intent(mainActivity, BleBeaconService::class.java))
            }
        }

        fun startBleBeaconService() {
            if ((ActivityCompat.checkSelfPermission(mainActivity.applicationContext, Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) &&
                (ActivityCompat.checkSelfPermission(mainActivity.applicationContext, Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) &&
                (ActivityCompat.checkSelfPermission(mainActivity.applicationContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        != PackageManager.PERMISSION_GRANTED)) {
                Log.d(TAG, "Don't have permissions. So NOT starting BleBeaconService")
            } else {
                if (enableBleBeaconService) {
                    if (!BleBeaconService.isRunning.get()) {
                        Log.d(TAG, "Starting BleBeaconService")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            mainActivity.startForegroundService(Intent(mainActivity, BleBeaconService::class.java))
                        } else {
                            mainActivity.startService(Intent(mainActivity, BleBeaconService::class.java))
                        }
                    } else {
                        Log.d(TAG, "BleBeaconService is already running")
                    }
                } else {
                    Log.d(TAG, "BleBeaconService is disabled")
                }
            }
        }

        //TODO: Add Start/Stop Chat service functions

        fun startPowerManagerService() {
            if (!PowerManagerService.isRunning.get()) {
                Log.d(TAG, "Starting PowerManagerService")
                mainActivity.startForegroundService(Intent(mainActivity, PowerManagerService::class.java))
            } else {
                Log.d(TAG, "PowerManagerService is already running")
            }
        }

        fun startBatteryService() {
            if (!BatteryService.isRunning.get()) {
                Log.d(TAG, "Starting BatteryService")
                mainActivity.startForegroundService(Intent(mainActivity, BatteryService::class.java))
            } else {
                Log.d(TAG, "BatteryService is already running")
            }
        }

        fun startCotDispatchService() {
            if (!CotDispatchService.isRunning.get()) {
                Log.d(TAG, "Starting CotDispatchService")
                mainActivity.startService(Intent(mainActivity, CotDispatchService::class.java))
            } else {
                Log.d(TAG, "Cot DispatchService is already running")
            }
        }

        fun startMovementDetectionService() {
            if (enableDynamicReportingStrategy) {
                if (!MovementDetectionService.isRunning.get()) {
                    Log.d(TAG, "Starting MovementDetectionService")
                    Handler(Looper.getMainLooper()).postDelayed({ mainActivity.startService(Intent(mainActivity, MovementDetectionService::class.java)) }, MOVEMENT_DETECTION_SERVICE_START_DELAY)

                } else {
                    Log.d(TAG, "MovementDetection is already running")
                }
            } else {
                Log.d(TAG, "MovementDetection is disabled (enableDynamicReportingStrategy == false)")
            }
        }

        fun startStopMovementDetectionService() {
            if (isLicenseValid()) {
                if (enableDynamicReportingStrategy) {
                    startMovementDetectionService()
                } else {
                    if (MovementDetectionService.isRunning.get()) {
                        mainActivity.stopService(Intent(mainActivity, MovementDetectionService::class.java))
                    }
                }
            } else {
                if (MovementDetectionService.isRunning.get()) {
                    mainActivity.stopService(Intent(mainActivity, MovementDetectionService::class.java))
                }
            }
        }

        fun startStopoffBodyDetectService() {
            if (isLicenseValid()) {
                if (enableBiometricsService) {
                    startOffBodyDetectService()
                } else {
                    if (OffBodyDetectService.isRunning.get()) mainActivity.stopService(Intent(mainActivity, OffBodyDetectService::class.java))
                }
            } else {
                if (OffBodyDetectService.isRunning.get()) mainActivity.stopService(Intent(mainActivity, OffBodyDetectService::class.java))
            }
        }

        fun startOffBodyDetectService() {
            if (enableBiometricsService) {
                if ((ActivityCompat.checkSelfPermission(
                        mainActivity.applicationContext,
                        Manifest.permission.BODY_SENSORS
                    ) != PackageManager.PERMISSION_GRANTED) &&
                    (ActivityCompat.checkSelfPermission(
                        mainActivity.applicationContext,
                        Manifest.permission.BODY_SENSORS_BACKGROUND
                    ) != PackageManager.PERMISSION_GRANTED)
                ) {
                    Log.d(TAG, "Don't have permissions to start body sensors in background. So NOT invoking OffBodyDetectService")
                } else {
                    if (!OffBodyDetectService.isRunning.get()) {
                        Log.d(TAG, "Starting OffBodyDetectService")
                        mainActivity.startService(
                            Intent(
                                mainActivity,
                                OffBodyDetectService::class.java
                            )
                        )
                    } else {
                        Log.d(TAG, "OffBodyDetectService is already running." )
                    }
                }
            }  else {
                Log.d(TAG, "BiometricServices are disabled. Not starting OffBodyDetectService")
            }
        }


        fun startStopServicesLicense(context: Context) {
            if (isLicenseValid()) {
                Log.d(TAG, """
                        ===   Application Services Info   ===
                        =====================================
                        """.trimIndent())
                startTAKConnectService()
                startOffBodyDetectService()
                startBiometricService()
                startBatteryService()
                startMovementDetectionService()
                startLocationService()
                startCotDispatchService()
                startDittoService()
                startPowerManagerService()
                startBleBeaconService()
            } else {
                Log.d(TAG, """
                            ===   Application Services Info   ===                                
                            """.trimIndent())
                if (TAKConnectService.isRunning.get()) {
                    mainActivity.stopService(Intent(mainActivity, TAKConnectService::class.java))
                }
                if (OffBodyDetectService.isRunning.get()) {
                    mainActivity.stopService(Intent(mainActivity, OffBodyDetectService::class.java))
                }
                if (HeartrateService.isRunning.get()) {
                    mainActivity.stopService(Intent(mainActivity, HeartrateService::class.java))
                }
                if (BatteryService.isRunning.get()) {
                    mainActivity.stopService(Intent(mainActivity, BatteryService::class.java))
                }
                if (LocationService.isRunning.get()) {
                    mainActivity.stopService(Intent(mainActivity, LocationService::class.java))
                }
                if (CotDispatchService.isRunning.get()) {
                    mainActivity.stopService(Intent(mainActivity, CotDispatchService::class.java))
                }
                if (enableDittoService && DittoService.isRunning.get()) {
                    mainActivity.stopService(Intent(mainActivity, DittoService::class.java))
                }
                if (PowerManagerService.isRunning.get()) {
                    mainActivity.stopService(Intent(mainActivity, PowerManagerService::class.java))
                }
                if (BleBeaconService.isRunning.get()) {
                    mainActivity.stopService(Intent(mainActivity, BleBeaconService::class.java))
				}
                if (MovementDetectionService.isRunning.get()) {
                    mainActivity.stopService(Intent(mainActivity, MovementDetectionService::class.java))
                }
            }
        }

        fun getMinReportingInterval(takServerList : List<DataRepository.TakServerItem>) : Int {
            var minReportingInterval : Int = 100000001
            for (takServer in takServerList) {
                if (takServer.isEnabled) {
                    Log.d(TAG, "getMinReportingInterval: ${takServer.address} reportingUpdateInterval ${takServer.reportingUpdateInterval}")
                    minReportingInterval = min(minReportingInterval, takServer.reportingUpdateInterval)
                }
            }
            if (minReportingInterval == 100000001) {
                minReportingInterval = 10
            }
            return minReportingInterval
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

//    private fun refreshPermissions() {
//        if ((ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) && (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
//            if (isLicenseValid()) {
//                Log.d(TAG, "Starting LocationService...")
//                startLocationService()
//            }
//        } else {
//            Log.d(TAG, "ContextCompat.checkSelfPermission for Location did not work. Trying to manually request")
//            ActivityCompat.requestPermissions(this,
//                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 0
//            )
//        }
//
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED) {
//            if (isLicenseValid()) {
//                Log.d(TAG, "Refreshing BiometricService...")
//                startBiometericService()
//            }
//        }
//
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) {
//            if (isLicenseValid()) {
//                Log.d(TAG, "Refreshing DittoService...")
//                startDittoService()
//            }
//        }
//    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called w/ categories (${intent.categories}) and action (${intent.action})")
    }

    override fun onDestroy() {
        super.onDestroy()
        /*
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }

         */
        mainActivity.stopService(Intent(mainActivity, CotDispatchService::class.java))
        mainActivity.stopService(Intent(mainActivity, MovementDetectionService::class.java))
        mainActivity.stopService(Intent(mainActivity, BatteryService::class.java))
        mainActivity.stopService(Intent(mainActivity, PowerManagerService::class.java))
        if (enableBiometricsService) {
            mainActivity.stopService(Intent(mainActivity, HeartrateService::class.java))
        }
        mainActivity.stopService(Intent(mainActivity, OffBodyDetectService::class.java))
        if (enableLocationService) {
            mainActivity.stopService(Intent(mainActivity, LocationService::class.java))
        }
        mainActivity.stopService(Intent(mainActivity, TAKConnectService::class.java))
        if (DittoService.isRunning.get()) {mainActivity.stopService(Intent(mainActivity, DittoService::class.java)) }

        networkManager?.deInit()

        unregisterReceiver(restrictionReceiver)

        /*
        // Unregister the callback.
        runBlocking {
            measureClient.unregisterMeasureCallbackAsync(
                DataType.Companion.HEART_RATE_BPM,
                heartRateCallback
            )
        }
        lifecycleScope.launch {
            healthServicesRepository.unregisterForHeartRateData()
        }

         */

        //fusedLocationClient?.removeLocationUpdates(locationCallback)
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

    private suspend fun initRoleAndRoleValue() {
        // what role am I?
        role = dataRepository.getRole.first()
        var roleIndex = roleEntriesValuesList.lrEntries.indexOf(role)
        // needed in the event my current role doesn't exist in the list of available roles.
        if (roleIndex == -1) {
            role = roleEntriesValuesList.lrEntries[0]
            roleIndex = 0
        }
        roleValue = roleEntriesValuesList.lrValues[roleIndex]
    }

    fun initSettings() {
        // This function reads in data from dataRepository and sets MainActivity settings (except those for UI settings screens) as well as other service settings
        runBlocking {
            Log.d(TAG, """=== === Initializing Settings === === ===""".trimIndent())
            // what is my callsign?
            callsign = dataRepository.getCallsign.first()
            // on the first install, callsign has no value, we need to fill it with a random number
            if (callsign.equals("")) {
                callsign = "WEAROS-" + Random.nextInt(1, 10000).toString()
                dataRepository.storeCallsign(callsign)
                Log.v(TAG, "First install --> Assigning a random callsign: $callsign")
            }

            // what are the teams I can be?
            val teamNames = dataRepository.getTeamNames.first().split(",")
            val teamColors = dataRepository.getTeamColors.first().split(",")
            initTeamEntriesValuesList(teamNames, teamColors)
            initTeamAndTeamValue()

            // what roles can I be?
            val rolesList = dataRepository.getRolesList.first().split(',')
            roleEntriesValuesList = RolesJson(rolesList, rolesList)
            initRoleAndRoleValue()

            license = dataRepository.getLicense.first()

            takServerList = dataRepository.getTakServerList.first()

            enableLocationService = dataRepository.getEnableLocationService.first()
            enableBiometricsService = dataRepository.getEnableBiometricsService.first()
            enableDittoService = dataRepository.getEnableDittoService.first()
            enableChatService = dataRepository.getEnableChatService.first()


            val minReportingInterval = getMinReportingInterval(takServerList)

            Log.d(TAG, "Setting storeReportingUpdateInterval & COT reportingUpdateIntervalSecs to $minReportingInterval secs")
            dataRepository.storeReportingUpdateInterval(minReportingInterval)
            CotDispatchService.reportingUpdateIntervalSecs = minReportingInterval
            CotDispatchService.currReportingIntervalInSecs = minReportingInterval
            //LocationService.locationUpdateFastestIntervalSecs = CotDispatchService.reportingUpdateIntervalSecs

            enableWifiBatteryOptimization = dataRepository.getEnableWifiBatteryOptimization.first()
            PowerManagerService.isWifiOpt = enableWifiBatteryOptimization
            enableAllWifiBatteryOptimization = dataRepository.getEnableAllWifiBatteryOptimization.first()
            PowerManagerService.isAllWifiOpt = enableAllWifiBatteryOptimization
            wifiSsidList = dataRepository.getWifiSsidList.first()
            saveBattOnWifiString = generateSaveBattOnWifiString(enableWifiBatteryOptimization, enableAllWifiBatteryOptimization)

            enableDynamicReportingStrategy = dataRepository.getEnableDynamicReportingStrategy.first()

            enableBleBeaconService = dataRepository.getEnableBleBeaconService.first()
            localizationBleBeaconItemList = dataRepository.getLocalizationBleBeaconItemList.first()

            dittoAppId = dataRepository.getDittoAppId.first()
            dittoAuthToken = dataRepository.getDittoAuthToken.first()

            Log.d(TAG, "============= Done Initializing Settings ===============")
        }
    }

    private fun logSettings() {
        var logOutput = """
                    === === Configuration Settings === ===
                    ${String.format("%-25s %-30s", "[Settings]", "[Value]")}
                    === === === === === === === === === ==
                    ${String.format("%-25s %-30s", "Device ID:", device_uid)}
                    ${String.format("%-25s %-30s", "Callsign:", callsign)}
                    ${String.format("%-25s %-30s", "Team:", team)}
                    ${String.format("%-25s %-30s", "Role:", role)}
                    ${String.format("%-25s %-30s", "Teams:", teamEntriesValuesList.ltEntries)}
                    ${String.format("%-25s %-30s", "Colors:", teamEntriesValuesList.ltValues)}
                    ${String.format("%-25s %-30s", "Roles:", roleEntriesValuesList.lrEntries)}
                    
                    === === Server Settings === ===
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
                    ${String.format("%-25s %-30s", "Server $i Update Intrvl:", "${takServer.reportingUpdateInterval} sec")}
                       
                    
                    """.trimIndent()
        }

        logOutput += """
                    === === Service Settings === ===
                    ${String.format("%-25s %-30s", "License Key:", license)}
                    ${String.format("%-25s %-30s", "Ditto App Id:", dittoAppId)}
                    ${String.format("%-25s %-30s", "Ditto Auth Token:", dittoAuthToken)}
                    ${String.format("%-25s %-30s", "Enable Dynamic Reporting:", enableDynamicReportingStrategy.toString())}
                    ${String.format("%-25s %-30s", "Enable Loc:", enableLocationService.toString())}
                    ${String.format("%-25s %-30s", "Enable Bio:", enableBiometricsService.toString())}
                    ${String.format("%-25s %-30s", "Enable Ditto:", enableDittoService.toString())}
                    ${String.format("%-25s %-30s", "Enable wifi opt:", enableWifiBatteryOptimization.toString())}
                    ${String.format("%-25s %-30s", "Enable all wifi:", enableAllWifiBatteryOptimization.toString())}
                    ${String.format("%-25s %-30s", "Wifi ssids list:", wifiSsidList.toString())}
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
            Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG, """
                === === ===  Permissions  === === ===
                Missing: $missingPermissions
                """.trimIndent()
            )
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
            //refreshPermissions()
        } else {
            Log.d(TAG, """
                === === ===  Permissions  === === ===
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
        Log.d(TAG, "MainActivity onCreate called")
        mainActivity = this
        mainActivityIsInit = this
        device_uid = "WEAROS_" + Secure.getString(this.getContentResolver(), Secure.ANDROID_ID)

        val context = mainActivity.applicationContext
        dataRepository = DataRepository(this)
        initSettings()
        logSettings()
        refreshUI(context)

        restrictionsManager = getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        // Register BroadcastReceiver to listen for changes
        restrictionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "onReceive Broadcast: ${intent?.action}")
                if (intent?.action == Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED) {
                    Log.d(TAG, "Managed Configurations Updated")
                    handleManagedConfigUpdate(false)
                }
            }
        }
        registerReceiver(restrictionReceiver, IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED))
        // Handle the initial configuration when the app starts
        handleManagedConfigUpdate(true)

//        Log.d(TAG, "Device UID is $device_uid")
        /*
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

         */
        //val file: File? = null
        //logFile = java.io.File(file, "/sdcard/Download/WearTAK_logs.txt")

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
        startStopServicesLicense(context)

        // Kiosk needs to be allow com.samsung.accessibility for code below to work
        val isAccessibilityServiceEnabled = isAccessibilityServiceEnabled(mainActivity, CustomAccessibilityService::class.java)
        Log.d(TAG, "isAccessibilityServiceEnabled $isAccessibilityServiceEnabled")
        if (!isAccessibilityServiceEnabled) {
            Log.d(TAG, "isAccessibilityServiceEnabled false and trying to open Accessibility Settings")
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
    }

    private fun refreshUI(context: Context) {
        Log.v(TAG, "=== === Refreshing UI  === === ")
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
        wifiSsidListState.postValue(wifiSsidList) // tell ui
        wifiSsidListSettingsState.postValue(wifiSsidList) // ephemeral ui value
        saveBattOnWifiStringState.postValue(saveBattOnWifiString)
        enableLocationServiceLiveData.postValue(enableLocationService)
        enableBiometricsServiceLiveData.postValue(enableBiometricsService)
        enableChatServiceLiveData.postValue(enableChatService)

        requestCallsignComplicationUpdate(context)
        requestLocationComplicationUpdate(context)
        requestHeartRateComplicationUpdate(context)
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

    private fun handleManagedConfigUpdate(calledOnCreate: Boolean) {
        Log.v(TAG, " === === Handling Managed Config Update ===")

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
        val rolesEntries = appRestrictions.getString("custom_roles", null)

        var serverEnableTAKConnectService: Boolean = true
        var serverName: String? = null
        var serverAddress: String? = null
        var serverPort: String? = null
        var serverUseCert: Boolean = false
        var serverUsername: String? = null
        var serverPassword: String? = null
        var serverCertPassword: String? = null
        var serverCertificate: String? = null
        var serverUpdateInterval: Int = CotDispatchService.reportingUpdateIntervalSecs
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
                    serverUpdateInterval = serverBundle.getInt("update_interval", serverUpdateInterval)
                    takServerListConfig.add(DataRepository.TakServerItem(serverEnableTAKConnectService, serverName, serverAddress, serverPort.toInt(), serverUseCert, serverUsername, serverPassword, serverCertificate, serverCertPassword, serverUpdateInterval))
                    Log.v(TAG, "========================================")
                } else {
                    Log.e(TAG, "Invalid server bundle at index $i")
                }
            }
        } else {
            Log.d(TAG, "tak_server_list is null or empty.")
        }

        val enableWifiBatteryOptimizationConfigUpdate = appRestrictions.getBoolean("wifiOptimization_service_enabled", enableWifiBatteryOptimization)
        val enableAllWifiBatteryOptimizationConfigUpdate = appRestrictions.getBoolean("wifiOptimization_all_enabled", enableAllWifiBatteryOptimization)
        val wifiOptimizationSsidString = appRestrictions.getString("wifiOptimization_ssid_list", convertWifiSsidListToStringWithSeparator(wifiSsidList, "||"))
        val wifiOptimizationSsidList: List<DataRepository.WifiSsidItem> = wifiOptimizationSsidString.split("||") // Split by two pipes, unlikely a random generated SSID has
            .map { it.trim() } // Trim whitespace from each SSID
            .filter { it.isNotEmpty() } // Remove any empty values
            .map { DataRepository.WifiSsidItem(it) } // Map each SSID to a WifiSsidItem

        val enableLocationServiceConfigUpdate = appRestrictions.getBoolean("location_service_enabled", enableLocationService)
        val enableBiometricsServiceConfigUpdate = appRestrictions.getBoolean("biometric_service_enabled", enableBiometricsService)
        val enableDittoServiceConfigUpdate = appRestrictions.getBoolean("ditto_service_enabled", enableDittoService)
        val enableChatServiceConfigUpdate = appRestrictions.getBoolean("chat_service_enabled", enableChatService)

        val configJson = appRestrictions.getString("custom_developer_config_json", "")

        val dittoAppIdConfigUpdate = appRestrictions.getString("ditto_app_id", "")
        val dittoAuthTokenConfigUpdate = appRestrictions.getString("ditto_auth_token", "")
        Log.v(TAG, "Ditto App ID: $dittoAppIdConfigUpdate | Auth Token: $dittoAuthTokenConfigUpdate")

        val enableDynamicReportingStrategyConfigUpdate = appRestrictions.getBoolean("enable_dynamic_reporting_strategy", enableDynamicReportingStrategy)

        val enableBleBeaconServiceConfigUpdate = appRestrictions.getBoolean("ble_beacon_service_enabled", enableBleBeaconService)
        val bleBeaconParcels = appRestrictions.getParcelableArray("localization_iBeacon_list")  // we don't know how many configurations will be set on the Knox Managed Configuration page, so we to loop through all the Localization iBeacons in the list.
        val bleBeaconMutableList : MutableList<DataRepository.LocalizationBleBeaconItem> = mutableListOf()
        if (bleBeaconParcels != null) {
            val bleBeaconCount = bleBeaconParcels.size
            Log.v(TAG, "=== Number of Localization iBeacons: $bleBeaconCount ===")

            var uuid: String? = null
            var major: Int = -1
            var minor: Int = -1
            var lat: String? = null
            var lon: String? = null
            var alt: String? = null
            // Loop through each Localization iBeacon
            for (i in bleBeaconParcels.indices) {
                // Each element should be a Bundle representing one localization iBeacon
                val bleBeaconBundle = bleBeaconParcels[i] as? Bundle
                if (bleBeaconBundle != null) {
                    Log.v(TAG, "=== Localization iBeacon [$i] ===")
                    // Iterate through all keys in this localization iBeacon Bundle
                    for (key in bleBeaconBundle.keySet()) {
                        val value = bleBeaconBundle.get(key)
                        // Format each key/value pair into a fixed-width layout for clarity.
                        Log.v(TAG, String.format("   %-30s : %s", key, value))
                    }

                    uuid = bleBeaconBundle.getString("localization_iBeacon_uuid", null)
                    major = bleBeaconBundle.getInt("localization_iBeacon_major", -1)
                    minor = bleBeaconBundle.getInt("localization_iBeacon_minor", -1)
                    lat = bleBeaconBundle.getString("localization_iBeacon_lat", null)
                    lon = bleBeaconBundle.getString("localization_iBeacon_lon", null)
                    alt = bleBeaconBundle.getString("localization_iBeacon_alt", null)

                    if ((null != uuid) && (major >= 0) && (minor >= 0) && (null != lat) && (null != lon) && (null != alt)) {
                        bleBeaconMutableList.add(DataRepository.LocalizationBleBeaconItem(uuid, major, minor,
                            lat.toDouble(), lon.toDouble(), alt.toDouble()))
                    } else {
                        Log.w(TAG, "Incorrect value received: uuid($uuid), major($major), minor($minor), lat($lat), lon($lon), alt($alt)")
                    }
                    Log.v(TAG, "========================================")
                } else {
                    Log.e(TAG, "Invalid localization iBeacon bundle at index $i")
                }
            }
        } else {
            Log.d(TAG, "localization_iBeacon_list is null or empty.")
        }

        // store settings persistently & send out events as necessary
        validateLicense(license) // validate the license

        runBlocking {
            var changed = false
            if (enableWifiBatteryOptimization != enableWifiBatteryOptimizationConfigUpdate) {
                enableWifiBatteryOptimization = enableWifiBatteryOptimizationConfigUpdate
                dataRepository.storeEnableWifiBatteryOptimization(enableWifiBatteryOptimization)
                Log.d(TAG, "enableWifiBatteryOptimization changed to $enableWifiBatteryOptimization")
                changed = true
            }
            if (enableAllWifiBatteryOptimization != enableAllWifiBatteryOptimizationConfigUpdate) {
                enableAllWifiBatteryOptimization = enableAllWifiBatteryOptimizationConfigUpdate
                dataRepository.storeEnableAllWifiBatteryOptimization(
                    enableAllWifiBatteryOptimization
                )
                Log.d(TAG, "enableAllWifiBatteryOptimization changed to $enableAllWifiBatteryOptimization")
                changed = true
            }
            if (wifiSsidList != wifiOptimizationSsidList) {
                Log.d(TAG, "MC wifiSsidList($wifiOptimizationSsidList), wifiSsidList($wifiSsidList)")
                wifiSsidList = wifiOptimizationSsidList
                dataRepository.storeWifiSsidList(wifiSsidList)
                changed = true
            }
            if (changed) {
                // Send PowerManager Event only if there is a change in wifi optimization settings
                EventBus.getDefault().post(MessageEvent(OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED, 0))
            }

            if (null != rolesEntries) {
                Log.v(TAG, "rolesEntries: $rolesEntries")
                dataRepository.storeRolesList(rolesEntries)
                val rolesList = rolesEntries.split(",")
                roleEntriesValuesList = RolesJson(rolesList, rolesList)
                initRoleAndRoleValue()
            }
            if ((null != teamsNames) && (null != teamsColors)) {
                Log.v(TAG, "teamsNames: $teamsNames")
                Log.v(TAG, "teamsColors: $teamsColors")
                if (null != teamsNames) { dataRepository.storeTeamNames(teamsNames) }
                if (null != teamsColors) { dataRepository.storeTeamColors(teamsColors) }
                initTeamEntriesValuesList(teamsNames.split(","), teamsColors.split(","))
                initTeamAndTeamValue()
            }
            if ((null != rolesEntries) || ((null != teamsNames) && (null != teamsColors))) {
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
                            Log.d(TAG, "takServerListConfig.get($i).toString(): ${takServerListConfig.get(i).toString()}")
                            changed = true
                            dataRepository.updateTakServerItem(i, takServerListConfig.get(i))
                            if (takServerConnDetailsChanged(takServer, takServerConfig)) {
                                Log.d(TAG, "takServerListConfig.get($i) connection details has changed. Sending SERVER_DETAILS_CHANGED")
                                // TODO: is there a sync for dataRepository so that we are guaranteed change when storing OR read from there without need for EventBus
                                handler.postDelayed({ EventBus.getDefault().post(MessageEvent(SERVER_CHANGED, i)) }, EVENT_SEND_DELAY_MS)
                            }
                        }
                    } else {
                        changed = true
                        Log.d(TAG, "Additional server($i) added. Sending SERVER_ADDED")
                        dataRepository.addTakServerItem(takServerListConfig.get(i))

                        handler.postDelayed({ EventBus.getDefault().post(MessageEvent(SERVER_ADDED, i)) }, EVENT_SEND_DELAY_MS)
                    }
                }
                if (changed) {
                    if (takServerListConfig.size >= takServerList.size) {
                        takServerList = takServerListConfig
                    } else {
                        val mutableTakServerList = takServerList.toMutableList()
                        for (i in takServerListConfig.indices) {
                            mutableTakServerList.set(i,takServerListConfig.get(i))
                        }
                        takServerList = mutableTakServerList.toList()
                        Log.d(TAG, "takServerList: ${takServerList}")
                    }
                }
                val minReportingInterval = getMinReportingInterval(takServerListConfig)
                val prevReportingInterval = dataRepository.getReportingUpdateInterval.first()
                Log.d(TAG, "prevReportingInterval($prevReportingInterval); currMinReportingInterval($minReportingInterval)")
                if (prevReportingInterval != minReportingInterval) {
                    Log.d(TAG, "prevReportingInterval($prevReportingInterval) != currMinReportingInterval($minReportingInterval). Sending UPDATE_INTERVAL_CHANGED")
                    dataRepository.storeReportingUpdateInterval(minReportingInterval)
                    CotDispatchService.reportingUpdateIntervalSecs = minReportingInterval
                    EventBus.getDefault().post(MessageEvent(UPDATE_INTERVAL_CHANGED, minReportingInterval))
                }
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
                            (bleBeaconUpdate.alt != locBleBeacon.alt)) {
                            changed = true
                            break
                        }
                    }
                }
                if (true == changed) {
                    localizationBleBeaconItemList = bleBeaconMutableList.toList()
                    dataRepository.storeLocalizationBleBeaconList(localizationBleBeaconItemList)
                    Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(MessageEvent(LOCALIZATION_BEACONS_CHANGED, 0)) }, EVENT_SEND_DELAY_MS)
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
            dataRepository.storeEnableBiometricsService(enableBiometricsServiceConfigUpdate)
            dataRepository.storeEnableDittoService(enableDittoServiceConfigUpdate)
            dataRepository.storeEnableBleBeaconService(enableBleBeaconServiceConfigUpdate)
            dataRepository.storeEnableDynamicReportingStrategy(enableDynamicReportingStrategyConfigUpdate)
            dataRepository.storeEnableChatService(enableChatServiceConfigUpdate)


            if (!isStringEmptyJson(configJson)) {
                // Currently don't check for any changes but just post to dataRepository
                dataRepository.storeCustomDeveloperConfigJson(configJson)
                Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(MessageEvent(CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED, 0)) }, EVENT_SEND_DELAY_MS)
            }

            // At the end, enable / disable services and then enable/disable based on license
            if (enableLocationServiceConfigUpdate != enableLocationService) {
                enableLocationService = enableLocationServiceConfigUpdate
                Log.d(TAG, "enableLocationService=$enableLocationService")
                if (!calledOnCreate) startStopLocationService()
            }
            if (enableBiometricsServiceConfigUpdate != enableBiometricsService) {
                enableBiometricsService = enableBiometricsServiceConfigUpdate
                Log.d(TAG, "enableBiometricsService=$enableBiometricsService")
                if (!calledOnCreate) startStopBiometricService()
            }
            if (enableDittoServiceConfigUpdate != enableDittoService) {
                enableDittoService = enableDittoServiceConfigUpdate
                Log.d(TAG, "enableDittoService=$enableDittoService")
                if (!calledOnCreate) startStopDittoService()
            }
            if (enableBleBeaconServiceConfigUpdate != enableBleBeaconService) {
                enableBleBeaconService = enableBleBeaconServiceConfigUpdate
                Log.d(TAG, "enableBleBeaconService=$enableBleBeaconService")
                if (!calledOnCreate) startStopBleBeaconService()
            }
            if (enableDynamicReportingStrategy != enableDynamicReportingStrategyConfigUpdate) {
                enableDynamicReportingStrategy = enableDynamicReportingStrategyConfigUpdate
                Log.d(TAG, "enableDynamicReportingStrategy=$enableDynamicReportingStrategy")
                if (!calledOnCreate) startStopMovementDetectionService()
			}
            if (enableChatServiceConfigUpdate != enableChatService) {
                enableChatService = enableChatServiceConfigUpdate
                Log.d(TAG, "enableChatService=$enableChatService")
                // TODO: Remove lines below after adding startStopChatService()
                /*
                if (!calledOnCreate) startStopChatService()
                 */
            }
            if (!calledOnCreate) {
                if (prevLicenseIsValid != isLicenseValid()) {
                    Log.d(TAG, "isLicenseValid=${isLicenseValid()}: Going to startStopServicesLicense")
                    startStopServicesLicense(context)
                }
            }
        }
        // ui needs to be refreshed with new settings changes
        refreshUI(context)

        // Log all settings to logcat
        logSettings()
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

fun requestLocationComplicationUpdate(context: Context) {
    val componentName = ComponentName(context, LocationComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}

//TODO: Possibly move to chat service wherever it goes
fun requestChatComplicationUpdate(context: Context) {
    val componentName = ComponentName(context, ChatComplicationService::class.java)

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
    var hasUnreadMessages = dataRepository.newMessagesPresentPublicBoolean.observeAsState()
    val chatCheckedLive by enableChatServiceLiveData.observeAsState()


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
                        HeartRate()
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
//                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        SOSButton()
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
                        newMessagesPresentPublic()
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
                            if (hasUnreadMessages.value == true) {
                                Icon(
                                    painter = painterResource(R.drawable.chat_newmessage),
                                    contentDescription = "Favorite icon",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(25.dp),
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.chat_nomessage),
                                    contentDescription = "Favorite icon",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(25.dp),
                                )
                            }
                        } else {
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
        if (is24Hour) {
            DateTimeFormatter.ofPattern("HH:mm:ss")
        } else {
            DateTimeFormatter.ofPattern("hh:mm:ss") // a = AM/PM
        }
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
    CurvedLayout() {
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

fun findRoleIndex(roleName: String?) : Int {
    if (null == roleName) {
        return 0
    }
    val index = roleEntriesValuesList.lrEntries.indexOf(roleName)
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
fun HeartRate () {
    val heartRateText by HeartrateService.hrStringLiveData.observeAsState()
    val biometricsCheckedLive by enableBiometricsServiceLiveData.observeAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.heart_rate),
                contentDescription = "Contact profile picture",
                modifier = Modifier
                    // Set image size to 40 dp
                    .size(14.dp)
                    // Clip image to be shaped as a circle
            )
            if (biometricsCheckedLive == true) {
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
                    fontSize = 13.sp
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
    }
}

//2 functions below are meant to connect to complication service, TODO: Is there a better way to do this?
fun connectivityComplicationString() {
    if (null != networkManager) {
        networkManager?.checkNetworkConnected()
        var networkConnectedLiveData: MutableLiveData<Boolean> =
            NetworkManager.isNetworkConnectedLiveData
        var cellConnectedLiveData: MutableLiveData<Boolean> =
            NetworkManager.isCellularConnectedLiveData

        if (networkConnectedLiveData.value == true) {
            if (cellConnectedLiveData.value == true) {
                connectivityStatusComplication = "cell"
            } else {
                connectivityStatusComplication = "wifi"
            }
        } else {
            connectivityStatusComplication = "none"
        }
    } else {
        connectivityStatusComplication = "none"
    }
}

fun takServerComplicationString() {
    if (null != takConnectService) {
        var takServerLiveData: MutableLiveData<Boolean> =
            TAKConnectService.isAnyTakConnectedLiveData
        var takServerConnectingLiveData: MutableLiveData<Boolean> =
            TAKConnectService.isAnyTakConnectingLiveData

        if (takServerLiveData.value == true) {
            takServerStatusComplication = "connected"
        } else if (takServerConnectingLiveData.value == true) {
            takServerStatusComplication = "connecting"
        } else if (takServerLiveData.value == false) {
            takServerStatusComplication = "disconnected"
        }
    } else {
        takServerStatusComplication = "disconnected"
    }
}

fun chatComplicationString() {
    if (enableChatServiceLiveData.value == true) {
        if (dataRepository.newMessagesPresentPublicBoolean.value == true) {
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
        val locationCheckedLive by enableLocationServiceLiveData.observeAsState()
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
                .size(if (cellConnected == true) 25.dp else 25.dp),
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
            callsignSettingsState.postValue(callsign)
            teamSettingsState.postValue(team)
            roleSettingsState.postValue(role)
            enableWifiBatteryOptimizationState.postValue(enableWifiBatteryOptimization)
            enableWifiBatteryOptimizationSettingsState.postValue(enableWifiBatteryOptimization)
            enableAllWifiBatteryOptimizationState.postValue(enableAllWifiBatteryOptimization)
            enableAllWifiBatteryOptimizationSettingsState.postValue(enableAllWifiBatteryOptimization)
            wifiSsidListState.postValue(wifiSsidList)
            wifiSsidListSettingsState.postValue(wifiSsidList)
            takServerListState.postValue(takServerList)
            saveBattOnWifiStringState.postValue(saveBattOnWifiString)
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
fun SOSButton() {
    val shouldShowDialog = remember { mutableStateOf(false) }
    val successBoolean = remember {mutableStateOf(false)}
    val shouldShowConfirmationDialog = remember { mutableStateOf(false) }
    val isConfirmed = remember { mutableStateOf(false) }
    val takServer by TAKConnectService.isAnyTakConnectedLiveData.observeAsState()
    val location = dataRepository.latestLocation.value
    var locationChecked by remember{ mutableStateOf(enableLocationService)}
    var sosText = stringResource(R.string.sos)

    if (shouldShowConfirmationDialog.value) {
        ConfirmationDialog(
            shouldShowConfirmationDialog,
            rememberSwipeDismissableNavController(),
            stringResource(R.string.sos_alert_prompt),
            Icons.Default.Warning,
            Color.Red,
            "main_screen",
            isConfirmed
        )
    }
    if (isConfirmed.value) {
        shouldShowDialog.value = true
        if (takServer == true) {
            if (location != null && locationChecked) {
                successBoolean.value = true
                SOSButtonEmergencyMessage()            }
        } else {
            successBoolean.value = false
        }
        SuccessDialog(shouldShowDialog = shouldShowDialog, successBoolean = successBoolean, isConfirmed)
    }

    Button(
        onClick = {
            shouldShowConfirmationDialog.value = true
        },
        modifier = Modifier
            .border(
                width = 4.dp,
                color = Color.Red,
                shape = RoundedCornerShape(40.dp),
            )
            .size(110.dp, 60.dp),
        shape = RoundedCornerShape(40.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.Red
        )
    ) {
        Text (
            sosText,
            fontSize = if (sosText!!.length <= 10) 25.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Red
        )
    }
}

fun SOSButtonEmergencyMessage(){
    EventBus.getDefault().post(CustomEmergencyEvent(CAT_MANUAL, DESC_SOS_ALERT, 1, true))
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
                width = 2.dp,
                color = Color.White,
                shape = CircleShape
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


