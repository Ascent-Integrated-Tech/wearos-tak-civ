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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings.Secure
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.concurrent.futures.await
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.awaitWithException
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.foundation.SwipeToDismissBoxState
import androidx.wear.compose.foundation.edgeSwipeToDismiss
import androidx.wear.compose.foundation.rememberRevealState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material.Checkbox
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ContentAlpha
import androidx.wear.compose.material.ExperimentalWearMaterialApi
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.SelectableChip
import androidx.wear.compose.material.SplitSelectableChip
import androidx.wear.compose.material.SplitToggleChip
import androidx.wear.compose.material.SwipeToRevealChip
import androidx.wear.compose.material.SwipeToRevealDefaults
import androidx.wear.compose.material.SwipeToRevealPrimaryAction
import androidx.wear.compose.material.SwipeToRevealSecondaryAction
import androidx.wear.compose.material.SwipeToRevealUndoAction
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.protolayout.LayoutElementBuilders.HorizontalAlignment
import androidx.wear.protolayout.LayoutElementBuilders.VerticalAlignment
import androidx.wear.protolayout.material.ButtonColors
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.HealthServicesRepository
import com.aitech.weartak_civ.data.latestHeartRate
import com.aitech.weartak_civ.presentation.MainActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsignSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.correctLicense
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBiometricsService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.license
import com.aitech.weartak_civ.presentation.MainActivity.Constants.licenseSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.networkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startBiometericService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.team
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.BatteryService
import com.aitech.weartak_civ.service.CotDispatchService
import com.aitech.weartak_civ.service.HeartrateService
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.LicenseManager.validateLicense
import com.aitech.weartak_civ.service.LocationService
import com.aitech.weartak_civ.service.TAKConnectService
import com.beust.klaxon.Klaxon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.checkerframework.checker.units.qual.C
import javax.net.ssl.SSLSocket


class MainActivity : ComponentActivity() {

