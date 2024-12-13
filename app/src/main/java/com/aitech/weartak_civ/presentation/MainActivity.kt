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
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings.Secure
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.concurrent.futures.await
import androidx.core.app.ActivityCompat
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
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ContentAlpha
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.SelectableChip
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.HealthServicesRepository
import com.aitech.weartak_civ.data.latestHeartRate
import com.aitech.weartak_civ.presentation.MainActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsignSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBiometricsService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startBiometericService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.team
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamState
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.BatteryService
import com.aitech.weartak_civ.service.HeartrateService
import com.aitech.weartak_civ.service.LocationService
import com.aitech.weartak_civ.service.TAKConnectService
import com.beust.klaxon.Klaxon
import com.beust.klaxon.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.net.ssl.SSLSocket


class MainActivity : ComponentActivity() {

    companion object Constants {
        val TAG = "WearTAK-" + MainActivity::class.java.simpleName
        lateinit var mainActivity : MainActivity
        lateinit var device_uid : String
        var callsign = "WEAROS-0"
        var enableLocationService : Boolean =  true
        var enableBiometricsService : Boolean = true
        var enableCellularService: Boolean = true
        var isConnected : Boolean = true
        lateinit var dataRepository: DataRepository
        lateinit var networkManager : NetworkManager
        private val klaxon = Klaxon()
        final val DEFAULT_TEAMS_LIST = listOf("Yellow", "Orange", "Magenta", "Red", "Maroon", "Purple", "Dark Blue", "Blue", "Cyan", "Teal", "Green", "Dark Green", "Brown")
        final val DEFAULT_ROLES_LIST = listOf("Team Member", "Team Lead", "HQ", "Sniper", "Medic", "Forward Observer", "RTO", "K9")
        var team = ""
        var role = ""
        lateinit var teamEntriesValuesList : TeamsJson
        lateinit var roleEntriesValuesList : RolesJson
        var callsignSettingsState = MutableLiveData<String>("WEAROS-0")
        val teamState = MutableLiveData<String>("")
        val teamSettingsState = MutableLiveData<String>("")
        val roleState = MutableLiveData<String>("")
        val roleSettingsState = MutableLiveData<String>("")

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

    }
    var secureSocket : SSLSocket? = null
    val callsignState = MutableLiveData<String>("WEAROS-0")
    lateinit var healthServicesRepository : HealthServicesRepository
    lateinit var measureClient : MeasureClient

    override fun onDestroy() {
        super.onDestroy()
        
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

    val heartRateCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            Log.d(TAG, "onAvailabilityChanged called w/ availability: $availability")
            if (availability is DataTypeAvailability) {
                // Handle availability change.
            }
        }

