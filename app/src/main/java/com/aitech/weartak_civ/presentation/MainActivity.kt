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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings.Secure
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
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
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.HealthServicesRepository
import com.aitech.weartak_civ.data.latestHeartRate
import com.aitech.weartak_civ.presentation.MainActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.BatteryService
import com.aitech.weartak_civ.service.HeartrateService
import com.aitech.weartak_civ.service.LocationService
import com.aitech.weartak_civ.service.TAKConnectService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import javax.net.ssl.SSLSocket


class MainActivity : ComponentActivity() {

    companion object Constants {
        val TAG = "WearTAK-" + MainActivity::class.java.simpleName
        lateinit var mainActivity : MainActivity
        lateinit var device_uid : String
        var callsign = "WEAROS-0"
        lateinit var dataRepository: DataRepository

        fun getActivity(): MainActivity {
            return mainActivity
        }
    }
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    //private val callsign_id = 1
    //private val callsign = "WEAROS-" + callsign_id
    //val device_uid by lazy { "WEAROS_" + Secure.getString(this.getContentResolver(), Secure.ANDROID_ID) }
    val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    var lat : Double? = null
    var lon : Double? = null
    var hae : Double? = null
    var ce : Float? = null
    var le : Float? = null
    var speed : Float? = null
    var bearing : Float? = null
    var secureSocket : SSLSocket? = null
    val callsignState = MutableLiveData<String>("WEAROS-0")
    lateinit var healthServicesRepository : HealthServicesRepository
    lateinit var measureClient : MeasureClient

    private fun hasGps(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

    override fun onDestroy() {
        super.onDestroy()

        mainActivity.stopService(Intent(mainActivity, BatteryService::class.java))
        mainActivity.stopService(Intent(mainActivity, HeartrateService::class.java))
        mainActivity.stopService(Intent(mainActivity, LocationService::class.java))
        mainActivity.stopService(Intent(mainActivity, TAKConnectService::class.java))
        CoroutineScope(Dispatchers.IO).launch {
            secureSocket?.close()
        }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        mainActivity = this
        dataRepository = DataRepository(this)
        installSplashScreen()
        super.onCreate(savedInstanceState)
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
        CoroutineScope(Dispatchers.Main).launch {
            dataRepository.getCallsign.collect { cs -> callsign = cs; callsignState.postValue(cs); dataRepository.storeCallsign(cs); }
            //dataRepository.getCallsign.collect { callsign -> Log.d(TAG, "set callsign: " + callsign) }
        }
        setTheme(android.R.style.Theme_DeviceDefault)

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mainActivity.startForegroundService(Intent(mainActivity, BatteryService::class.java))

        } else {
            mainActivity.startService(Intent(mainActivity, BatteryService::class.java))
        }
        mainActivity.startService(Intent(mainActivity, HeartrateService::class.java))

        if (hasGps()) {
            Log.i(TAG, "This hardware has GPS")
        } else {
            Log.i(TAG, "This hardware does NOT have GPS")
        }
        //fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        //locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TimeUnit.SECONDS.toMillis(2)).build()

        /*
        connectTAKServer( R.raw.tak_prod_ascent_user, "atakatak", "tak.prod.ops.ascentitech.com")
        if (null != secureSocket) {
            val timer = Timer()
            timer.schedule(
                object : TimerTask() {
                    override fun run() {
                        processBiometrics(secureSocket!!, callsign)
                    }
                },0, 30000)
*/

        if ((ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) &&
            (ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED)
        ) {
            Log.d(TAG, "Don't have permissions. So NOT invoking requestLocationUpdates")
        } else {
            Log.d(TAG, "Calling LocationService")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mainActivity.startForegroundService(Intent(mainActivity, LocationService::class.java))

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
        }
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
                .padding(2.dp)
        ) {

            Column(
                modifier = Modifier
                    .background(MaterialTheme.colors.background)
                    .padding(10.dp),
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
                        .padding(vertical = 10.dp)
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
fun HeartRate () {
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
                text = " 100",
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
    val iconSizeSmall= 20.dp
    val iconSizeLarge = 17.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp),
            contentAlignment = Alignment.TopCenter
    ) {
        // Icon on the top left curve
        Image(
            painterResource(R.drawable.tak_server), // Replace with your icon
            contentDescription = "Top Left Icon",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(
                    x = (40).dp, // Adjust this value based on the curve
                    y = (-10).dp // Adjust this value based on the curve
                )
                .size(iconSizeSmall) // Adjust icon size as needed
        )

        // Icon on the bottom left curve
        Icon(
            painterResource(R.drawable.cell), // Replace with your icon
            contentDescription = "Bottom Left Icon",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(
                    x = (15).dp, // Adjust this value
                    y = (15).dp // Adjust this value
                )
                .size(iconSizeLarge)
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
                .size(iconSizeSmall)
        )

        // Icon on the bottom right curve
        Icon(
            painterResource(R.drawable.battery), // Replace with your icon
            contentDescription = "Bottom Right Icon",
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(
                    x = (-15).dp, // Adjust this value
                    y = (15).dp // Adjust this value
                )
                .size(iconSizeLarge)
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

//Contains functionality for settings screen
@Composable
fun SettingsScreen(navController: NavController, callsignLiveDataState: MutableLiveData<String>) {
    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(bottom = 16.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            /*
        Text(
            text = "This is the settings screen",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.primary,
        )
         */
            var callsignTextFieldValue by remember { mutableStateOf(TextFieldValue(callsign)) }
            OutlinedTextField(
                value = callsignTextFieldValue,
                onValueChange = {
                    callsignTextFieldValue = it
                },
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
                            navController.navigate("main_screen")
                        })
                Spacer(modifier = Modifier.width(12.dp))
                Icon(imageVector = Icons.Default.Check, tint = Color.Green, contentDescription = "Save",
                    modifier = Modifier
                        .size(40.dp)
                        .clickable {
                            callsign = callsignTextFieldValue.text
                            callsignLiveDataState.postValue(callsign)
                            CoroutineScope(Dispatchers.Main).launch {
                                dataRepository.storeCallsign(callsign)
                            }
                            Log.d(TAG, "Settings saved with callsign: $callsign")
                            navController.navigate("main_screen")
                        })
            }
        }
    }
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true, showBackground = true)
@Composable
fun DefaultPreview() {
    var callsignState = MutableLiveData<String>("Scooby Doo!")
    WearTAKCivApp(callsignState)
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun SettingsPreview() {
    var callsignState = MutableLiveData<String>("Preview Android!")
    WearTAKCivApp(callsignState)
    SettingsScreen(rememberNavController(),callsignState)
}