    companion object Constants {
        val TAG = "WearTAK-" + MainActivity::class.java.simpleName
        lateinit var mainActivity : MainActivity
        lateinit var device_uid : String
        var callsign = "WEAROS-0"
        var enableLocationService : Boolean =  true
        var enableBiometricsService : Boolean = true
        var enableTAKConnectService : Boolean = true
        var enableCellularService: Boolean = true
        var isConnected : Boolean = true
        lateinit var dataRepository: DataRepository
        lateinit var networkManager : NetworkManager
        private val klaxon = Klaxon()
        final val DEFAULT_TEAMS_LIST = listOf("White", "Yellow", "Orange", "Magenta", "Red", "Maroon", "Purple", "Dark Blue", "Blue", "Cyan", "Teal", "Green", "Dark Green", "Brown")
        final val DEFAULT_ROLES_LIST = listOf("Team Member", "Team Lead", "HQ", "Sniper", "Medic", "Forward Observer", "RTO", "K9")
        var team = ""
        var role = ""
        var license = "AscentFreeLicense"
        var correctLicense = "AscentFreeLicense"
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
                    Log.d(TAG, "Don't have permissions to start body sensors in background. So NOT invoking requestLocationUpdates")
                } else {
                    Log.d(TAG, "Starting hearrate service")
                    mainActivity.startService(Intent(mainActivity, HeartrateService::class.java))
                }
            }  else {
                Log.d(TAG, "startBiometricsService is false. So NOT starting location service")
            }
        }
        private fun hasGps(): Boolean = mainActivity.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

        fun startLocationService() {
            if (hasGps()) {
                Log.i(TAG, "This hardware has GPS")
            } else {
                Log.i(TAG, "This hardware does NOT have GPS")
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
                        mainActivity.startForegroundService(
                            Intent(
                                mainActivity,
                                LocationService::class.java
                            )
                        )

                    } else {
                        mainActivity.startService(Intent(mainActivity, LocationService::class.java))
                    }
                    /*
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
                 */
                } else {
                    Log.d(TAG, "enableLocationService is false. So NOT starting location service")
                }
            }
        }

        fun startCotDispatchService() {
            Log.d(TAG, "Starting CotDispatchService")
            mainActivity.startService(Intent(mainActivity, CotDispatchService::class.java))
        }

    }

    var secureSocket : SSLSocket? = null
    val callsignState = MutableLiveData<String>("WEAROS-0")
    val licenseState = MutableLiveData<String>("")
    lateinit var healthServicesRepository : HealthServicesRepository
    lateinit var measureClient : MeasureClient

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsMap ->
            val deniedPermissions = permissionsMap.filterValues { !it }.keys

            if (deniedPermissions.isEmpty()) {
                Log.d(TAG, "All permissions granted. Refreshing services...")
                refreshPermissions()
            } else {
                Log.w(TAG, "Permissions denied: $deniedPermissions")
                // Optionally guide the user to settings if necessary
            }
        }

    private fun refreshPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Refreshing LocationService...")
            startLocationService()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Refreshing BiometricService...")
            startBiometericService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        mainActivity.stopService(Intent(mainActivity, CotDispatchService::class.java))
        mainActivity.stopService(Intent(mainActivity, BatteryService::class.java))
        if (enableBiometricsService) {
            mainActivity.stopService(Intent(mainActivity, HeartrateService::class.java))
        }
        if (enableLocationService) {
            mainActivity.stopService(Intent(mainActivity, LocationService::class.java))
        }
        mainActivity.stopService(Intent(mainActivity, TAKConnectService::class.java))
        CoroutineScope(Dispatchers.IO).launch {
            secureSocket?.close()
        }

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

    fun startBatteryService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mainActivity.startForegroundService(Intent(mainActivity, BatteryService::class.java))
        } else {
            mainActivity.startService(Intent(mainActivity, BatteryService::class.java))
        }
    }

    fun setDataFromDatastore() {
        runBlocking {
            callsign = dataRepository.getCallsign.first()
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

            Log.d(TAG, "Setting callsign: $callsign")
            Log.d(TAG, "Setting license: $license")

            enableLocationService = dataRepository.getEnableLocationService.first()
            Log.d(TAG, "Setting enableLocationService to $enableLocationService")
            enableBiometricsService = dataRepository.getEnableBiometricsService.first()
            Log.d(TAG, "Setting enableBiometricsService to $enableBiometricsService")
            LocationService.locationUpdateIntervalSecs = dataRepository.getLocationUpdateInterval.first()
            LocationService.locationUpdateFastestIntervalSecs = LocationService.locationUpdateIntervalSecs
            Log.d(TAG, "Setting locationUpdateIntervalSecs to ${LocationService.locationUpdateIntervalSecs} seconds")
            HeartrateService.heartrateSendIntervalSecs = dataRepository.getBiometricsSendInterval.first()
            Log.d(TAG, "Setting heartrateSendInterval to ${HeartrateService.heartrateSendIntervalSecs} seconds")
        }
    }

    fun readTeamsJson() {
        val inputStream = mainActivity.resources.openRawResource(R.raw.teams_json_config)
        try {
            var bytes  = inputStream.readAllBytes()
            teamEntriesValuesList = TeamsJson.fromJson(String(bytes, charset("UTF-8")))!!
            Log.d(TAG, "Team Entries List: ${teamEntriesValuesList?.ltEntries.toString()}")
            Log.d(TAG, "Team Values List: ${teamEntriesValuesList?.ltValues.toString()}")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR when reading Team json config", e)
            teamEntriesValuesList = TeamsJson(DEFAULT_TEAMS_LIST, DEFAULT_TEAMS_LIST)
        }
    }

    fun readRolesJson() {
        val inputStream = mainActivity.resources.openRawResource(R.raw.roles_json_config)
        try {
            var bytes  = inputStream.readAllBytes()
            roleEntriesValuesList = RolesJson.fromJson(String(bytes, charset("UTF-8")))!!
            Log.d(TAG, "Roles Entries List: ${roleEntriesValuesList?.lrEntries.toString()}")
            Log.d(TAG, "Roles Values List: ${roleEntriesValuesList?.lrValues.toString()}")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR when reading Role json config", e)
            roleEntriesValuesList = RolesJson(DEFAULT_ROLES_LIST, DEFAULT_ROLES_LIST)
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

    private fun startTAKConnectService() {
        mainActivity.startForegroundService(Intent(mainActivity, TAKConnectService::class.java))
    }


    private fun checkAndRequestPermissions() {
        val requiredPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS
        )

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG, "Requesting missing permissions: $missingPermissions")
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            Log.d(TAG, "All permissions already granted. Starting services...")
            //refreshPermissions()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainActivity = this
        dataRepository = DataRepository(this)
        networkManager = NetworkManager.getInstance()
        networkManager.init()
        readTeamsJson()
        readRolesJson()
        installSplashScreen()

        setDataFromDatastore()
        if (enableTAKConnectService) { startTAKConnectService() }
        device_uid = "WEAROS_" + Secure.getString(this.getContentResolver(), Secure.ANDROID_ID)

        setTheme(android.R.style.Theme_DeviceDefault)

        printSensorDetails(this)

        // check for permissions & start services if permissions are enabled.
        // Request all necessary permissions
        checkAndRequestPermissions()
        startHealthCapabilitiesCollection()
        startLocationService()
        startCotDispatchService()
        startBiometericService()
        startBatteryService()
        
        setContent {
            WearTAKCivApp(callsignState, licenseState)
        }
    }
}

