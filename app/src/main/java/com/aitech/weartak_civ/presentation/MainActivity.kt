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
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.Delete
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
import androidx.concurrent.futures.await
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.DataType
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
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
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.HealthServicesRepository
import com.aitech.weartak_civ.events.CustomEmergencyEvent
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.CAT_MANUAL
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.DESC_SOS_ALERT
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.CALLSIGN_TEAM_ROLE_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.DROP_MARKER
import com.aitech.weartak_civ.events.MessageEvent.Companion.OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_DETAILS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_TOGGLED
import com.aitech.weartak_civ.events.MessageEvent.Companion.UPDATE_INTERVAL_CHANGED
import com.aitech.weartak_civ.events.TakServerConnStatusEvent
import com.aitech.weartak_civ.events.TakServerConnStatusEvent.Companion.CONNECTING
import com.aitech.weartak_civ.events.TakServerConnStatusEvent.Companion.CONNECTION_FAILURE
import com.aitech.weartak_civ.events.TakServerConnStatusEvent.Companion.CONNECTION_SUCCESS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.ALL_WIFI_CONNECTIONS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.NO_WIFI_CONNECTIONS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.SOME_WIFI_CONNECTIONS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MainActivity.Constants.address
import com.aitech.weartak_civ.presentation.MainActivity.Constants.addressSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsignSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimization
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimizationSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimizationState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBiometricsService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDittoService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableTAKConnectService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimization
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimizationSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimizationState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.generateSaveBattOnWifiString
import com.aitech.weartak_civ.presentation.MainActivity.Constants.license
import com.aitech.weartak_civ.presentation.MainActivity.Constants.licenseSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.name
import com.aitech.weartak_civ.presentation.MainActivity.Constants.nameSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.networkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.password
import com.aitech.weartak_civ.presentation.MainActivity.Constants.passwordSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.port
import com.aitech.weartak_civ.presentation.MainActivity.Constants.portSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.saveBattOnWifiString
import com.aitech.weartak_civ.presentation.MainActivity.Constants.saveBattOnWifiStringState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.serverSettingsChanged
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startBatteryService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startBiometericService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startCotDispatchService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startDittoService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startOffBodyDetectService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startPowerManagerService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startTAKConnectService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.team
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.username
import com.aitech.weartak_civ.presentation.MainActivity.Constants.usernameSettingsState
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
import com.aitech.weartak_civ.service.TAKConnectService.Companion.takConnectStatusLiveData
import com.aitech.weartak_civ.service.TAKConnectService.Companion.takConnectionStatus
import com.beust.klaxon.Klaxon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import javax.net.ssl.SSLSocket
import kotlin.random.Random


class MainActivity : ComponentActivity() {