        override fun onDataReceived(dataPoints: DataPointContainer) {
            // Inspect data points.
            var latestHeartRate: Double? = null
            Log.d(TAG, "onDataReceived")
            runBlocking {
                dataPoints.getData(DataType.HEART_RATE_BPM).latestHeartRate()?.let {
                    //repository.storeLatestHeartRate(it)
                    latestHeartRate = it
                }
            }
            Log.d(TAG, "onDataReceived: hr: $latestHeartRate")
        }
    }

    fun printSensorDetails(context: Context?) {
        var resSensorList: MutableList<Sensor> = ArrayList()
        try {
            val smm = context?.getSystemService(Context.SENSOR_SERVICE) as SensorManager
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
            // Needed on initial install of app where the teams json file does not match with DEFAULT_TEAMS_LIST
            if (teamEntriesValuesList.ltEntries.indexOf(team) == -1) {
                team = teamEntriesValuesList.ltEntries[0]
            }
            teamState.postValue(team)
            teamSettingsState.postValue(team)
            role = dataRepository.getRole.first()
            // Needed on initial install of app where the roles json file does not match with DEFAULT_ROLES_LIST
            if (roleEntriesValuesList.lrEntries.indexOf(role) == -1) {
                role = roleEntriesValuesList.lrEntries[0]
            }
            roleState.postValue(role)
            roleSettingsState.postValue(role)
            Log.d(TAG, "Setting callsign: $callsign")
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mainActivity.startForegroundService(Intent(mainActivity, TAKConnectService::class.java))

        } else {
            mainActivity.startService(Intent(mainActivity, TAKConnectService::class.java))
        }
        device_uid = "WEAROS_" + Secure.getString(this.getContentResolver(), Secure.ANDROID_ID)
        /*
        healthServicesRepository = HealthServicesRepository(this)
        lifecycleScope.launch {
            healthServicesRepository.registerForHeartRateData()
        }

         */

        setTheme(android.R.style.Theme_DeviceDefault)

        printSensorDetails(this)

        val healthClient = HealthServices.getClient(this /*context*/)
        val passiveMonitoringClient = healthClient.passiveMonitoringClient
        measureClient = healthClient.measureClient
        lifecycleScope.launch {
            val capabilities = passiveMonitoringClient.getCapabilitiesAsync()
            capabilities.awaitWithException()
            capabilities.await()
            // Supported types for passive data collection
            val passiveMonitoringDataTypes = capabilities.get().supportedDataTypesPassiveMonitoring
            Log.d(TAG, "passiveMonitoringDataTypes: " + passiveMonitoringDataTypes.toString())
            val supportsHeartRate = DataType.HEART_RATE_BPM in passiveMonitoringDataTypes
            val supportsVo2Max = DataType.VO2_MAX in passiveMonitoringDataTypes
            val supportsLocation = DataType.LOCATION in passiveMonitoringDataTypes
            Log.d(TAG, "supportsHeartRate: " + supportsHeartRate + "; supportsVo2Max: " + supportsVo2Max + "; supportsLocation: " + supportsLocation)
            val measureCapabilities = measureClient.getCapabilitiesAsync().await()
            val measureSupportsHeartRate = DataType.HEART_RATE_BPM in measureCapabilities.supportedDataTypesMeasure
            Log.d(TAG, "measureCapabilities: $measureCapabilities")
            Log.d(TAG, "measureSupportsHeartRate: " + measureSupportsHeartRate)
            //measureClient.registerMeasureCallback(DataType.Companion.HEART_RATE_BPM, heartRateCallback)
        }
        val listPermissionsNeeded: MutableList<String> = ArrayList()
        if ((ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) &&
            (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            // TODO: Call ActivityCompat#requestPermissions to request the missing permissions,
            // and then override:
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants/deny the permission.
            listPermissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            listPermissionsNeeded.add(Manifest.permission.BODY_SENSORS)
        }
        if (listPermissionsNeeded.size > 0) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toTypedArray(), 1)
        }

        startBatteryService()
        startBiometericService()
        startLocationService()
        setContent {
            WearTAKCivApp(callsignState)
        }
    }
}

//Contains functionality for all watch screens
@Composable
fun WearTAKCivApp(callsignLiveDataState: MutableLiveData<String>) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "main_screen") {
        composable("main_screen",
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() }) {
            MainScreen(navController, callsignLiveDataState)
        }
        composable(
            "settings_screen",
            enterTransition = { slideInVertically() },
            exitTransition = { slideOutVertically() }) {
            SettingsScreen(navController, callsignLiveDataState)
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
    }
}