//Contains functionality for all watch screens
@Composable
fun WearTAKCivApp(callsignLiveDataState: MutableLiveData<String>, licenseLiveDataState: MutableLiveData<String>) {
    val navController = rememberNavController()
    validateLicense(license)
    NavHost(
        navController,
        startDestination = if (isLicenseValid()) "main_screen" else "invalid_license_screen"
    ) {
        composable("main_screen",
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() }) {
            MainScreen(navController, callsignLiveDataState)
        }
        composable(
            "settings_screen",
            enterTransition = { slideInVertically() },
            exitTransition = { slideOutVertically() }) {
            SettingsScreen(navController, callsignLiveDataState, licenseLiveDataState)
        }
        composable(
            "callsign_screen",
            enterTransition = { slideInVertically() },
            exitTransition = { slideOutVertically() }) {
            CallsignSetting(navController, callsignSettingsState)
        }
        composable(
            "role_screen",
            enterTransition = { slideInVertically() },
            exitTransition = { slideOutVertically() }) {
            RoleSetting(navController)
        }
        composable(
            "team_screen",
            enterTransition = { slideInVertically() },
            exitTransition = { slideOutVertically() }) {
            TeamSetting(navController)
        }
        composable(
            "tak_server_screen",
            enterTransition = { slideInVertically() },
            exitTransition = { slideOutVertically() }) {
            TakServerSetting(navController)
        }
        composable(
            "new_server_screen",
            enterTransition = { slideInVertically() },
            exitTransition = { slideOutVertically() }) {
            NewServerPage(navController)
        }
        composable(
            "license_setting_screen",
            enterTransition = { slideInVertically() },
            exitTransition = { slideOutVertically() }) {
            LicenseKeyScreen(navController, licenseSettingsState)
        }
        composable(
            "invalid_license_screen",
            enterTransition = { slideInVertically() },
            exitTransition = { slideOutVertically() }) {
            InvalidLicenseScreen(navController)
        }
    }
}

