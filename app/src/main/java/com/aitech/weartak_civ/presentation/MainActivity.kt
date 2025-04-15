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
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.MutableLiveData
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.RevealValue
import androidx.wear.compose.foundation.rememberRevealState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ExperimentalWearMaterialApi
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.SelectableChip
import androidx.wear.compose.material.SplitToggleChip
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.SwipeToRevealChip
import androidx.wear.compose.material.SwipeToRevealDefaults
import androidx.wear.compose.material.SwipeToRevealPrimaryAction
import androidx.wear.compose.material.SwipeToRevealSecondaryAction
import androidx.wear.compose.material.SwipeToRevealUndoAction
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
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
import com.aitech.weartak_civ.events.MessageEvent.Companion.DROP_MARKER
import com.aitech.weartak_civ.events.MessageEvent.Companion.OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_ADDED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.UPDATE_INTERVAL_CHANGED
import com.aitech.weartak_civ.presentation.MainActivity.Constants.ALL_WIFI_NETWORKS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.SOME_WIFI_NETWORKS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsignSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimization
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimizationSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimizationState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBiometricsService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDittoService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimization
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimizationSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimizationState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.generateSaveBattOnWifiString
import com.aitech.weartak_civ.presentation.MainActivity.Constants.license
import com.aitech.weartak_civ.presentation.MainActivity.Constants.licenseSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.networkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.saveBattOnWifiString
import com.aitech.weartak_civ.presentation.MainActivity.Constants.saveBattOnWifiStringState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startBiometricService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startDittoService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerListState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.team
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.versionNumber
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import kotlin.random.Random
import android.content.RestrictionsManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.collectAsState
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_ADDED_VIA_UI
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_REMOVED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_TOGGLED
import com.aitech.weartak_civ.presentation.MainActivity.Constants.EVENT_SEND_DELAY_MS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.getMinReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startStopServicesLicense
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTING_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTION_FAILED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_DISCONNECTED_STRING
import kotlin.math.min


class MainActivity : ComponentActivity() {