//Contains functionality for main screen
@Composable
fun MainScreen(navController: NavController, callsignState: MutableLiveData<String>) {
    WearTAKCIVTheme {
        Box (
            modifier = Modifier
                .background(MaterialTheme.colors.background)
                .fillMaxSize()
                .padding(5.dp)
        ) {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colors.background)
                    .padding(5.dp),
        //                horizontalAlignment = Alignment.CenterHorizontally,
        //                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier
                        .padding(top = 35.dp)
                        .fillMaxWidth()
                ) {
                    HeartRate()
                }

                Row(
                    modifier = Modifier
                        .padding(vertical = 5.dp)
                        .fillMaxWidth(),
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
                        .fillMaxWidth(),
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
            .background(MaterialTheme.colors.background)
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
            textStyle = TextStyle(color = MaterialTheme.colors.primary)
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
            .background(MaterialTheme.colors.background)
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
            .background(MaterialTheme.colors.background)
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
fun HeartRate () {
    val heartRateText by HeartrateService.hrStringLiveData.observeAsState()
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
            imageVector = Icons.Default.LocationOn, // Replace with your icon
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
fun Callsign(navController: NavController, callsignState: MutableLiveData<String>) {
    val greetingName: String? by callsignState.observeAsState()
    AssistChip(
        onClick = { navController.navigate("settings_screen") },
        label = { Text(
            "${greetingName}",
            fontSize = 15.sp,
            ) },
        shape = RoundedCornerShape(40.dp)
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
            .size(100.dp, 60.dp),
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
    IconButton(
        onClick = { /* doSomething() */ },
        modifier = Modifier
            .border(
                width = 2.dp,
                color = Color.White,
                shape = CircleShape
            )
            .size(60.dp),
    ) {
        Icon(
            painterResource(R.drawable.markerdrop),
            contentDescription = "Localized description",
            modifier = Modifier.size(30.dp)
        )
    }
}

@Composable
private fun SettingSwitchItem(
    modifier: Modifier = Modifier,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String,
    enabled: Boolean = true,
) {

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1.0f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val contentAlpha = if (enabled) ContentAlpha.high else ContentAlpha.disabled

            Text(
                text = title,
                style = MaterialTheme.typography.caption1,
                maxLines = 1,
                modifier = Modifier.alpha(contentAlpha)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.caption3,
                modifier = Modifier.alpha(contentAlpha)
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled
        )
    }
}

//Contains functionality for settings screen
@Composable
fun SettingsScreen(navController: NavController, callsignLiveDataState: MutableLiveData<String>) {
    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
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

                SettingSwitchItem(checked = locationChecked, onCheckedChange =  { locationChecked = it },
                    title = "Location Service", description = "Service ON/OFF")
                OutlinedTextField(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    value = locationUpdateInterval,
                    onValueChange = {
                        locationUpdateInterval = it
                    },
                    label = { Text(text = "Location Update Interval", color = colors.primary, style = MaterialTheme.typography.caption3) },
                    placeholder = { Text(text = "Interval in secs", color = colors.primary, style = MaterialTheme.typography.caption3) },
                    textStyle = TextStyle(color = MaterialTheme.colors.primary)
                )
                SettingSwitchItem(checked = biometricsChecked, onCheckedChange =  { biometricsChecked = it },
                    title = "Biometrics",
                    description = "Service ON/OFF",)
                OutlinedTextField(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    value = biometricsSendingInterval,
                    onValueChange = {
                        biometricsSendingInterval = it
                    },
                    label = { Text(text = "Biometrics Send Interval", color = colors.primary, style = MaterialTheme.typography.caption3) },
                    placeholder = { Text(text = "Interval in secs", color = colors.primary, style = MaterialTheme.typography.caption3) },
                    textStyle = TextStyle(color = MaterialTheme.colors.primary)
                )
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
                                teamState.postValue(team)
                            }
                            if (!role.equals(roleSettingsState.value)) {
                                settingsChanged = true; roleChanged = true
                                role = roleSettingsState.value ?: role
                                roleState.postValue(role)
                            }
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
    WearTAKCivApp(callsignState)
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun CallsignSettingsPreview() {
    var callsignState = MutableLiveData<String>("WEAROS-0")
    CallsignSetting(rememberNavController(),callsignState)
}

@Preview(widthDp = 192, heightDp = 600)
@Composable
fun SettingsPreview() {
    var callsignState = MutableLiveData<String>("WEAROS-0")
    SettingsScreen(rememberNavController(),callsignState)
}

@Preview(widthDp = 192, heightDp = 500)
@Composable
fun ChipPreview() {
   RoleSetting(rememberNavController())
}