//Contains functionality for main screen
@Composable
fun MainScreen(navController: NavController, callsignState: MutableLiveData<String>) {
    WearTAKCIVTheme {
        Box (
            modifier = Modifier
                .background(colors.background)
                .fillMaxSize()
                .padding(5.dp)
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
                    MarkerDropButton()
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
        var callsignTextFieldValue by remember { mutableStateOf(TextFieldValue(callsignLiveDataState.value ?: "null")) }
        Log.d(TAG, "callsignTextFieldValue=" + callsignTextFieldValue.text)
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
            Icon(imageVector = Icons.Default.Close, tint = Color.Red, contentDescription = "Close",
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        Log.d(TAG, "Settings NOT saved")
                        navController.navigate("settings_screen")
                    })
            Spacer(modifier = Modifier.width(12.dp))
            Icon(imageVector = Icons.Default.Check, tint = Color.Green, contentDescription = "Save",
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        callsignLiveDataState.postValue(callsignTextFieldValue.text)
                        Log.d(TAG, "Settings saved with callsign: ${callsignLiveDataState.value}")
                        navController.navigate("settings_screen")
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
            .padding(top = 20.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        val index : Int = findTeamIndex(teamSettingsState.value)
        var selectedRadioIndex by remember { mutableStateOf(index) }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp).padding(bottom = 40.dp).verticalScroll(state = rememberScrollState()),
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
        }
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            Icon(imageVector = Icons.Default.Close, tint = Color.Red, contentDescription = "Close",
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        Log.d(TAG, "Settings NOT saved")
                        navController.navigate("settings_screen")
                    })
            Spacer(modifier = Modifier.width(12.dp))
            Icon(imageVector = Icons.Default.Check, tint = Color.Green, contentDescription = "Save",
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        teamSettingsState.postValue(teamEntriesValuesList.ltEntries[selectedRadioIndex])
                        Log.d(TAG, "Settings saved with team: ${teamSettingsState.value}")
                        navController.navigate("settings_screen")
                    })
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
            .padding(top = 20.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        val index = findRoleIndex(roleSettingsState.value)
        var selectedRadioIndex by remember { mutableStateOf(index) }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp).padding(bottom = 40.dp).verticalScroll(state = rememberScrollState()),
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
        }
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            Icon(imageVector = Icons.Default.Close, tint = Color.Red, contentDescription = "Close",
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        Log.d(TAG, "Settings NOT saved")
                        navController.navigate("settings_screen")
                    })
            Spacer(modifier = Modifier.width(12.dp))
            Icon(imageVector = Icons.Default.Check, tint = Color.Green, contentDescription = "Save",
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        roleSettingsState.postValue(roleEntriesValuesList.lrEntries[selectedRadioIndex])
                        Log.d(TAG, "Settings saved with role: ${roleSettingsState.value}")
                        navController.navigate("settings_screen")
                    })
        }
    }
}

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
                Text(
                    text = "TAK Servers",
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                TakServerChip()
                TakServerChip()

                Spacer(modifier = Modifier.height(10.dp))

                Chip(
                    onClick = { navController.navigate("new_server_screen") },
                    enabled = true,
                    label = { Text(text = "Add New Server", fontSize = 13.sp) },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray,
                        contentColor = Color.White,
                        secondaryContentColor = Color.LightGray
                    ),
                    icon = { Icon(Icons.Filled.Add, contentDescription = "Localized description") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 3.dp)
                )
            }
            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                Button(
                    onClick = { navController.navigate("settings_screen") },
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

@Composable
fun TakServerChip(

) {
    var checked by remember { mutableStateOf(true) }
    SplitToggleChip(
        label = {
            Image(
                painter = painterResource(R.drawable.tak_server),
                contentDescription = "Top Left Icon",
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(22.dp)
                    .padding(end = 2.dp),
            )
                Text("Server", maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier
                    .align(Alignment.CenterVertically))
                },
        secondaryLabel = {
            Text("1122211111", maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        checked = checked,
        toggleControl = {
            Checkbox(
                checked = checked,
                enabled = true,
            )
        },
        onCheckedChange = { checked = it },
        onClick = {
            /* Do something */
        },
        enabled = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 3.dp)
    )
}

@Composable
fun NewServerPage (navController: NavController) {
    WearTAKCIVTheme {
        val shouldShowDialog = remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (shouldShowDialog.value) {
                DeleteServerDialog(shouldShowDialog = shouldShowDialog, navController)
            }
            Column(modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .align(Alignment.TopCenter)
                .padding(bottom = 20.dp)
                .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = /* add if statement for new server vs editing current server */ "Add New Server",
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                NewServerSettingText("Connection Name", callsignSettingsState)
                NewServerSettingText("Address/URL", callsignSettingsState)
                NewServerSettingText("Port", callsignSettingsState)
                NewServerSettingText("Username", callsignSettingsState)
                NewServerSettingText("Password", callsignSettingsState)

                // Change if to create button when settings are edited
                if (callsignSettingsState.value == "E2-FF1") {
                    Button(
                        onClick = { shouldShowDialog.value = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red,
                            contentColor = Color.Red
                        ),
                        modifier = Modifier.padding(top = 5.dp)
                    ) {
                        Text(
                            text = "Delete Server",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(5.dp)
                        )
                    }
                }
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                Icon(imageVector = Icons.Default.Close,
                    tint = Color.Red,
                    contentDescription = "Close",
                    modifier = Modifier
                        .size(40.dp)
                        .clickable {
                            Log.d(TAG, "Settings NOT saved")
                            navController.navigate("tak_server_screen")
                        })
                Spacer(modifier = Modifier.width(12.dp))
                Icon(imageVector = Icons.Default.Check,
                    tint = Color.Green,
                    contentDescription = "Save",
                    modifier = Modifier
                        .size(40.dp)
                        .clickable {
//                            callsignLiveDataState.postValue(callsignTextFieldValue.text)
//                            Log.d(
//                                TAG,
//                                "Settings saved with callsign: ${callsignLiveDataState.value}"
//                            )
                            navController.navigate("tak_server_screen")
                        })
            }
        }
    }
}

@Composable
fun NewServerSettingText (
    label: String,
    textState: MutableLiveData<String>,
) {
    var callsignTextFieldValue by remember { mutableStateOf(TextFieldValue(textState.value ?: "null")) }
//    Log.d(TAG, "callsignTextFieldValue=" + callsignTextFieldValue.text)
    OutlinedTextField(
        value = callsignTextFieldValue,
        onValueChange = {
            callsignTextFieldValue = it
        },
        maxLines = 1,
        label = { Text(text = label, color = colors.primary, fontSize = 12.sp) },
        textStyle = TextStyle(color = colors.primary),
        modifier = Modifier.padding(bottom = 5.dp)
    )
}

@Composable
fun DeleteServerDialog(shouldShowDialog: MutableState<Boolean>, navController: NavController) {
    if (shouldShowDialog.value) {
            AlertDialog(
                containerColor = colors.background,
                modifier = Modifier.fillMaxSize(),
                onDismissRequest = {
                    shouldShowDialog.value = false
                },
                icon = {
                    Icon(
                        Icons.Rounded.Delete,
                        modifier = Modifier.size(25.dp),
                        contentDescription = null,
                        tint = Color.Red
                    )
                },
                text = {
                    Text(
                        "Are you sure you want to delete this server?",
                        textAlign = TextAlign.Center
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            shouldShowDialog.value = false
                            navController.navigate("tak_server_screen")
                        },
                        modifier = Modifier.fillMaxWidth(0.45f),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red,
                        ),
                    ) {
                        Text(
                            text = "Confirm",
                        )
                    }
                },

                dismissButton = {
                    Button(
                        onClick = {
                            shouldShowDialog.value = false
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        var licenseTextFieldValue by remember { mutableStateOf(TextFieldValue(licenseLiveDataState.value ?: "null")) }

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
            Icon(imageVector = Icons.Default.Close, tint = Color.Red, contentDescription = "Close",
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        Log.d(TAG, "Settings NOT saved")
                        settingsChanged = false; licenseChanged = false
                        if (isLicenseValid()) {
                            navController.navigate("settings_screen")
                        } else {
                            navController.navigate("invalid_license_screen")
                        }
                    })
            Spacer(modifier = Modifier.width(12.dp))
            Icon(imageVector = Icons.Default.Check, tint = Color.Green, contentDescription = "Save",
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        Log.d(TAG, "licenseTextFieldValue=" + licenseTextFieldValue.text)
                        if (!licenseTextFieldValue.text.equals(licenseSettingsState.value)) {
                            settingsChanged = true; licenseChanged = true
                            license = licenseTextFieldValue.text ?: license
                            licenseSettingsState.postValue(license)
                            licenseLiveDataState.postValue(license)
                            validateLicense(license)
                            Log.d(TAG, "license stuff-boolean " + isLicenseValid())
                        }

                        if (settingsChanged) {
                            CoroutineScope(Dispatchers.Main).launch {
                                if (licenseChanged) {
                                    dataRepository.storeLicense(license)
                                }
                            }
                        }
                        if (isLicenseValid()) {
                            navController.navigate("settings_screen")
                        } else {
                            navController.navigate("invalid_license_screen")
                        }
                    })

            Log.d(TAG, "license stuff-license " + license)
            Log.d(TAG, "license stuff-setting " + licenseSettingsState.value)
            Log.d(TAG, "license stuff-live data " + licenseLiveDataState.value)
            Log.d(TAG, "license stuff-textfield " + licenseTextFieldValue.text)
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
                onClick = { navController.navigate("license_setting_screen") },
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
    //TODO: Add icons with slashes
    //TODO: Add values to function instead of one giant function
    //TODO: Connect cell

    val iconSizeSmall= 20.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp),
            contentAlignment = Alignment.TopCenter
    ) {
        var locationChecked by remember{ mutableStateOf(enableLocationService)}
        val battery by BatteryService.batteryPercentageLiveData.observeAsState()
        val takServer by TAKConnectService.isConnectedLiveData.observeAsState()
        val cellChecked by remember{ mutableStateOf(enableLocationService)}

        // Icon on the top left curve
        Image(
            painter = if (takServer == true) painterResource(R.drawable.tak_server) else painterResource(R.drawable.tak_server_disconnected),
            contentDescription = "Top Left Icon",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(
                    x = (40).dp, // Adjust this value based on the curve
                    y = (-10).dp // Adjust this value based on the curve
                )
                .size(iconSizeSmall) // Adjust icon size as needed
        )

//         Icon on the bottom left curve
        Icon(
            painterResource(R.drawable.cell), // Replace with your icon
            contentDescription = "Bottom Left Icon",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(
                    x = (15).dp, // Adjust this value
                    y = (15).dp // Adjust this value
                )
                .size(iconSizeSmall)
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
            tint = if (locationChecked) Color.White else Color.Gray
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
    }
}

@Composable
fun MarkerDropDialog(shouldShowDialog: MutableState<Boolean>, successBoolean : MutableState<Boolean>) {
    if (shouldShowDialog.value) {
        Dialog(onDismissRequest = { shouldShowDialog.value = false }) {
            LaunchedEffect(Unit) {
                delay(2000) // Delay for 5 seconds
                shouldShowDialog.value = false // Dismiss the dialog
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

                        Text(
                            text = "Marker Sent Successfully",
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Clear,
                            modifier = Modifier.size(25.dp),
                            contentDescription = null,
                            tint = Color.Red
                        )

                        Text(
                            text = "Error Sending Marker",
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
        onClick = { navController.navigate("settings_screen") },
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

@Composable
fun SOSButton() {
    Button(
        onClick = { /* Do something! */ },
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

@Composable
fun MarkerDropButton() {
    val shouldShowDialog = remember { mutableStateOf(false) }
    val successBoolean = remember {mutableStateOf(false)}

    if (shouldShowDialog.value) {
        MarkerDropDialog(shouldShowDialog = shouldShowDialog, successBoolean = successBoolean)
    }

    IconButton(
        onClick = { shouldShowDialog.value = true; successBoolean.value = true},
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        label = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis,) },
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
                enabled = enabled
            )
        },
        onCheckedChange = onCheckedChange,
        enabled = true,
    )
}


//Contains functionality for settings screen
@Composable
fun SettingsScreen(navController: NavController, callsignLiveDataState: MutableLiveData<String>, licenseLiveDataState: MutableLiveData<String>) {
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
            var locationUpdateInterval by remember{ mutableStateOf(TextFieldValue(LocationService.locationUpdateIntervalSecs.toString()))}
            var biometricsChecked by remember{ mutableStateOf(enableBiometricsService)}
            var biometricsSendingInterval by remember{ mutableStateOf(TextFieldValue(HeartrateService.heartrateSendIntervalSecs.toString()))}

            Column(modifier = Modifier
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

                CallsignRoleTeamChip("callsign_screen", "Callsign", callsignSettingsState, navController)
                CallsignRoleTeamChip("team_screen", "Team", teamSettingsState, navController)
                CallsignRoleTeamChip("role_screen", "Role", roleSettingsState, navController)

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "General Settings",
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                SettingSwitchToggle(checked = locationChecked, onCheckedChange =  { locationChecked = it },
                    title = "Location Service", description = "Service ON/OFF")
                if (locationChecked) {
                    OutlinedTextField(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        value = locationUpdateInterval,
                        onValueChange = {
                            locationUpdateInterval = it
                        },
                        label = {
                            Text(
                                text = "Location Update Interval",
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
                        textStyle = TextStyle(color = colors.primary)
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                }

                SettingSwitchToggle(checked = biometricsChecked, onCheckedChange =  { biometricsChecked = it },
                    title = "Biometrics",
                    description = "Service ON/OFF",)

                if (biometricsChecked) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        value = biometricsSendingInterval,
                        onValueChange = {
                            biometricsSendingInterval = it
                        },
                        label = {
                            Text(
                                text = "Biometrics Send Interval",
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
                        textStyle = TextStyle(color = colors.primary)
                    )
                }
                
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
                    label = { Text(text = "TAK Servers") },
                    secondaryLabel = { Text(text = "X" + " Connected") },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray,
                        contentColor = Color.White,
                        secondaryContentColor = Color.LightGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 3.dp)
                )

                CallsignRoleTeamChip("license_setting_screen", "License Key", licenseSettingsState, navController)

            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                Icon(imageVector = Icons.Default.Close, tint = Color.Red, contentDescription = "Close",
                    modifier = Modifier
                        .size(25.dp)
                        .clickable {
                            callsignSettingsState.postValue(callsign)
                            teamSettingsState.postValue(team)
                            roleSettingsState.postValue(role)
                            Log.d(TAG, "Settings NOT saved")
                            navController.navigate("main_screen")
                        })
                Spacer(modifier = Modifier.width(20.dp))
                Icon(imageVector = Icons.Default.Check, tint = Color.Green, contentDescription = "Save",
                    modifier = Modifier
                        .size(25.dp)
                        .clickable {
                            var settingsChanged: Boolean = false
                            var callsignChanged: Boolean = false
                            var teamChanged = false
                            var roleChanged = false
                            var licenseChanged: Boolean= false

                            var locationCheckedChanged: Boolean = false
                            var biometricsCheckedChanged: Boolean = false
                            var locationSendingIntervalChanged = false
                            var biometricsSendingIntervalChanged = false
                            if (!callsign.equals(callsignSettingsState.value)) {
                                settingsChanged = true; callsignChanged = true
                                callsign = callsignSettingsState.value ?: callsign
                                callsignLiveDataState.postValue(callsign)
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
                                teamState.postValue(team)
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
                                roleState.postValue(role)
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
                                    mainActivity.stopService(
                                        Intent(
                                            mainActivity,
                                            LocationService::class.java
                                        )
                                    )
                                }
                            }
                            val locationUpdateIntervalText = locationUpdateInterval.text
                            if (!locationUpdateIntervalText.equals("") && (locationUpdateIntervalText.toInt() != 0) && !locationUpdateIntervalText.equals(
                                    LocationService.locationUpdateIntervalSecs.toString()
                                )
                            ) {
                                settingsChanged = true; locationSendingIntervalChanged = true
                                LocationService.locationUpdateIntervalSecs =
                                    locationUpdateIntervalText.toInt()
                                LocationService.locationUpdateFastestIntervalSecs =
                                    LocationService.locationUpdateIntervalSecs
                                Log.d(
                                    TAG,
                                    "Setting locationUpdateIntervalSecs to ${LocationService.locationUpdateIntervalSecs}"
                                )
                                if (enableLocationService) {
                                    mainActivity.stopService(
                                        Intent(
                                            mainActivity,
                                            LocationService::class.java
                                        )
                                    )
                                    startLocationService()
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
                                    mainActivity.stopService(
                                        Intent(
                                            mainActivity,
                                            HeartrateService::class.java
                                        )
                                    )
                                }
                            }
                            val biometricsSendingIntervalText = biometricsSendingInterval.text
                            if (!biometricsSendingIntervalText.equals("") && (biometricsSendingIntervalText.toInt() != 0) && !biometricsSendingIntervalText.equals(
                                    HeartrateService.heartrateSendIntervalSecs.toString()
                                )
                            ) {
                                settingsChanged = true; biometricsSendingIntervalChanged = true
                                HeartrateService.heartrateSendIntervalSecs =
                                    biometricsSendingIntervalText.toInt()
                                Log.d(
                                    TAG,
                                    "Setting heartrateSendIntervalSecs to ${HeartrateService.heartrateSendIntervalSecs}"
                                )
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
                                    if (locationCheckedChanged) {
                                        Log.d(
                                            TAG,
                                            "Storing enableLocationService $enableLocationService"
                                        )
                                        dataRepository.storeEnableLocationService(
                                            enableLocationService
                                        )
                                    }
                                    if (locationSendingIntervalChanged) {
                                        Log.d(
                                            TAG,
                                            "Storing locationSendingInterval (${LocationService.locationUpdateIntervalSecs})"
                                        )
                                        dataRepository.storeLocationUpdateInterval(LocationService.locationUpdateIntervalSecs)
                                    }
                                    if (biometricsCheckedChanged) {
                                        Log.d(
                                            TAG,
                                            "Storing enableBiometricsService $enableBiometricsService"
                                        )
                                        dataRepository.storeEnableBiometricsService(
                                            enableBiometricsService
                                        )
                                    }
                                    if (biometricsSendingIntervalChanged) {
                                        Log.d(
                                            TAG,
                                            "Storing biometricsSendingInterval (${HeartrateService.heartrateSendIntervalSecs})"
                                        )
                                        dataRepository.storeBiometricsSendInterval(HeartrateService.heartrateSendIntervalSecs)
                                    }
                                    Log.d(TAG, "Finished saving settings")
                                }
                            } else {
                                Log.d(TAG, "Settings not changed and hence NOT saved")
                            }
                            navController.navigate("main_screen")
                        })
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
    CallsignSetting(rememberNavController(),callsignState)
}

@Preview(widthDp = 192, heightDp = 800)
@Composable
fun SettingsPreview() {
    var callsignState = MutableLiveData<String>("WEAROS-0")
    var licenseState = MutableLiveData<String>("AscentLicense")

    SettingsScreen(rememberNavController(),callsignState, licenseState)
}

@Preview(widthDp = 192, heightDp = 400, showBackground = true)
@Composable
fun TAKSettingsPreview() {
    TakServerSetting(rememberNavController())
}

@Preview(widthDp = 192, heightDp = 600, showBackground = true)
@Composable
fun NewServerPreview() {
    NewServerPage(rememberNavController())
}
@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun MarkerDropPreview() {
    val shouldShowDialog = remember { mutableStateOf(true) }
    val successBoolean = remember { mutableStateOf(false) }
    MarkerDropDialog(shouldShowDialog, successBoolean)
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun InvalidLicensePreview() {
    InvalidLicenseScreen(rememberNavController())
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun LicenseKeyPreview() {
    var licenseState = MutableLiveData<String>("License Key")
    LicenseKeyScreen(rememberNavController(), licenseState)
}