    companion object Constants {
        val TAG = "WearTAK-" + MainActivity::class.java.simpleName
        const val EVENT_SEND_DELAY_MS = 1000L
        lateinit var mainActivity : MainActivity
        lateinit var device_uid : String
        var callsign = "WEAROS-0"
        var enableLocationService : Boolean =  true
        var locationServiceRunning : Boolean = false
        var enableBiometricsService : Boolean = true
        var biometricServiceRunning : Boolean = false
        var enableDittoService : Boolean = false
        var dittoServiceRunning : Boolean = false

        var enableCellularService: Boolean = true
        var isConnected : Boolean = true
        lateinit var dataRepository: DataRepository
        lateinit var networkManager : NetworkManager
        private val klaxon = Klaxon()

        var team = ""
        var role = ""
        var license = "License"
        var teamValue = ""
        var roleValue = ""
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
            if (hasGps()) {
                //Log.i(TAG, "This hardware has GPS")
            } else {
                //Log.i(TAG, "This hardware does NOT have GPS")
            }
            //fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            //locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TimeUnit.SECONDS.toMillis(2)).build()

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


        fun startStopServicesLicense() {
            if (isLicenseValid()) {
                Log.d(TAG, """
                        ===   Application Services Info   ===
                        =====================================
                        """.trimIndent())
                startTAKConnectService()
                startOffBodyDetectService()
                startBiometricService()
                startBatteryService()
                startLocationService()
                startCotDispatchService()
                startDittoService()
                startPowerManagerService()
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
            }
        }

        fun getMinReportingInterval(takServerList : List<DataRepository.TakServerItem>) : Int {
            var minReportingInterval : Int = 100000001
            for (takServer in takServerList) {
                minReportingInterval = min(minReportingInterval, takServer.reportingUpdateInterval)
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

        networkManager.deInit()

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
                    ${String.format("%-25s %-30s", "enable Loc:", enableLocationService.toString())}
                    ${String.format("%-25s %-30s", "enable Bio:", enableBiometricsService.toString())}
                    ${String.format("%-25s %-30s", "enable Ditto:", enableDittoService.toString())}
                    ${String.format("%-25s %-30s", "enable wifi opt:", enableWifiBatteryOptimization.toString())}
                    ${String.format("%-25s %-30s", "enable all wifi:", enableAllWifiBatteryOptimization.toString())}
                    ${String.format("%-25s %-30s", "Wifi ssids list:", wifiSsidList.toString())}
                """.trimIndent()
        Log.v(TAG, logOutput)
    }

//    private fun startHealthCapabilitiesCollection() {
//        val healthClient = HealthServices.getClient(this)
//        val passiveMonitoringClient = healthClient.passiveMonitoringClient
//        measureClient = healthClient.measureClient
//
//        lifecycleScope.launch {
//            try {
//                // Await the Passive Monitoring capabilities
//                val capabilities = passiveMonitoringClient.getCapabilitiesAsync().await()
//                val supportedDataTypes = capabilities.supportedDataTypesPassiveMonitoring
//
//                Log.d(TAG, "Supported passive monitoring data: $supportedDataTypes")
//
//                val supportsHeartRate = DataType.HEART_RATE_BPM in supportedDataTypes
//                val supportsVo2Max = DataType.VO2_MAX in supportedDataTypes
//                val supportsLocation = DataType.LOCATION in supportedDataTypes
//
//                Log.d(TAG, "supportsHeartRate: $supportsHeartRate; supportsVo2Max: $supportsVo2Max; supportsLocation: $supportsLocation")
//
//                // Await the Measure capabilities
//                val measureCapabilities = measureClient?.getCapabilitiesAsync()?.await()
//                val measureSupportsHeartRate = DataType.HEART_RATE_BPM in (measureCapabilities?.supportedDataTypesMeasure ?: emptySet())
//
//                Log.d(TAG, "measureCapabilities: $measureCapabilities")
//                Log.d(TAG, "measureSupportsHeartRate: $measureSupportsHeartRate")
//
//                // If needed, register callbacks
//                // measureClient?.registerMeasureCallback(DataType.HEART_RATE_BPM, heartRateCallback)
//            } catch (e: Exception) {
//                Log.e(TAG, "Error fetching health capabilities: ${e.message}")
//            }
//        }
//    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE)

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
        device_uid = "WEAROS_" + Secure.getString(this.getContentResolver(), Secure.ANDROID_ID)

        dataRepository = DataRepository(this)
        initSettings()
        logSettings()
        refreshUI()

        restrictionsManager = getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        // Register BroadcastReceiver to listen for changes
        restrictionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
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

//        Log.d(TAG, " === === Teams And Roles === ===")
//        setTeamsJson()
//        setRolesJson()
//        setDataFromDatastore()
        networkManager = NetworkManager.getInstance()
        networkManager.init()

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
        startStopServicesLicense()

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
                WearTAKCivApp(callsignState, licenseState)
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

    private fun refreshUI() {
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

    private fun handleManagedConfigUpdate(calledOnCreate: Boolean) {
        Log.v(TAG, " === === Handling Managed Config Update ===")

        val appRestrictions = restrictionsManager.applicationRestrictions
        if (appRestrictions == null) {
            Log.w(TAG, "No managed configuration received. Returning...")
            return
        }

        /* for (key in appRestrictions.keySet()) {
            val value = appRestrictions.get(key)
            // Retrieve the runtime type name, if available
            val typeName = value?.javaClass?.simpleName ?: "null"
            //Log.v(TAG, "Key: $key, Value: $value, Type: $typeName")
        } */

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
        val wifiOptimizationSsidString = appRestrictions.getString("wifiOptimization_ssid_list", "")
        val wifiOptimizationSsidList: List<DataRepository.WifiSsidItem> = wifiOptimizationSsidString.split("||") // Split by two pipes, unlikely a random generated SSID has
            .map { it.trim() } // Trim whitespace from each SSID
            .filter { it.isNotEmpty() } // Remove any empty values
            .map { DataRepository.WifiSsidItem(it) } // Map each SSID to a WifiSsidItem

        val enableLocationServiceConfigUpdate = appRestrictions.getBoolean("location_service_enabled", enableLocationService)
        val enableBiometricsServiceConfigUpdate = appRestrictions.getBoolean("biometric_service_enabled", enableBiometricsService)
        val enableDittoServiceConfigUpdate = appRestrictions.getBoolean("ditto_service_enabled", enableDittoService)
        val configJson = appRestrictions.getString("custom_developer_config_json", "{}")

        // store settings persistently & send out events as necessary
        validateLicense(license) // validate the license

        runBlocking {
            var changed = false
            if (enableWifiBatteryOptimization != enableWifiBatteryOptimizationConfigUpdate) {
                enableWifiBatteryOptimization = enableWifiBatteryOptimizationConfigUpdate
                dataRepository.storeEnableWifiBatteryOptimization(enableWifiBatteryOptimization)
                changed = true
            }
            if (enableAllWifiBatteryOptimization != enableAllWifiBatteryOptimizationConfigUpdate) {
                enableAllWifiBatteryOptimization = enableAllWifiBatteryOptimizationConfigUpdate
                dataRepository.storeEnableAllWifiBatteryOptimization(
                    enableAllWifiBatteryOptimization
                )
                changed = true
            }
            if (wifiSsidList != wifiOptimizationSsidList) {
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
            if ((null != teamsNames) || (null != teamsColors)) {
                Log.v(TAG, "teamsNames: $teamsNames")
                Log.v(TAG, "teamsColors: $teamsColors")
                if (null != teamsNames) { dataRepository.storeTeamNames(teamsNames) }
                if (null != teamsColors) { dataRepository.storeTeamColors(teamsColors) }
                initTeamEntriesValuesList(teamsNames.split(","), teamsColors.split(","))
                initTeamAndTeamValue()
            }
            // Send out EventBus message even if there is no change
            EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))

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

            dataRepository.storeLicense(license)
            dataRepository.storeEnableLocationService(enableLocationServiceConfigUpdate)
            dataRepository.storeEnableBiometricsService(enableBiometricsServiceConfigUpdate)
            dataRepository.storeEnableDittoService(enableDittoServiceConfigUpdate)

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
            if (!calledOnCreate) {
                if (prevLicenseIsValid != isLicenseValid()) {
                    Log.d(TAG, "isLicenseValid=${isLicenseValid()}: Going to startStopServicesLicense")
                    startStopServicesLicense()
                }
            }
        }
        // ui needs to be refreshed with new settings changes
        refreshUI()

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


//Contains functionality for all watch screens
@Composable
fun WearTAKCivApp(callsignLiveDataState: MutableLiveData<String>, licenseLiveDataState: MutableLiveData<String>) {
    val navController = rememberSwipeDismissableNavController()
    val context = LocalContext.current

    // collect license from data repo so we can see if it changes.
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")

    // react to changes in the license.
    LaunchedEffect(licenseKey) {
        validateLicense(licenseKey)
        if (isLicenseValid()) {
//            Log.v(TAG, "LaunchedEffect License is valid: $licenseKey | $license")
            navController.navigate("main_screen") {
                popUpTo("invalid_license_screen") {
                    inclusive = true
                }
            }
        } else {
//            Log.v(TAG, "LaunchedEffect License is invalid: $licenseKey | $license")
            navController.navigate("invalid_license_screen") {
                popUpTo("license_setting_screen") {
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
        composable(
            "settings_screen") {
            SettingsScreen(navController, callsignLiveDataState, licenseLiveDataState)
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
            "license_setting_screen") {
            LicenseKeyScreen(navController, licenseSettingsState)
        }
        composable(
            "invalid_license_screen") {
            InvalidLicenseScreen(navController)
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
                            .padding(top = 35.dp)
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        HeartRate()
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
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
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Callsign(navController, callsignState)
                    }

                }
                TroubleshootingIcons()
                TimeText()
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
            var callsignTextFieldValue by remember {
                mutableStateOf(
                    TextFieldValue(
                        callsignLiveDataState.value ?: "null"
                    )
                )
            }
//        Log.d(TAG, "callsignTextFieldValue=" + callsignTextFieldValue.text)

            OutlinedTextField(
                value = callsignTextFieldValue,
                onValueChange = {
                    callsignTextFieldValue = it
                },
                maxLines = 1,
                label = { Text(text = stringResource(R.string.callsign), color = colors.primary) },
                placeholder = { Text(text = stringResource(R.string.callsign), color = colors.primary) },
                textStyle = TextStyle(color = colors.primary)
            )
            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                ConfirmCancelButtons(
                    onClickCancel = {
                        Log.d(TAG, "Settings NOT saved")
                        navController.navigate("settings_screen") {
                            popUpTo(("settings_screen")) {
                                inclusive = true
                            }
                        }
                    },
                    onClickConfirm = {
                        callsignLiveDataState.postValue(callsignTextFieldValue.text)
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

@Composable
fun TeamSetting(navController: NavController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = 20.dp, bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        val index : Int = findTeamIndex(teamSettingsState.value)
        var selectedRadioIndex by remember { mutableStateOf(index) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp)
                .padding(bottom = 40.dp)
                .verticalScroll(state = rememberScrollState()),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
                        shape = RoundedCornerShape(0.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(15.dp))
        }
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
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
        }
    }
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

@Composable
fun RoleSetting (navController: NavController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = 20.dp, bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        val index = findRoleIndex(roleSettingsState.value)
        var selectedRadioIndex by remember { mutableStateOf(index) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp)
                .padding(bottom = 40.dp)
                .verticalScroll(state = rememberScrollState()),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            roleEntriesValuesList.lrEntries.forEachIndexed { index, lrItem ->
                run {
                    SelectableChip(
                        modifier = Modifier.fillMaxWidth(),
                        selected = index == selectedRadioIndex,
                        onClick = { selectedRadioIndex = index},
                        label = {
                            // The primary label should have a maximum 3 lines of text
                            Text(lrItem, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        },
                        enabled = true,
                        shape = RoundedCornerShape(0.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(15.dp))
        }
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }                },
                onClickConfirm = {
                    roleSettingsState.postValue(roleEntriesValuesList.lrEntries[selectedRadioIndex])
                    Log.d(TAG, "Settings saved with role: ${roleSettingsState.value}")
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }                })
        }
    }
}

@SuppressLint("CoroutineCreationDuringComposition", "UnrememberedMutableState")
@Composable
fun TakServerSetting(navController: NavController) {
    val takServerSettingList by takServerListState.observeAsState(initial = emptyList())
    //val selectedIndexState = MutableLiveData<Int?>(null)
    //val selectedIndex by selectedIndexState.observeAsState(initial = null)
    Log.d(TAG, "TakServerSetting called")
    val text = stringResource(R.string.tak_server_connections)

    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .align(Alignment.TopCenter)
                .padding(bottom = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LazyColumn {
                    item {
                        Text(
                            text = text,
                            fontSize = if (text.length!! < 20) 15.sp else 13.sp,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 20.dp).align(Alignment.CenterHorizontally)
                        )
                    }
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
                                    Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(MessageEvent(SERVER_TOGGLED, index)) }, EVENT_SEND_DELAY_MS)
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
                                    Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(MessageEvent(SERVER_REMOVED, index)) }, EVENT_SEND_DELAY_MS)
                                }
                            },
                            onSecondaryActionClick = {
                                //selectedIndexState.postValue(index)
                                navController.navigate("edit_server_screen/$index")
                            }
                        )
                    }

                    item {
                        Chip(
                            onClick = { navController.navigate("new_server_screen") {
                                popUpTo(("new_server_screen")) {
                                    inclusive = true
                                }
                            } },
                            enabled = true,
                            label = { Text(text = stringResource(R.string.add_network)) },
                            colors = ChipDefaults.chipColors(
                                backgroundColor = Color.DarkGray,
                                contentColor = Color.White,
                                secondaryContentColor = Color.LightGray
                            ),
                            icon = {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Add TAK Server"
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                Button(
                    onClick = {
                        navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    } },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                        contentColor = Color.Red
                    ),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "Go back to Main Settings Screen",
                    )
                }
            }
        }
    }
    Log.d(TAG, "Tak Server List: $takServerSettingList")
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
        modifier =
        Modifier
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
        var reportingUpdateInterval by remember { mutableStateOf("10") }
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

                OutlinedTextField(
                    modifier = Modifier.padding(vertical = 5.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (reportingUpdateInterval.isEmpty()) {
                                Toast.makeText(mainActivity,
                                    context.getString(R.string.reporting_interval_empty_enter_again), Toast.LENGTH_SHORT).show()
                            } else if (reportingUpdateInterval == "0") {
                                Toast.makeText(mainActivity,
                                    context.getString(R.string.reporting_interval_0_enter_again), Toast.LENGTH_SHORT).show()
                            } else {
                                // If there is valid input, we can clear focus
                                // (which typically dismisses the keyboard)
                                focusManager.clearFocus()

                                // Now you can navigate or do the next action
                            }
                        }
                    ),
                    singleLine = true,
                    value = reportingUpdateInterval,
                    onValueChange = {
                        reportingUpdateInterval = it
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.reporting_interval),
                            color = colors.primary,
                            style = MaterialTheme.typography.caption3
                        )
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.interval_in_secs),
                            color = colors.primary,
                            style = MaterialTheme.typography.caption3
                        )
                    },
                    textStyle = TextStyle(color = colors.primary),
                )

                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {

                val maxReportRate: Int = 100000000
                val minReportRate: Int = 1

                ConfirmCancelButtons(
                    onClickCancel = {
                        Log.d(TAG, "Settings NOT saved")
                        navController.navigate("tak_server_screen") {
                            popUpTo(("tak_server_screen")) {
                                inclusive = true
                            }
                        }
                    },
                    onClickConfirm = {
                        scope.launch {
                            if (name.isNotBlank() && address.isNotBlank() && port.isNotBlank() && username.isNotBlank() &&
                                password.isNotBlank() && reportingUpdateInterval.isNotBlank()) {
                                if (reportingUpdateInterval.equals("")) {
                                    Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_empty_enter_again), Toast.LENGTH_SHORT).show()
                                } else if (reportingUpdateInterval.equals("0")) {
                                    Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_0_enter_again), Toast.LENGTH_SHORT).show()
                                } else if (reportingUpdateInterval.toInt() < minReportRate /* || reportingUpdateInterval.text.toInt() > maxReportRate */) {
                                    Toast.makeText(mainActivity, context.getString(R.string.reporting_rate_outside_bounds) + " " + "$minReportRate" + "-" + "$maxReportRate", Toast.LENGTH_SHORT).show()
                                } else {
                                    val newReportingUpdateInterval = reportingUpdateInterval.toInt()
                                    val takServerItem = DataRepository.TakServerItem(true, name, address, port.toInt(),
                                        isP12Cert, username, password, "", "", newReportingUpdateInterval)
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
                                        { EventBus.getDefault().post(MessageEvent(SERVER_ADDED_VIA_UI, takServerList.size-1)) }, EVENT_SEND_DELAY_MS)

                                    Log.d(TAG, "reportingUpdateInterval.toInt()=${newReportingUpdateInterval}; COT:${CotDispatchService.reportingUpdateIntervalSecs}")
                                    val minReportingInterval = getMinReportingInterval(takServerList)
                                    var prevReportingInterval = 1
                                    runBlocking {
                                        prevReportingInterval = dataRepository.getReportingUpdateInterval.first()
                                    }
                                    Log.d(TAG, "prevReportingInterval($prevReportingInterval); currMinReportingInterval($minReportingInterval)")
                                    if (prevReportingInterval != minReportingInterval) {
                                        Log.d(TAG, "prevReportingInterval($prevReportingInterval) != currMinReportingInterval($minReportingInterval). Sending UPDATE_INTERVAL_CHANGED")
                                        runBlocking {
                                            dataRepository.storeReportingUpdateInterval(minReportingInterval)
                                        }
                                        CotDispatchService.reportingUpdateIntervalSecs = minReportingInterval
                                        EventBus.getDefault().post(MessageEvent(UPDATE_INTERVAL_CHANGED, minReportingInterval))
                                    }
                                    /*
                                    if (newReportingUpdateInterval < CotDispatchService.reportingUpdateIntervalSecs) {
                                        Log.d(TAG, "Setting reportingUpdateIntervalSecs (curr= ${CotDispatchService.reportingUpdateIntervalSecs}) to ${newReportingUpdateInterval}")
                                        runBlocking {
                                            dataRepository.storeReportingUpdateInterval(newReportingUpdateInterval)
                                        }
                                        CotDispatchService.reportingUpdateIntervalSecs = newReportingUpdateInterval
                                        //LocationService.locationUpdateFastestIntervalSecs = CotDispatchService.reportingUpdateIntervalSecs
                                        EventBus.getDefault().post(MessageEvent(UPDATE_INTERVAL_CHANGED, newReportingUpdateInterval))
                                    }

                                     */
                                }
                            } else {
                                Toast.makeText(mainActivity,
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
                        LoadingDialog(shouldShowLoadingDialog)
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
        var reportingUpdateInterval by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.reportingUpdateInterval.toString().orEmpty()) }
        var isP12Cert by remember { mutableStateOf(takServerListLocal.get(index)?.isP12Cert ?: false) }
        val context = LocalContext.current
        val serverSettingsChanged = remember { mutableStateOf(false) }
        Log.d(TAG, "address=$address, serverSettingsChanged=${serverSettingsChanged.value}")

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

                OutlinedTextField(
                    modifier = Modifier.padding(vertical = 5.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (reportingUpdateInterval.isEmpty()) {
                                Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_empty_enter_again), Toast.LENGTH_SHORT).show()
                            } else if (reportingUpdateInterval == "0") {
                                Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_0_enter_again), Toast.LENGTH_SHORT).show()
                            } else {
                                // If there is valid input, we can clear focus
                                // (which typically dismisses the keyboard)
                                focusManager.clearFocus()

                                // Now you can navigate or do the next action
                            }
                        }
                    ),
                    singleLine = true,
                    value = reportingUpdateInterval,
                    onValueChange = {
                        reportingUpdateInterval = it
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.reporting_interval),
                            color = colors.primary,
                            style = MaterialTheme.typography.caption3
                        )
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.interval_in_secs),
                            color = colors.primary,
                            style = MaterialTheme.typography.caption3
                        )
                    },
                    textStyle = TextStyle(color = colors.primary),
                )
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
                        Log.d(TAG, "Settings NOT saved")
                        navController.navigate("tak_server_screen") {
                            popUpTo(("tak_server_screen")) {
                                inclusive = true
                            }
                        }
                    },
                    onClickConfirm = {
                            if (name.isNotBlank() && address.isNotBlank() && port.isNotBlank() && reportingUpdateInterval.isNotBlank() &&
                                (if (isP12Cert) { true } else { username.isNotBlank() } ) &&
                                (if (isP12Cert) { true } else { password.isNotBlank() } ) ) {
                                if (reportingUpdateInterval.equals("")) {
                                    Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_empty_enter_again), Toast.LENGTH_SHORT).show()
                                } else if (reportingUpdateInterval.equals("0")) {
                                    Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_0_enter_again), Toast.LENGTH_SHORT).show()
                                } else if (reportingUpdateInterval.toInt() < minReportRate /* || reportingUpdateInterval.text.toInt() > maxReportRate */) {
                                    Toast.makeText(mainActivity,
                                        context.getString(R.string.reporting_rate_outside_bounds) + " " + "$minReportRate" + "-" + "$maxReportRate", Toast.LENGTH_SHORT).show()
                                } else {
                                    val takServerDetails = takServerList.get(index)
                                    Log.d(TAG, "EditServerPage: onClickConfirm all settings good")
                                    Log.d(TAG, "takServerDetails: $takServerDetails")
                                    Log.d(TAG, "name: $name, address: $address, port=$port, username=$username, password=$password, reportingUpdateInterval=${reportingUpdateInterval.toInt()}")
                                    if (name != takServerDetails.name || address != takServerDetails.address || port != takServerDetails.port.toString() ||
                                        username != takServerDetails.username || password != takServerDetails.password ||
                                        takServerDetails.reportingUpdateInterval != reportingUpdateInterval.toInt()) {
                                        Log.d(TAG, "EditServerPage onClickConfirm: something has changed")
                                        if (name == takServerDetails.name) {
                                            if (takServerDetails.reportingUpdateInterval == reportingUpdateInterval.toInt()) {
                                                // Name and reporting interval are same, so something else in address, port, username or password has changed
                                                serverSettingsChanged.value = true
                                                Log.d(TAG, "serverSettingsChanged set to true")
                                            }
                                        } else { // Name is different
                                            if (address != takServerDetails.address || port.toInt() != takServerDetails.port ||
                                                username != takServerDetails.username || password != takServerDetails.password) {
                                                // Apart from name, omething else is different
                                                serverSettingsChanged.value = true
                                                Log.d(TAG, "serverSettingsChanged set to true with name being different")
                                            }
                                        }
                                        val changedTakServerDetail: DataRepository.TakServerItem =
                                            if (isP12Cert == true) {
                                                DataRepository.TakServerItem(takServerListLocal.get(index).isEnabled, name, address, port.toInt(), isP12Cert,
                                                    takServerListLocal.get(index).username, takServerListLocal.get(index).password, takServerListLocal.get(index).p12Cert, takServerListLocal.get(index).p12CertPassword,
                                                    reportingUpdateInterval.toInt())
                                            } else {
                                                DataRepository.TakServerItem(takServerListLocal.get(index).isEnabled, name, address, port.toInt(), isP12Cert,
                                                    username, password, "", "", reportingUpdateInterval.toInt())
                                            }

                                        runBlocking {
                                            dataRepository.updateTakServerItem(index, changedTakServerDetail)
                                        }

                                        Log.d(TAG, "onClickConfirm: dataRepo updated")
                                        val mutableTakServerList = takServerListLocal.toMutableList()
                                        mutableTakServerList.set(index, changedTakServerDetail)
                                        takServerList = mutableTakServerList.toList()
                                        takServerListState.postValue(takServerList)

                                        val changedReportingUpdateInterval = reportingUpdateInterval.toInt()
                                        Log.d(TAG, "reportingUpdateInterval.toInt()=${changedReportingUpdateInterval}; COT:${CotDispatchService.reportingUpdateIntervalSecs}")
                                        val minReportingInterval = getMinReportingInterval(takServerList)
                                        var prevReportingInterval = 1
                                        runBlocking {
                                            prevReportingInterval = dataRepository.getReportingUpdateInterval.first()
                                        }
                                        Log.d(TAG, "prevReportingInterval($prevReportingInterval); currMinReportingInterval($minReportingInterval)")
                                        if (prevReportingInterval != minReportingInterval) {
                                            Log.d(TAG, "prevReportingInterval($prevReportingInterval) != currMinReportingInterval($minReportingInterval). Sending UPDATE_INTERVAL_CHANGED")
                                            runBlocking {
                                                dataRepository.storeReportingUpdateInterval(minReportingInterval)
                                            }
                                            CotDispatchService.reportingUpdateIntervalSecs = minReportingInterval
                                            EventBus.getDefault().post(MessageEvent(UPDATE_INTERVAL_CHANGED, minReportingInterval))
                                        }
                                        /*
                                        if (changedReportingUpdateInterval < CotDispatchService.reportingUpdateIntervalSecs) {
                                            Log.d(TAG, "Setting reportingUpdateIntervalSecs (curr= ${CotDispatchService.reportingUpdateIntervalSecs}) to ${changedReportingUpdateInterval}")
                                            runBlocking {
                                                dataRepository.storeReportingUpdateInterval(changedReportingUpdateInterval)
                                            }
                                            CotDispatchService.reportingUpdateIntervalSecs = changedReportingUpdateInterval
                                            //LocationService.locationUpdateFastestIntervalSecs = CotDispatchService.reportingUpdateIntervalSecs
                                            EventBus.getDefault().post(MessageEvent(UPDATE_INTERVAL_CHANGED, changedReportingUpdateInterval))
                                        }

                                         */

                                        if (serverSettingsChanged.value) {
                                            Log.d(TAG, "EditServerPage serverSettingsChanged=${serverSettingsChanged.value}")
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
                                        serverSettingsChanged.value = false
                                        navController.navigate("tak_server_screen") {
                                            popUpTo(("tak_server_screen")) {
                                                inclusive = true
                                            }
                                        }
                                    }
                                }
                            } else {
                                Toast.makeText(mainActivity, context.getString(R.string.please_enter_valid_values_for_all_fields), Toast.LENGTH_LONG).show()
                            }
                    }
                )
                Log.d(TAG, "EditServerPage: loadingDialog serverSettingsChanged=${serverSettingsChanged.value}")
                if (serverSettingsChanged.value) {
                    val loadingDialog by TAKConnectService.takServerDetails.get(index).connectionStatusLiveData.observeAsState()
                    val shouldShowLoadingDialog = remember { mutableStateOf(false) }
                    val shouldShowConfirmDialog = remember { mutableStateOf(false) }
                    val isServerConnected = remember { mutableStateOf(false) }
                    if (NetworkManager.isNetworkConnected) {
                        Log.d(TAG, "EditServerPage: serverSettingsChanged w/ Network connected")
                        val isNetworkConnected = remember { mutableStateOf(true)}

                        displayServerDialogs(isNetworkConnected.value, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected)
                        LoadingDialog(shouldShowLoadingDialog)
                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog, shouldShowLoadingDialog, navController, isServerConnected, serverSettingsChanged)
                    } else {
                        Log.d(TAG, "EditServerPage: serverSettingsChanged w/ Network disconnected")
                        val isNetworkConnected = remember { mutableStateOf(false)}

                        displayServerDialogs(isNetworkConnected.value, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected)
                        //LoadingDialog(shouldShowLoadingDialog)
                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog, shouldShowLoadingDialog, navController, isServerConnected, serverSettingsChanged)
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
                    .padding(bottom = 20.dp, top = 15.dp)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.save_battery_on_wifi),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(3.dp)
                        .padding(horizontal = 10.dp)
                )

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
                        .align(Alignment.Start)
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
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
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
                        val currWifiOpt = enableWifiBatteryOptimizationSettingsState.value
                        val currAllOpt = enableAllWifiBatteryOptimizationSettingsState.value

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
            }
        }
    }
}

@Composable
fun WifiSsidSelectScreen(navController: NavController) {
    //Log.d(TAG, "WifiSsidSelectScreen called")
    val wifiSsidList by wifiSsidListSettingsState.observeAsState(initial = emptyList())
    val text = stringResource(R.string.enabled_wifi_connections)

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
                    .padding(bottom = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LazyColumn {
                    item {
                        Text(
                            text = text,
                            fontSize = if(text.length!!<20) 15.sp else 13.sp,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal =20.dp, vertical = 10.dp)
                        )
                    }
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
                        Chip(
                            onClick = { navController.navigate("wifi_ssid_setting_screen") {
                                popUpTo(("wifi_ssid_setting_screen")) {
                                    inclusive = true
                                }
                            } },
                            enabled = true,
                            label = { Text(text = stringResource(R.string.add_network)) },
                            colors = ChipDefaults.chipColors(
                                backgroundColor = Color.DarkGray,
                                contentColor = Color.White,
                                secondaryContentColor = Color.LightGray
                            ),
                            icon = {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Localized description"
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        )
                    }

                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                Button(
                    onClick = { navController.navigate("optimize_battery_on_wifi_screen") {
                        popUpTo(("optimize_battery_on_wifi_screen")) {
                            inclusive = true
                        }
                    } },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                        contentColor = Color.Red
                    ),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "Localized description",
                    )
                }
            }

        }

    }
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
            maxLines = 1,
            label = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            placeholder = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            textStyle = TextStyle(color = colors.primary)
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
                    navController.navigate("wifi_ssid_screen") {
                        popUpTo(("wifi_ssid_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
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
            maxLines = 1,
            label = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            placeholder = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            textStyle = TextStyle(color = colors.primary)
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
                    navController.navigate("wifi_ssid_screen") {
                        popUpTo(("wifi_ssid_screen")) {
                            inclusive = true
                        }
                    }
                },
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
                        text = stringResource(R.string.registering_device_with_tak_server),
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

@Composable
fun LicenseKeyScreen(navController: NavController, licenseLiveDataState: MutableLiveData<String>) {
    var offsetX by remember { mutableStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {

            var licenseTextFieldValue by remember {
            mutableStateOf(
                TextFieldValue(
                    licenseLiveDataState.value ?: "null"
                )
            )
        }

        OutlinedTextField(
            value = licenseTextFieldValue,
            onValueChange = {
                licenseTextFieldValue = it
            },
            maxLines = 1,
            label = { Text(text = stringResource(R.string.license_key), color = colors.primary) },
            textStyle = TextStyle(color = colors.primary)
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            var settingsChanged: Boolean = false
            var licenseChanged: Boolean = false
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
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
                    val enteredLicense = licenseTextFieldValue.text
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
                        license = licenseTextFieldValue.text ?: license
                        Log.d(TAG, "License Changed. Updating DataRepository.")
                        CoroutineScope(Dispatchers.Main).launch {
                            licenseSettingsState.postValue(license)
                            licenseLiveDataState.postValue(license)
                            dataRepository.storeLicense(license)
                        }
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
                    startStopServicesLicense()
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
                    popUpTo("invalid_license_screen") { inclusive = true }
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
fun HeartRate () {
    val heartRateText by HeartrateService.hrStringLiveData.observeAsState()
    var biometricsChecked by remember{ mutableStateOf(enableBiometricsService)}

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
            if (biometricsChecked) {
                Text(
                    text = " $heartRateText",
                    textAlign = TextAlign.Start,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = stringResource(R.string.bpm),
                    color = Color.White
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

@Composable
fun TroubleshootingIcons() {
    val iconSizeSmall= 20.dp
    networkManager.checkNetworkConnected()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp),
            contentAlignment = Alignment.TopCenter
    ) {
        var locationChecked by remember{ mutableStateOf(enableLocationService)}
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
                    y = (15).dp // Adjust this value
                )
                .size(if (cellConnected == true) 15.dp else 25.dp),
            tint = if (isConnected == true) Color.White else Color.Unspecified
        )

        // Icon on the top right curve
        Icon(
            if (locationChecked) painterResource(R.drawable.location_on) else painterResource(R.drawable.location_off),
            contentDescription = "Top Right Icon",
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(
                    x = (-40).dp, // Adjust this value
                    y = (-10).dp // Adjust this value
                )
                .size(iconSizeSmall),
            tint = if (locationChecked) Color.White else Color.DarkGray
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
                    y = (15).dp // Adjust this value
                )
                .size(iconSizeSmall)

        )
        Log.d("Iscellconnected", cellConnected.toString())
    }
}

@Composable
fun SuccessDialog(shouldShowDialog: MutableState<Boolean>, successBoolean : MutableState<Boolean>, isConfirmed : MutableState<Boolean>) {
    if (shouldShowDialog.value) {
        Dialog(onDismissRequest = { shouldShowDialog.value = false }) {
            LaunchedEffect(Unit) {
                delay(2000) // Delay for 5 seconds
                shouldShowDialog.value = false // Dismiss the dialog
                isConfirmed.value = false
            }

            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .height(200.dp),
                colors = CardDefaults.cardColors(containerColor = colors.background)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (successBoolean.value) {
                        Icon(
                            Icons.Rounded.Check,
                            modifier = Modifier.size(25.dp),
                            contentDescription = null,
                            tint = Color.Green
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.success),
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Clear,
                            modifier = Modifier.size(25.dp),
                            contentDescription = null,
                            tint = Color.Red
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.error),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Callsign(navController: NavController, callsignState: MutableLiveData<String>) {
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
            navController.navigate("settings_screen") },
        label = { Text(
            "${greetingName}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = if (greetingName!!.length <= maxChar) 17.sp else 13.sp,
            modifier = Modifier.padding(10.dp),

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
            .size(110.dp),
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
    val shouldShowDialog = remember { mutableStateOf(false) }
    val successBoolean = remember {mutableStateOf(false)}
    val shouldShowConfirmationDialog = remember { mutableStateOf(false) }
    val isConfirmed = remember { mutableStateOf(false) }
    val takServer by TAKConnectService.isAnyTakConnectedLiveData.observeAsState()
    val location = dataRepository.latestLocation.value
    var locationChecked by remember{ mutableStateOf(enableLocationService)}


    // State for button color
    var isPressed by remember { mutableStateOf(false) }
    val buttonColor by animateColorAsState(
        targetValue = if (isPressed) Color.Green else Color.White,
        label = "ButtonColorAnimation"
    )

    val coroutineScope = rememberCoroutineScope()

    if (shouldShowConfirmationDialog.value) {
        ConfirmationDialog(
            shouldShowConfirmationDialog,
            rememberSwipeDismissableNavController(),
            stringResource(R.string.marker_drop_prompt),
            Icons.Default.LocationOn,
            Color.Green,
            "main_screen",
            isConfirmed
        )
    }
    if (isConfirmed.value) {
        shouldShowDialog.value = true
        if (takServer == true) {
            if (location != null && locationChecked) {
                successBoolean.value = true
                EventBus.getDefault().post(MessageEvent(DROP_MARKER, 0))
            }
        } else {
            successBoolean.value = false
        }
        SuccessDialog(shouldShowDialog = shouldShowDialog, successBoolean = successBoolean, isConfirmed)
    }

    IconButton(
        onClick = {
		    shouldShowConfirmationDialog.value = true
            vibrateWatch(context)

            isPressed = true
            coroutineScope.launch {
                delay(500)  // temporary color change
                isPressed = false
            }
            Log.d(TAG, "DropMarkerButton Clicked")
        },
        modifier = Modifier
            .border(
                width = 2.dp,
                color = Color.White,
                shape = CircleShape
            )
            .size(100.dp),
    ) {
        Icon(
            painterResource(R.drawable.markerdrop),
            contentDescription = "Localized description",
            modifier = Modifier.size(30.dp)
        )
    }
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

//Contains functionality for settings screen
@Composable
fun SettingsScreen(
    navController: NavController,
    callsignLiveDataState: MutableLiveData<String>,
    licenseLiveDataState: MutableLiveData<String>
) {
    //Log.d(TAG, "SettingsScreen called")
    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            var locationChecked by remember{ mutableStateOf(enableLocationService)}
            var biometricsChecked by remember{ mutableStateOf(enableBiometricsService)}
            var dittoChecked by remember{ mutableStateOf(enableDittoService)}
            val displayedSecondaryText by saveBattOnWifiStringState.observeAsState()
            val text = stringResource(R.string.callsign_settings)

//            when (displayedSecondaryText) {
//                ALL_WIFI_NETWORKS -> { ALL_WIFI_NETWORKS = stringResource(R.string.all_wifi_connections) }
//                SOME_WIFI_NETWORKS -> { SOME_WIFI_NETWORKS = stringResource(R.string.some_wifi_connections) }
//                else -> { NO_WIFI_NETWORKS = stringResource(R.string.no_wifi_connections) }
//            }

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
                    text = text,
                    fontSize = if(text.length!!<20) 15.sp else 13.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

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

                SettingSwitchToggle(
                    checked = locationChecked, onCheckedChange = { locationChecked = it },
                    title = stringResource(R.string.location_service), description = stringResource(R.string.service_on_off)
                )

                SettingSwitchToggle(
                    checked = biometricsChecked, onCheckedChange = { biometricsChecked = it },
                    title = stringResource(R.string.biometrics),
                    description = stringResource(R.string.service_on_off),
                )

                SettingSwitchToggle(
                    checked = dittoChecked, onCheckedChange = { dittoChecked = it },
                    title = stringResource(R.string.ditto),
                    description = stringResource(R.string.service_on_off),
                )

                Text(
                    modifier = Modifier.padding(top = 7.dp),
                    text = stringResource(R.string.version, versionNumber),
                    fontSize = 11.sp,
                    color = colors.primary,
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                ConfirmCancelButtons(
                    onClickCancel = {
                        Log.d(TAG, "SettingsScreen Settings NOT saved")
                        navController.navigate("main_screen") {
                            popUpTo(("main_screen")) {
                                inclusive = true
                            }
                        }
                    },
                    onClickConfirm = {
                        var settingsChanged: Boolean = false
                        var callsignChanged: Boolean = false
                        var teamChanged = false
                        var roleChanged = false
                        var licenseChanged: Boolean = false
                        val maxReportRate: Int = 100000000
                        val minReportRate: Int = 1
                        var locationCheckedChanged: Boolean = false
                        var biometricsCheckedChanged: Boolean = false
                        var dittoCheckedChanged: Boolean = false
                        var saveBattOnWifiChanged = false
                        var takServerListChanged = false

                        if (!callsign.equals(callsignSettingsState.value)) {
                            settingsChanged = true; callsignChanged = true
                            callsign = callsignSettingsState.value ?: callsign
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
                            var roleIndex = roleEntriesValuesList.lrEntries.indexOf(role)
                            // This cannot happen, but still...
                            if (roleIndex == -1) {
                                Log.e(TAG, "ERROR SettingsScreen: roleIndex is -1. Setting role entry and value to that of index 0")
                                roleIndex = 0
                                role = roleEntriesValuesList.lrEntries[0]
                                roleSettingsState.postValue(role)
                            }
                            roleValue = roleEntriesValuesList.lrValues[roleIndex]
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


                        if (enableLocationService != locationChecked) {
                            settingsChanged = true; locationCheckedChanged = true
                            enableLocationService = locationChecked
                            if (enableLocationService) {
                                Log.d(TAG, "Trying to start location service")
                                startLocationService()
                            } else {
                                Log.d(TAG, "Trying to stop location service")
                                mainActivity.stopService(Intent(mainActivity, LocationService::class.java))
                            }
                        }

                        if (enableBiometricsService != biometricsChecked) {
                            settingsChanged = true; biometricsCheckedChanged = true
                            enableBiometricsService = biometricsChecked
                            if (enableBiometricsService) {
                                Log.d(TAG, "Trying to start biometric service")
                                startBiometricService()
                            } else {
                                Log.d(TAG, "Trying to stop biometric service")
                                mainActivity.stopService(Intent(mainActivity, HeartrateService::class.java))
                            }
                        }

                        if (enableDittoService != dittoChecked) {
                            settingsChanged = true; dittoCheckedChanged = true
                            enableDittoService = dittoChecked
                            if (enableDittoService) {
                                Log.d(TAG, "Trying to start ditto service")
                                startDittoService()
                            } else {
                                Log.d(TAG, "Trying to stop ditto service")
                                mainActivity.stopService(Intent(mainActivity, DittoService::class.java))
                            }
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
                                }
//                                    if(licenseChanged) {
//                                        dataRepository.storeLicense(license)
//
//                                    }
                                if (saveBattOnWifiChanged) {
                                    Log.d(TAG, "Storing batt optimization on wifi: enable($enableWifiBatteryOptimization), allWifi($enableAllWifiBatteryOptimization), wifiSsidList=($wifiSsidList)")
                                    runBlocking {
                                        dataRepository.storeEnableWifiBatteryOptimization(enableWifiBatteryOptimization)
                                        dataRepository.storeEnableAllWifiBatteryOptimization(enableAllWifiBatteryOptimization)
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
                                if (biometricsCheckedChanged) {
                                    Log.d(TAG, "Storing enableBiometricsService $enableBiometricsService")
                                    dataRepository.storeEnableBiometricsService(enableBiometricsService)
                                }
                                if (dittoCheckedChanged) {
                                    Log.d(TAG, "Storing enableDittoService $enableDittoService")
                                    dataRepository.storeEnableDittoService(enableDittoService)
                                }

                                Log.d(TAG, "Finished saving settings")
                            }
                        } else {
                            Log.v(TAG, "Settings not changed and hence NOT saved")
                        }
                            navController.navigate("main_screen") {
                                popUpTo(("main_screen")) {
                                    inclusive = true
                                }
                            }
                    })
            }

        }
    }
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
    CallsignSetting(rememberSwipeDismissableNavController(),callsignState)
}

@Preview(widthDp = 192, heightDp = 800)
@Composable
fun SettingsPreview() {
    var callsignState = MutableLiveData<String>("WEAROS-0")
    var licenseState = MutableLiveData<String>("AscentLicense")

    SettingsScreen(rememberSwipeDismissableNavController(),callsignState, licenseState)
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


fun vibrateWatch(context: Context) {
    val vibrator = context.getSystemService(Vibrator::class.java)
    vibrator?.let {
        if (it.hasVibrator()) {
            val vibrationEffect = VibrationEffect.createOneShot(
                100, // Duration in milliseconds
                VibrationEffect.DEFAULT_AMPLITUDE
            )
            it.vibrate(vibrationEffect)
        }
    }
}

public fun Modifier.unswipeable() =
    this.then(
        Modifier.draggable(
            orientation = Orientation.Horizontal,
            enabled = true,
            state = DraggableState {}
        )
    )