    companion object Constants {
        val TAG = "WearTAK-" + MainActivity::class.java.simpleName
        lateinit var mainActivity : MainActivity
        lateinit var device_uid : String
        var callsign = "WEAROS-0"
        var enableLocationService : Boolean =  true
        var locationServiceRunning : Boolean = false
        var enableBiometricsService : Boolean = true
        var biometricServiceRunning : Boolean = false
        var enableTAKConnectService : Boolean = true
        var enableDittoService : Boolean = false
        var dittoServiceRunning : Boolean = false

        var enableCellularService: Boolean = true
        var isConnected : Boolean = true
        lateinit var dataRepository: DataRepository
        lateinit var networkManager : NetworkManager
        private val klaxon = Klaxon()
        final val DEFAULT_TEAMS_LIST = listOf("White", "Yellow", "Orange", "Magenta", "Red", "Maroon", "Purple", "Dark Blue", "Blue", "Cyan", "Teal", "Green", "Dark Green", "Brown")
        final val DEFAULT_ROLES_LIST = listOf("Team Member", "Team Lead", "HQ", "Sniper", "Medic", "Forward Observer", "RTO", "K9")

        var isP12Cert: Boolean = false
        var team = ""
        var role = ""
        var license = "AscentFreeLicense"
        var correctLicense = "AscentFreeLicense"
        var teamValue = ""
        var roleValue = ""
        var name = "Test.Tak"
        var address = "tak.test.ops.ascentitech.com"
        var port = "8089"
        var username = "TEST.AIT"
        var password = "TEST.AIT.Password01"
        lateinit var teamEntriesValuesList : TeamsJson
        lateinit var roleEntriesValuesList : RolesJson
        var callsignSettingsState = MutableLiveData<String>("WEAROS-0")
        val teamState = MutableLiveData<String>("")
        val teamSettingsState = MutableLiveData<String>("")
        val roleState = MutableLiveData<String>("")
        val roleSettingsState = MutableLiveData<String>("")
        val licenseSettingsState = MutableLiveData<String>("")
        val nameSettingsState = MutableLiveData<String>("Test.Tak")
        val addressSettingsState = MutableLiveData<String>("tak.test.ops.ascentitech.com")
        val portSettingsState = MutableLiveData<String>("8089")
        val usernameSettingsState = MutableLiveData<String>("TEST.AIT")
        val passwordSettingsState = MutableLiveData<String>("TEST.AIT.Password01")

        var serverSettingsChanged: Boolean = false

        private val SOME_WIFI_NETWORKS = "Some WiFi Networks"
        private val ALL_WIFI_NETWORKS = "All WiFi Networks"
        private val NO_WIFI_NETWORKS = "No WiFi Networks"
        val SOME_WIFI_CONNECTIONS = "Some WiFi Connections"
        val ALL_WIFI_CONNECTIONS = "All WiFi Connections"
        val NO_WIFI_CONNECTIONS = "No WiFi Connections"
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

        fun startBiometericService() {
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

        fun startTAKConnectService() {
            if (enableTAKConnectService) {
                if (!TAKConnectService.isRunning.get()) {
                    Log.d(TAG, "Starting TAKConnectService")
                    mainActivity.startForegroundService(Intent(mainActivity, TAKConnectService::class.java))
                } else {
                    Log.d(TAG, "TAKConnectService is already running.")
                }
            } else {
                Log.d(TAG, "TAKConnectService is disabled")
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
                    Log.d(TAG, "Starting LocationService")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        mainActivity.startForegroundService(Intent(mainActivity, LocationService::class.java))

                    } else {
                        mainActivity.startService(Intent(mainActivity, LocationService::class.java))
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
            }
        }

        fun startBatteryService() {
            if (!BatteryService.isRunning.get()) {
                Log.d(TAG, "Starting BatteryService")
                mainActivity.startForegroundService(Intent(mainActivity, BatteryService::class.java))
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
    }

    var secureSocket : SSLSocket? = null
    val callsignState = MutableLiveData<String>("WEAROS-0")
    val licenseState = MutableLiveData<String>("")
    val nameState = MutableLiveData<String>("")
    val addressState = MutableLiveData<String>("")
    val portState = MutableLiveData<String>("")
    val usernameState = MutableLiveData<String>("")
    val passwordState = MutableLiveData<String>("")

    lateinit var healthServicesRepository : HealthServicesRepository
    lateinit var measureClient : MeasureClient

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
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
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
        CoroutineScope(Dispatchers.IO).launch {
            secureSocket?.close()
        }
        if (DittoService.isRunning.get()) {mainActivity.stopService(Intent(mainActivity, DittoService::class.java)) }

        networkManager.deInit()
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

    fun setDataFromDatastore() {
        runBlocking {
            var storeCallsign = false
            callsign = dataRepository.getCallsign.first()
            if (callsign.equals("")) {
                callsign = "WEAROS-" + Random.nextInt(1, 1000).toString()
                storeCallsign = true
            }
            callsignState.postValue(callsign) // TODO: Check if we need this at all
            callsignSettingsState.postValue(callsign) // TODO: Check if we need this at all
            team = dataRepository.getTeam.first()
            var teamIndex = teamEntriesValuesList.ltEntries.indexOf(team)
            // Needed on initial install of app where the teams json file does not match with DEFAULT_TEAMS_LIST
            if (teamIndex == -1) {
                team = teamEntriesValuesList.ltEntries[0]
                teamIndex = 0
            }
            teamValue = teamEntriesValuesList.ltValues[teamIndex]
            teamState.postValue(team)
            teamSettingsState.postValue(team)
            role = dataRepository.getRole.first()
            var roleIndex = roleEntriesValuesList.lrEntries.indexOf(role)
            // Needed on initial install of app where the roles json file does not match with DEFAULT_ROLES_LIST
            if (roleIndex == -1) {
                role = roleEntriesValuesList.lrEntries[0]
                roleIndex = 0
            }
            roleValue = roleEntriesValuesList.lrValues[roleIndex]
            roleState.postValue(role)
            roleSettingsState.postValue(role)

            license = dataRepository.getLicense.first()
            licenseState.postValue(license)
            licenseSettingsState.postValue(license)

            name = dataRepository.getName.first()
            nameState.postValue(name)
            nameSettingsState.postValue(name)
            address = dataRepository.getAddress.first()
            addressState.postValue(address)
            addressSettingsState.postValue(address)

            port = dataRepository.getPort.first()
            portState.postValue(port)
            portSettingsState.postValue(port)

            username = dataRepository.getUsername.first()
            usernameState.postValue(username)
            usernameSettingsState.postValue(username)

            password = dataRepository.getPassword.first()
            passwordState.postValue(password)
            passwordSettingsState.postValue(password)

            enableLocationService = dataRepository.getEnableLocationService.first()
            enableBiometricsService = dataRepository.getEnableBiometricsService.first()
            enableTAKConnectService = dataRepository.getEnableTAKConnectService.first()
            enableDittoService = dataRepository.getEnableDittoService.first()

            CotDispatchService.reportingUpdateIntervalSecs = dataRepository.getReportingUpdateInterval.first()
            CotDispatchService.currReportingIntervalInSecs = CotDispatchService.reportingUpdateIntervalSecs
            //LocationService.locationUpdateFastestIntervalSecs = CotDispatchService.reportingUpdateIntervalSecs

            enableWifiBatteryOptimization = dataRepository.getEnableWifiBatteryOptimization.first()
            enableWifiBatteryOptimizationState.postValue(enableWifiBatteryOptimization)
            enableWifiBatteryOptimizationSettingsState.postValue(enableWifiBatteryOptimization)
            PowerManagerService.isWifiOpt = enableWifiBatteryOptimization
            enableAllWifiBatteryOptimization = dataRepository.getEnableAllWifiBatteryOptimization.first()
            enableAllWifiBatteryOptimizationState.postValue(enableAllWifiBatteryOptimization)
            enableAllWifiBatteryOptimizationSettingsState.postValue(enableAllWifiBatteryOptimization)
            PowerManagerService.isAllWifiOpt = enableAllWifiBatteryOptimization
            wifiSsidList = dataRepository.getWifiSsidList.first()
            wifiSsidListState.postValue(wifiSsidList)
            wifiSsidListSettingsState.postValue(wifiSsidList)
            saveBattOnWifiString = generateSaveBattOnWifiString(enableWifiBatteryOptimization, enableAllWifiBatteryOptimization)
            saveBattOnWifiStringState.postValue(saveBattOnWifiString)

            Log.d(TAG, """
                === Device Configuration Settings === 
                DeviceID:                $device_uid
                Callsign:                $callsign
                License:                 $license
                Department:              $username
                enableLocationService:   $enableLocationService
                enableBiometricsService: $enableBiometricsService
                enableTAKConnectService: $enableTAKConnectService
                enableDittoService:      $enableDittoService
                ReportingUpdateInterval: ${CotDispatchService.reportingUpdateIntervalSecs} seconds
                enableWifiBatteryOptimization: $enableWifiBatteryOptimization
                enableBatteryOptimizationOnAllWifiNetworks: $enableAllWifiBatteryOptimization
                batteryOptimizedWifiSsidList: $wifiSsidList
                =====================================
            """.trimIndent())

            // Set callsign on first install
            if (storeCallsign) {
                Log.d(TAG, "Storing initial callsign $callsign")
                dataRepository.storeCallsign(callsign)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTakConnectionSuccess(event: TakServerConnStatusEvent) {
        if (event.connEvent == CONNECTION_SUCCESS) {
            Log.v(TAG, "TAKConnectService connected. Getting Roles & Teams Configurations from Server...")
            getRolesAndTeamsJson()
        }
    }

    private fun setTeamsJson() {
        val (teamEntries, teamValues) = readJson("teams")
        if (teamEntries.isEmpty() || teamValues.isEmpty()) {
            Log.v(TAG, "Teams JSON empty or failed to parse. Using default teams.")
            teamEntriesValuesList = TeamsJson(DEFAULT_TEAMS_LIST, DEFAULT_TEAMS_LIST)
        } else {
            teamEntriesValuesList = TeamsJson(teamEntries, teamValues)
            Log.v(TAG, "Team Entries List: ${teamEntriesValuesList.ltEntries}")
            Log.v(TAG, "Team Values List: ${teamEntriesValuesList.ltValues}")
        }
    }

    private fun setRolesJson() {
        val (roleEntries, roleValues) = readJson("roles")
        if (roleEntries.isEmpty() || roleValues.isEmpty()) {
            Log.v(TAG, "Roles JSON empty or failed to parse. Using default roles.")
            roleEntriesValuesList = RolesJson(DEFAULT_ROLES_LIST, DEFAULT_ROLES_LIST)
        } else {
            roleEntriesValuesList = RolesJson(roleEntries, roleValues)
            Log.v(TAG, "Roles Entries List: ${roleEntriesValuesList.lrEntries}")
            Log.v(TAG, "Roles Values List: ${roleEntriesValuesList.lrValues}")
        }
    }

    private fun getDepartmentId(): String {
        return "TEST.AIT"
    }

    private fun getRolesAndTeamsJson() {
        CoroutineScope(Dispatchers.IO).launch {
            // need ip to build url endpoint
            val takServerIp = dataRepository.getAddress.first().toString().trim()
            val departmentIdentifier = dataRepository.getUsername.first().toString() // e.g., "TEST.AIT" or "$state.$department.$departmentType

            // build the url endpoint to obtain config.json (e.g. https://$ip/$departmentIdentifier/teams.json)
            val teamsJsonUrl = NetworkUtils.buildJsonUrl(takServerIp, departmentIdentifier, "teams")
            val rolesJsonUrl = NetworkUtils.buildJsonUrl(takServerIp, departmentIdentifier, "roles")

            // fetch the json from the url endpoint
            val teamsJsonResult = NetworkUtils.fetchJsonFromServer(teamsJsonUrl)
            val rolesJsonResult = NetworkUtils.fetchJsonFromServer(rolesJsonUrl)

            // if we can get the config, use it. If not, fallback to local.
            if (teamsJsonResult != null) {
                val parsedTeams = TeamsJson.fromJson(teamsJsonResult)
                if (parsedTeams != null) {
                    // Successfully retrieved & parsed teams config file. Setting values for frontend & store the file locally!
                    teamEntriesValuesList = parsedTeams
                    storeJson("teams", teamsJsonResult)
                } else {
                    // Fallback to local file if parsing fails
                    Log.v(TAG, "Could not parse teams Json. Falling back to persistent store teams config")
                }
            } else {
                // Fallback to local file if server call fails
                Log.v(TAG, "Could not retrieve teams.json file from server. Falling back to persistent store teams config")
            }

            if (rolesJsonResult != null) {
                val parsedRoles = RolesJson.fromJson(rolesJsonResult)
                if (parsedRoles != null) {
                    // Successfully retrieved & parsed roles config file. Setting values for frontend & store the file locally!
                    roleEntriesValuesList = parsedRoles
                    storeJson("roles", rolesJsonResult)
                } else {
                    // Fallback to local file if parsing fails
                    Log.v(TAG, "Could not parse roles Json. Falling back to persistent store roles config.")
                }
            } else {
                // Fallback to local file if server call fails
                Log.v(TAG, "Could not retrieve roles.json file from server. Falling back to persistent store roles config")
            }
        }
    }

    private fun storeJson(configType: String, json: String) {
        val fileName = "$configType.json"
//        Log.v(TAG, "Storing $fileName to persistent store")
//        val filesDir = this.filesDir.absolutePath
//        Log.v(TAG, "Files directory: $filesDir")

        try {
            // open the file in private mode
            openFileOutput(fileName, Context.MODE_PRIVATE).use { outputStream ->
                outputStream.write(json.toByteArray(Charsets.UTF_8))
            }
            Log.v(TAG, "Successfully stored $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing JSON to $fileName", e)
        }
    }

    private fun readJson(configType: String): Pair<List<String>, List<String>> {
        val fileName = "$configType.json"
        return try {
            openFileInput(fileName).use { inputStream ->
                val bytes = inputStream.readBytes()
                val jsonString = String(bytes, Charsets.UTF_8)
                if (configType.equals("teams", ignoreCase = true)) {
                    val teamsJson = TeamsJson.fromJson(jsonString)
                    if (teamsJson != null) {
                        Pair(teamsJson.ltEntries, teamsJson.ltValues)
                    } else {
                        Pair(emptyList(), emptyList())
                    }
                } else if (configType.equals("roles", ignoreCase = true)) {
                    val rolesJson = RolesJson.fromJson(jsonString)
                    if (rolesJson != null) {
                        Pair(rolesJson.lrEntries, rolesJson.lrValues)
                    } else {
                        Pair(emptyList(), emptyList())
                    }
                } else {
                    Pair(emptyList(), emptyList())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading $fileName from internal storage", e)
            Pair(emptyList(), emptyList())
        }
    }


    private fun startHealthCapabilitiesCollection() {
        val healthClient = HealthServices.getClient(this)
        val passiveMonitoringClient = healthClient.passiveMonitoringClient
        measureClient = healthClient.measureClient

        lifecycleScope.launch {
            try {
                // Await the Passive Monitoring capabilities
                val capabilities = passiveMonitoringClient.getCapabilitiesAsync().await()
                val supportedDataTypes = capabilities.supportedDataTypesPassiveMonitoring

                Log.d(TAG, "Supported passive monitoring data: $supportedDataTypes")

                val supportsHeartRate = DataType.HEART_RATE_BPM in supportedDataTypes
                val supportsVo2Max = DataType.VO2_MAX in supportedDataTypes
                val supportsLocation = DataType.LOCATION in supportedDataTypes

                Log.d(TAG, "supportsHeartRate: $supportsHeartRate; supportsVo2Max: $supportsVo2Max; supportsLocation: $supportsLocation")

                // Await the Measure capabilities
                val measureCapabilities = measureClient?.getCapabilitiesAsync()?.await()
                val measureSupportsHeartRate = DataType.HEART_RATE_BPM in (measureCapabilities?.supportedDataTypesMeasure ?: emptySet())

                Log.d(TAG, "measureCapabilities: $measureCapabilities")
                Log.d(TAG, "measureSupportsHeartRate: $measureSupportsHeartRate")

                // If needed, register callbacks
                // measureClient?.registerMeasureCallback(DataType.HEART_RATE_BPM, heartRateCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching health capabilities: ${e.message}")
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE)

        /*
        val requiredPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        )
         */

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
        Log.d(TAG, "onCreate called")
        mainActivity = this
        device_uid = "WEAROS_" + Secure.getString(this.getContentResolver(), Secure.ANDROID_ID)
//        Log.d(TAG, "Device UID is $device_uid")
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        dataRepository = DataRepository(this)
        //val file: File? = null
        //logFile = java.io.File(file, "/sdcard/Download/WearTAK_logs.txt")

        Log.d(TAG, " === === Teams And Roles === ===")
        setTeamsJson()
        setRolesJson()
        setDataFromDatastore()
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

        validateLicense(license)

        setTheme(android.R.style.Theme_DeviceDefault)

        //printSensorDetails(this)

        // Request all necessary permissions
        checkAndRequestPermissions()
        //startHealthCapabilitiesCollection()

        if (isLicenseValid()) {
            Log.d(TAG, """
                === Application Services onCreate ===
            """.trimIndent())
            startTAKConnectService()
            startOffBodyDetectService()
            startCotDispatchService()
            startLocationService()
            startBiometericService()
            startBatteryService()
            startDittoService()
            startPowerManagerService()
            Log.d(TAG, """
                =====================================
            """.trimIndent())
        }

        /*
        val intent = Intent()
        val pm : PowerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(this.packageName)) {
            intent.action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            Log.d(TAG, "pm.isIgnoringBatteryOptimizations is TRUE: Going to call startActivity to ignore battery optimizations")
        } else {
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:${this.packageName}")
            Log.d(TAG, "Going to call startActivity to request ignore battery optimizations")
        }
        this.startActivity(intent)
        */

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
                Toast.makeText(mainActivity, "Please switch ON WearTAK-CIV in Installed Apps of Accessibility Settings", Toast.LENGTH_LONG).show()
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

    @Subscribe(threadMode = ThreadMode.MAIN)
    public fun onMessageEvent(event: TakServerConnStatusEvent) {
        var toastText = ""
        val connEvent = event.connEvent
        if (connEvent == CONNECTING) {
            toastText = "Connecting to $name"
            Log.d(TAG, "Received TakServerConnStatusEvent: CONNECTING [$connEvent]")
        } else if (connEvent == CONNECTION_SUCCESS) {
            toastText = "Connected to $name"
            Log.d(TAG, "Received TakServerConnStatusEvent: CONNECTION_SUCCESS [$connEvent]")
        } else if (connEvent == CONNECTION_FAILURE) {
            toastText = "Connection Failed"
            Log.d(TAG, "Received TakServerConnStatusEvent: CONNECTION_FAILURE [$connEvent]")
        }
        //Log.d(TAG, "Showing toast w/ text: $toastText")
//        Toast.makeText(mainActivity, toastText, Toast.LENGTH_SHORT).show()
    }
}


//Contains functionality for all watch screens
@Composable
fun WearTAKCivApp(callsignLiveDataState: MutableLiveData<String>, licenseLiveDataState: MutableLiveData<String>) {
    val navController = rememberSwipeDismissableNavController()
    val context = LocalContext.current
    validateLicense(license)
    val isP12Cert = remember { mutableStateOf(false) }

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
            EditServerPage(navController, isP12Cert)
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
                label = { Text(text = "Callsign", color = colors.primary) },
                placeholder = { Text(text = "Your Callsign", color = colors.primary) },
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

@SuppressLint("CoroutineCreationDuringComposition")
@Composable
fun TakServerSetting(navController: NavController) {
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
                .padding(bottom = 30.dp)
                .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                var takServerChecked by remember{ mutableStateOf(enableTAKConnectService)}
                var takServerCheckedChanged : Boolean = false


                Text(
                    text = "TAK Server",
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                TakServerChip(
                    navController,
                    "new_server_screen",
                    name,
                    address,
                    checked = takServerChecked,
                    onCheckedChange =  { takServerChecked = it },
                )

                if (enableTAKConnectService != takServerChecked) {
                    enableTAKConnectService = takServerChecked; takServerCheckedChanged = true
                    if (enableTAKConnectService) {
                        if (!TAKConnectService.isRunning.get()) {
                            Log.d(TAG, "Connecting to tak server")
                            mainActivity.startForegroundService(
                                Intent(
                                    mainActivity,
                                    TAKConnectService::class.java
                                )
                            )

                        } else {
                            Log.d(TAG, "Disconnecting from tak server")
                            mainActivity.stopService(
                                Intent(
                                    mainActivity,
                                    TAKConnectService::class.java
                                )
                            )
                        }
                    }
                }

                if (takServerCheckedChanged) {
                    Log.d(TAG, "Storing enableLocationService $enableTAKConnectService")
                    CoroutineScope(Dispatchers.Main).launch {
                        dataRepository.storeEnableTAKConnectService(enableTAKConnectService)
                        EventBus.getDefault().post(MessageEvent(SERVER_TOGGLED, 0))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

//                Chip(
//                    onClick = { navController.navigate("new_server_screen") },
//                    enabled = true,
//                    label = { Text(text = "Add New Server", fontSize = 13.sp) },
//                    colors = ChipDefaults.chipColors(
//                        backgroundColor = Color.DarkGray,
//                        contentColor = Color.White,
//                        secondaryContentColor = Color.LightGray
//                    ),
//                    icon = { Icon(Icons.Filled.Add, contentDescription = "Localized description") },
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .padding(bottom = 3.dp)
//                )
            }
            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                Button(
                    onClick = { navController.navigate("settings_screen") {
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
                        contentDescription = "Localized description",
                    )
                }
            }
            var pp = remember {mutableStateOf(TAKConnectService.takConnectionStatus)}
            val loadingDialog by TAKConnectService.takConnectStatusLiveData.observeAsState()
            val shouldShowLoadingDialog = remember { mutableStateOf(false) }
            val shouldShowConfirmDialog = remember { mutableStateOf(false) }
            val isServerConnected = remember { mutableStateOf(false) }

            takConnectionStatus = "NULL"
            takConnectStatusLiveData.postValue(takConnectionStatus)

            if (serverSettingsChanged) {
                if (loadingDialog.equals("CONNECTING")) {
                    shouldShowLoadingDialog.value = true
                    shouldShowConfirmDialog.value = false
                } else if (loadingDialog.equals("CONNECTION_SUCCESS")) {
                    shouldShowLoadingDialog.value = false
                    shouldShowConfirmDialog.value = true
                    isServerConnected.value = true
                } else if (loadingDialog.equals("CONNECTION_FAILURE")) {
                    shouldShowLoadingDialog.value = false
                    shouldShowConfirmDialog.value = true
                    isServerConnected.value = false
                }
                LoadingDialog(shouldShowLoadingDialog)
                ServerRegistrationDialog(
                    shouldShowConfirmDialog,
                    navController,
                    isServerConnected
                )
            }
        }
    }
}

@Composable
fun TakServerChip(
    navController: NavController,
    route: String,
    title: String,
    address: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val takServer by TAKConnectService.isTakConnectedLiveData.observeAsState()
    val takServerConnecting by TAKConnectService.takConnectStatusLiveData.observeAsState()
    SplitToggleChip(
        label = {
            Image(
                painter = if (takServer == true) painterResource(R.drawable.tak_server_connected) else if (takServerConnecting == "CONNECTING") painterResource(R.drawable.tak_server_connecting) else painterResource(R.drawable.tak_server_disconnected),
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
        onClick = {
            navController.navigate(route)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 3.dp)
    )
}

@Composable
fun EditServerPage(
    navController: NavController,
    isP12Cert: MutableState<Boolean>) {
    WearTAKCIVTheme {
//        val shouldShowDialog = remember { mutableStateOf(false) }
//        val isConfirmed = remember { mutableStateOf(false) }
        val nameTextState = remember { mutableStateOf(nameSettingsState.value ?: "null") }
        val addressTextState = remember { mutableStateOf(addressSettingsState.value ?: "null") }
        var portTextState = remember { mutableStateOf(portSettingsState.value ?: "null") }
        val usernameTextState = remember { mutableStateOf(usernameSettingsState.value ?: "null") }
        val passwordTextState = remember { mutableStateOf(passwordSettingsState.value ?: "null") }
        var reportingUpdateInterval by remember{ mutableStateOf(TextFieldValue(CotDispatchService.reportingUpdateIntervalSecs.toString()))}

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {

            //TODO: Move this to tak server chip when its turned into a swipe to reveal
//            if (shouldShowDialog.value) {
//                ConfirmationDialog(
//                    shouldShowDialog = shouldShowDialog,
//                    navController,
//                    "Are you sure you want to delete this server?",
//                    Icons.Default.Delete,
//                    Color.Red,
//                    "main",
//                    isConfirmed
//                )
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
                    text = "Edit Server",
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                NewServerSettingText(
                    "Connection Name",
                    nameSettingsState,
                    nameTextState,
                    false,
                    false,
                    true
                )

                if (isP12Cert.value) {
                    NewServerSettingText(
                        "Address/URL",
                        addressSettingsState,
                        addressTextState,
                        false,
                        false,
                        false
                    )
                    NewServerSettingText("Port", portSettingsState, portTextState, false, true, true)
                } else {
                    NewServerSettingText(
                        "Address/URL",
                        addressSettingsState,
                        addressTextState,
                        false,
                        false,
                        true
                    )
                    NewServerSettingText("Port", portSettingsState, portTextState, false, true, true)
                    NewServerSettingText(
                        "Username",
                        usernameSettingsState,
                        usernameTextState,
                        false,
                        false,
                        true
                    )
                    NewServerSettingText(
                        "Password",
                        passwordSettingsState,
                        passwordTextState,
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
                            if (reportingUpdateInterval.text.isEmpty()) {
                                Toast.makeText(mainActivity, "Reporting Rate Empty, Enter Again", Toast.LENGTH_SHORT).show()
                            } else if (reportingUpdateInterval.text == "0") {
                                Toast.makeText(mainActivity, "Reporting Rate 0, Enter Again", Toast.LENGTH_SHORT).show()
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
                            text = "Update Interval",
                            color = colors.primary,
                            style = MaterialTheme.typography.caption3
                        )
                    },
                    placeholder = {
                        Text(
                            text = "Interval in secs",
                            color = colors.primary,
                            style = MaterialTheme.typography.caption3
                        )
                    },
                    textStyle = TextStyle(color = colors.primary),
                )
                if (isP12Cert.value) {
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
                var nameChanged: Boolean = false
                var addressChanged: Boolean = false
                var portChanged: Boolean = false
                var usernameChanged: Boolean = false
                var passwordChanged: Boolean = false
                val maxReportRate: Int = 100000000
                val minReportRate: Int = 1
                var reportingUpdateIntervalChanged = false

                val context = LocalContext.current

                ConfirmCancelButtons(
                    onClickCancel = {
                        serverSettingsChanged = false; nameChanged = false; addressChanged =
                        false; portChanged = false; usernameChanged = false; passwordChanged =
                        false
                        Log.d(TAG, "Settings NOT saved")
                        navController.navigate("tak_server_screen") {
                            popUpTo(("tak_server_screen")) {
                                inclusive = true
                            }
                        }
                    },
                    onClickConfirm = {
                        if (!nameTextState.value.equals(nameSettingsState.value)) {
                            serverSettingsChanged = true; nameChanged = true
                            name = nameTextState.value ?: name
                            nameSettingsState.postValue(name)
                        }
                        if (!addressTextState.value.equals(addressSettingsState.value)) {
                            serverSettingsChanged = true; addressChanged = true
                            address = addressTextState.value.trim() ?: address
                            // let the user enter shorthand server address (without periods ".")
                            if (!address.contains(".")) {
                                Log.v(TAG, "Entered address was $address, setting address to tak.$address.ops.ascentitech.com")
                                address = "tak.$address.ops.ascentitech.com"
                            }
                            addressSettingsState.postValue(address)
                        }
                        if (!portTextState.value.equals(portSettingsState.value)) {
                            serverSettingsChanged = true; portChanged = true
                            port = portTextState.value ?: port
                            portSettingsState.postValue(port)
                        }
                        if (!usernameTextState.value.equals(usernameSettingsState.value)) {
                            serverSettingsChanged = true; usernameChanged = true
                            username = usernameTextState.value ?: username
                            usernameSettingsState.postValue(username)
                        }
                        if (!passwordTextState.value.equals(passwordSettingsState.value)) {
                            serverSettingsChanged = true; passwordChanged = true
                            password = passwordTextState.value ?: password
                            passwordSettingsState.postValue(password)
                        }

                        val reportingUpdateIntervalText = reportingUpdateInterval.text
                        if (!reportingUpdateIntervalText.equals("") && (reportingUpdateIntervalText.toInt() != 0) && !reportingUpdateIntervalText.equals(
                                CotDispatchService.reportingUpdateIntervalSecs.toString()
                            ) && reportingUpdateInterval.text.toInt() >= minReportRate) {
                            reportingUpdateIntervalChanged = true
                            CotDispatchService.reportingUpdateIntervalSecs = reportingUpdateIntervalText.toInt()
                            //LocationService.locationUpdateFastestIntervalSecs = CotDispatchService.reportingUpdateIntervalSecs
                            Log.d(TAG, "Setting reportingUpdateIntervalSecs to ${CotDispatchService.reportingUpdateIntervalSecs}")
                        }

                        if (serverSettingsChanged) {
                            CoroutineScope(Dispatchers.Main).launch {
                                    if (nameChanged) {
                                        dataRepository.storeName(name)
                                    }
                                    if (addressChanged) {
                                        dataRepository.storeAddress(address)
                                    }
                                    if (portChanged) {
                                        dataRepository.storePort(port)
                                    }
                                    if (usernameChanged) {
                                        dataRepository.storeUsername(username)
                                    }
                                    if (passwordChanged) {
                                        dataRepository.storePassword(password)
                                    }
                                EventBus
                                    .getDefault()
                                    .post(MessageEvent(SERVER_DETAILS_CHANGED, 0))

                                }
                        }
                        if (reportingUpdateIntervalChanged){
                            CoroutineScope(Dispatchers.Main).launch {

                                Log.d(
                                    TAG,
                                    "Storing reportingUpdateInterval (${CotDispatchService.reportingUpdateIntervalSecs})"
                                )
                                runBlocking {
                                    dataRepository.storeReportingUpdateInterval(
                                        CotDispatchService.reportingUpdateIntervalSecs
                                    )
                                }
                                EventBus.getDefault().post(
                                    MessageEvent(
                                        UPDATE_INTERVAL_CHANGED,
                                        CotDispatchService.reportingUpdateIntervalSecs
                                    )
                                )
                            }
                        }
                        if (reportingUpdateIntervalText.equals("")) {
                            Toast.makeText(mainActivity,"Reporting Rate Empty, Enter Again", Toast.LENGTH_SHORT).show()
                        } else if (reportingUpdateIntervalText.equals("0")) {
                            Toast.makeText(mainActivity,"Reporting Rate 0, Enter Again", Toast.LENGTH_SHORT).show()
                        } else if (reportingUpdateInterval.text.toInt() < minReportRate /* || reportingUpdateInterval.text.toInt() > maxReportRate */) {
                            Toast.makeText(mainActivity,"Reporting Rate Outside Bounds " + "$minReportRate" + "-" + "$maxReportRate", Toast.LENGTH_SHORT).show()
                        } else {
                            navController.navigate("tak_server_screen") {
                                popUpTo(("tak_server_screen")) {
                                    inclusive = true
                                }
                            }
                        }
                    })
            }
        }
    }
}

@Composable
fun NewServerSettingText (
    label: String,
    liveDataState: MutableLiveData<String>,
    textState: MutableState<String>,
    isPassword : Boolean,
    isPort : Boolean,
    isEnabled: Boolean
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue(liveDataState.value ?: "null")) }
    val maxChar = 20

    OutlinedTextField(
        value = textFieldValue,
        enabled = isEnabled,
        onValueChange = {
            textFieldValue = it
        },
//        maxLines = 1,
        label = { Text(text = label, color = colors.primary, fontSize = 12.sp) },
        textStyle = TextStyle(color = colors.primary, fontSize = if (textFieldValue.text!!.length <= maxChar) 12.sp else 10.sp),
        modifier = Modifier.padding(bottom = 5.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (isPort) KeyboardType.Number else KeyboardType.Text),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
    )
    textState.value = textFieldValue.text
}

@Composable
fun UpdateIntervalServerSettingText (liveDataState: MutableLiveData<String>,
                                     textState: MutableState<String>,
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue(liveDataState.value ?: "null")) }

    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        modifier = Modifier.padding(vertical = 5.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                if (textFieldValue.text.isEmpty()) {
                    Toast.makeText(mainActivity, "Reporting Rate Empty, Enter Again", Toast.LENGTH_SHORT).show()
                } else if (textFieldValue.text == "0") {
                    Toast.makeText(mainActivity, "Reporting Rate 0, Enter Again", Toast.LENGTH_SHORT).show()
                } else {
                    // If there is valid input, we can clear focus
                    // (which typically dismisses the keyboard)
                    focusManager.clearFocus()

                    // Now you can navigate or do the next action
                }
            }
        ),
        singleLine = true,
        value = textFieldValue,
        onValueChange = {
            textFieldValue = it
        },
        label = {
            Text(
                text = "Update Interval",
                color = colors.primary,
                style = MaterialTheme.typography.caption3
            )
        },
        placeholder = {
            Text(
                text = "Interval in secs",
                color = colors.primary,
                style = MaterialTheme.typography.caption3
            )
        },
        textStyle = TextStyle(color = colors.primary),
    )
    textState.value = textFieldValue.text

}

@Composable
fun OptimizeBatteryOnWifiSelectScreen(navController: NavController) {
    val enableWifiOpt = enableWifiBatteryOptimizationSettingsState.value ?: true
    val enableAllWifiOpt = enableAllWifiBatteryOptimizationSettingsState.value ?: true
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
                    text = "Optimize Battery on WiFi",
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(3.dp)
                        .padding(horizontal = 10.dp)
                )

                Text(
                    text = "Slows Update Rate on Enabled WiFi Networks",
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .padding(horizontal = 10.dp)
                )

                Text(
                    text = "Optimize On:",
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
                if (selectedValue.value == "Some WiFi Connections") {
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
                                text = "Edit Optimized WiFi Connections",
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
                            text = "Enabled WiFi Connections",
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(10.dp)
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
                            label = { Text(text = "Add Network") },
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
        modifier =
        Modifier
            .fillMaxWidth()
            .semantics {
                // Use custom actions to make the primary and secondary actions accessible
                customActions =
                    listOf(
                        CustomAccessibilityAction("Delete") {
                            /* Add the primary action click handler here */
                            true
                        },
                        CustomAccessibilityAction("More Options") {
                            /* Add the secondary click handler here */
                            true
                        }
                    )
            },
        primaryAction = {
            SwipeToRevealPrimaryAction(
                revealState = revealState,
                icon = { Icon(SwipeToRevealDefaults.Delete, "Delete") },
                label = { Text("Delete") },
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
                label = { Text("Undo") },
                onClick = { /* Add the undo handler for primary action */ }
            )
        },
        undoSecondaryAction = {
            SwipeToRevealUndoAction(
                revealState = revealState,
                label = { Text("Undo") },
                onClick = { /* Add the undo handler for secondary action */ }
            )
        },
        onFullSwipe = { }
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
            label = { Text(text = "Wifi SSID", color = colors.primary) },
            placeholder = { Text(text = "Wifi SSID", color = colors.primary) },
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
            label = { Text(text = "Wifi SSID", color = colors.primary) },
            placeholder = { Text(text = "Wifi SSID", color = colors.primary) },
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
                        text = "Registering device with TAK Server",
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun ServerRegistrationDialog(
    shouldShowDialog: MutableState<Boolean>,
    navController: NavController,
    isServerConnected: MutableState<Boolean>
) {
    if (shouldShowDialog.value) {
        AlertDialog(
            modifier = Modifier.wrapContentHeight(),
            containerColor = Color.DarkGray,
            onDismissRequest = {
                shouldShowDialog.value = false
            },
            title = {
                if (isServerConnected.value) {
                    Text("Registration Complete", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                } else {
                    Text("Registration Failed", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 15.dp))
                }
            },
            text = {
                if (isServerConnected.value) {
                    Text("TAK Server registration succeeded", textAlign = TextAlign.Center)
                } else {
                    Text("Invalid Credentials, try again", textAlign = TextAlign.Center)
                }
            },
            confirmButton = {
                if (isServerConnected.value) {
                    //Don't create retry button
                } else {
                    Button(
                        onClick = {
                            shouldShowDialog.value = false
                            navController.navigate("new_server_screen")
                        },
                        modifier = Modifier.fillMaxWidth(0.45f),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.LightGray,
                        ),
                    ) {
                        Text(
                            text = "Retry",
                            color = Color.Black
                        )
                    }
                }
            },

            dismissButton = {
                    Button(
                        onClick = {
                            shouldShowDialog.value = false
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = if (isServerConnected.value) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.45f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Gray,
                        )
                    ) {
                        if (isServerConnected.value) {
                            Text(
                                text = "Okay",
                            )
                        } else {
                            Text(
                                text = "Cancel",
                            )
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
                    Text(description, textAlign = TextAlign.Center)
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
                            text = "Confirm",
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
                            text = "Dismiss",
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
            label = { Text(text = "License Key", color = colors.primary) },
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
                    if (!enteredLicense.equals(currentLicense)) {
                        settingsChanged = true; licenseChanged = true
                        license = licenseTextFieldValue.text ?: license
                        Log.d(TAG, "License Changed. Updating DataRepository.")
                        CoroutineScope(Dispatchers.Main).launch {
                            licenseSettingsState.postValue(license)
                            licenseLiveDataState.postValue(license)
                            dataRepository.storeLicense(license)
                        }
                        Log.d(TAG, "Sending License for Validation.")
                        validateLicense(license)
                    } else {
                        Log.d(TAG, "License Did not Change.")
                    }

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

                        Log.d(
                            TAG, """
                        ===   Application Services Info   ===
                        =====================================
                        """.trimIndent()
                        )
                        startTAKConnectService()
                        startOffBodyDetectService()
                        startBatteryService()
                        startLocationService()
                        startCotDispatchService()
                        startBiometericService()
                        startDittoService()
                        startPowerManagerService()
                    } else {
                        Log.d(
                            TAG, """
                            License is invalid. Stopping Services if Started. Navigating to Invalid License Screen.
                            =====================================
                            """.trimIndent()
                        )
                        Log.d(
                            TAG, """
                            ===   Application Services Info   ===                                
                            """.trimIndent()
                        )
                        if (TAKConnectService.isRunning.get()) {
                            mainActivity.stopService(Intent(mainActivity, TAKConnectService::class.java))
                        }
                        if (BatteryService.isRunning.get()) {
                            mainActivity.stopService(
                                Intent(
                                    mainActivity,
                                    BatteryService::class.java
                                )
                            )
                        }
                        if (PowerManagerService.isRunning.get()) {
                            mainActivity.stopService(
                                Intent(
                                    mainActivity,
                                    PowerManagerService::class.java
                                )
                            )
                        }
                        if (enableBiometricsService && HeartrateService.isRunning.get()) {
                            mainActivity.stopService(
                                Intent(
                                    mainActivity,
                                    HeartrateService::class.java
                                )
                            )
                        }
                        if (enableBiometricsService && OffBodyDetectService.isRunning.get()) {
                            mainActivity.stopService(
                                Intent(
                                    mainActivity,
                                    OffBodyDetectService::class.java
                                )
                            )
                        }
                        if (enableLocationService && LocationService.isRunning.get()) {
                            mainActivity.stopService(
                                Intent(
                                    mainActivity,
                                    LocationService::class.java
                                )
                            )
                        }
                        if (enableDittoService && DittoService.isRunning.get()) {
                            mainActivity.stopService(
                                Intent(
                                    mainActivity,
                                    DittoService::class.java
                                )
                            )
                        }

                        navController.navigate("invalid_license_screen"){
                            popUpTo("settings_screen") { inclusive = true }
                        }

                    }
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
                text = "Invalid License Key",
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(5.dp))
            //Maybe add info here for how to contact Ascent for the key?
            Text(
                text = "Please add correct key to use WearTAK",
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
                    text = "Update License",
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
                    text = " bpm",
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
        val takServer by TAKConnectService.isTakConnectedLiveData.observeAsState()
        val takServerConnecting by TAKConnectService.takConnectStatusLiveData.observeAsState()
        val isConnected by NetworkManager.isNetworkConnectedLiveData.observeAsState()
        val cellConnected by NetworkManager.isCellularConnectedLiveData.observeAsState()
        val wifiConnected by NetworkManager.isWifiConnectedLiveData.observeAsState()


        // Icon on the top left curve
        Image(
            painter = if (takServer == true) painterResource(R.drawable.tak_server_connected) else if (takServerConnecting == "CONNECTING") painterResource(R.drawable.tak_server_connecting) else painterResource(R.drawable.tak_server_disconnected),
            contentDescription = "Top Left Icon",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(
                    x = (40).dp, // Adjust this value based on the curve
                    y = (-10).dp // Adjust this value based on the curve
                )
                .size(25.dp) // Adjust icon size as needed
        )
        Log.d(TAG, "TakConnecdtionStatus = $takServerConnecting")


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
                            text = "Success!",
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
                            text = "Error",
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
            wifiSsidListSettingsState.postValue(wifiSsidList)
            wifiSsidListSettingsState.postValue(wifiSsidList)
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
    val takServer by TAKConnectService.isTakConnectedLiveData.observeAsState()
    val location = dataRepository.latestLocation.value
    var locationChecked by remember{ mutableStateOf(enableLocationService)}


    if (shouldShowConfirmationDialog.value) {
        ConfirmationDialog(
            shouldShowConfirmationDialog,
            rememberSwipeDismissableNavController(),
            "Do you want to trigger an SOS alert at your location?",
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
            "SOS",
            fontSize = 25.sp,
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
    val takServer by TAKConnectService.isTakConnectedLiveData.observeAsState()
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
            "Drop marker at current location?",
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
                    text = "Callsign Settings",
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                CallsignRoleTeamChip(
                    "callsign_screen",
                    "Callsign",
                    callsignSettingsState,
                    navController
                )
                CallsignRoleTeamChip("team_screen", "Team", teamSettingsState, navController)
                CallsignRoleTeamChip("role_screen", "Role", roleSettingsState, navController)

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Connection Settings",
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(7.dp)
                )

                Chip(
                    onClick = { navController.navigate("tak_server_screen") },
                    enabled = true,
                    label = { Text(text = "TAK Server") },
                    secondaryLabel = { Text(text = name) },
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
                    label = { Text(text = "Save Battery on WiFi") },
                    secondaryLabel = {Text(text = "$displayedSecondaryText")},
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
                    label = { Text(text = "License Key") },
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
                    text = "General Settings",
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                SettingSwitchToggle(
                    checked = locationChecked, onCheckedChange = { locationChecked = it },
                    title = "Location Service", description = "Service ON/OFF"
                )

                SettingSwitchToggle(
                    checked = biometricsChecked, onCheckedChange = { biometricsChecked = it },
                    title = "Biometrics",
                    description = "Service ON/OFF",
                )

                SettingSwitchToggle(
                    checked = dittoChecked, onCheckedChange = { dittoChecked = it },
                    title = "Ditto",
                    description = "Service ON/OFF",
                )

                Text(
                    modifier = Modifier.padding(top = 7.dp),
                    text = "Version $versionNumber",
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

                        if (enableLocationService != locationChecked) {
                            settingsChanged = true; locationCheckedChanged = true
                            enableLocationService = locationChecked
                            if (enableLocationService) {
                                Log.d(TAG, "Starting location service")
                                startLocationService()
                            } else {
                                Log.d(TAG, "Stopping location service")
                                mainActivity.stopService(Intent(mainActivity, LocationService::class.java))
                            }
                        }

                        if (enableBiometricsService != biometricsChecked) {
                            settingsChanged = true; biometricsCheckedChanged = true
                            enableBiometricsService = biometricsChecked
                            if (enableBiometricsService) {
                                Log.d(TAG, "Starting biometric service")
                                startBiometericService()
                            } else {
                                Log.d(TAG, "Stopping biometric service")
                                mainActivity.stopService(Intent(mainActivity, HeartrateService::class.java))
                            }
                        }

                        if (enableDittoService != dittoChecked) {
                            settingsChanged = true; dittoCheckedChanged = true
                            enableDittoService = dittoChecked
                            if (enableDittoService) {
                                Log.d(TAG, "Starting ditto service")
                                startDittoService()
                            } else {
                                Log.d(TAG, "Stopping ditto service")
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
fun TAKSettingsPreview() {
    TakServerSetting(rememberSwipeDismissableNavController())
}

@Preview(widthDp = 192, heightDp = 600, showBackground = true)
@Composable
fun EditServerPreview() {
    val isP12Cert = remember { mutableStateOf(true) }
    EditServerPage(rememberSwipeDismissableNavController(), isP12Cert)
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


