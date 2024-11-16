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
import android.os.Bundle
import android.provider.Settings.Secure
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.HealthServicesRepository
import com.aitech.weartak_civ.data.latestHeartRate
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.BatteryMonitorService
import com.aitech.weartak_civ.service.BatteryMonitorService.Companion.batteryPercentage
import com.aitech.weartak_civ.service.LocationService
import com.aitech.weartak_civ.service.PassiveDataService
import com.aitech.weartak_civ.service.PassiveDataService.Companion
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


class MainActivity : ComponentActivity() {

    companion object Constants {
        val TAG = "WearTAK-" + MainActivity::class.java.simpleName
        val LOC_PART1 = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='"
        val LOC_PART2 = "' type='a-f-G-U-C' time='"
        val LOC_PART3 = "' how='m-g' access='Undefined'><point lat='"
        val LOC_PART4 = "' /><detail><contact endpoint='*:-1:stcp' phone='11234567890' callsign='"
        val LOC_PART5 = "'/><uid Droid='"
        val LOC_PART6 = "'/><__group role='"
        val LOC_PART7 = "'/><status battery='"
        val LOC_PART_8 = "'/><track course='"
        val LOC_PART_9 = "'/></detail></event>"
        val BIO_PART1 = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='"
        val BIO_PART2 = "' type='t-x-c-heartRate' time='"
        val BIO_PART3 = "' how='m-g' access='Undefined'><point lat='"
        val BIO_PART4 = "' /><detail><status serialNumber='"
        val BIO_PART5 = "'/></detail></event>"
        lateinit var mainActivity : MainActivity
        lateinit var device_uid : String
        var callsign = "WEAROS-0"

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
    private val dataRepository: DataRepository by lazy { DataRepository(this) }
    private val callsignState = MutableLiveData<String>("WEAROS-0")
    lateinit var healthServicesRepository : HealthServicesRepository
    lateinit var measureClient : MeasureClient

    private fun hasGps(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

    fun processBiometrics(secureSock: SSLSocket, callsign: String) {
        try {
            // Parse and manipulate timestamps
            dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
            val bioDateTime = Date()
            val bioTimeStr = dateTimeFormatter.format(bioDateTime)

            // Create a Calendar object and set it to the given date
            val calendar: Calendar = Calendar.getInstance()
            calendar.setTime(bioDateTime)

            // Add 120 seconds to the calendar
            calendar.add(Calendar.SECOND, 120)

            val bioDateTimeFuture = calendar.time
            val bioFutureTimeStr = dateTimeFormatter.format(bioDateTimeFuture)
            //Log.d(TAG, "Current biometrics time string: $bioTimeStr")

            // Construct CoT message
            var cotMsg: String = BIO_PART1 + device_uid + BIO_PART2 +
                    bioTimeStr + "' start='" + bioTimeStr + "' stale='" + bioFutureTimeStr + BIO_PART3
            if ((null != lat) && (null != lon)) {
                val haeActual = hae ?: "0.0"
                val ceActual = ce ?: "9999999.0"
                val leActual = le ?: "9999999.0"
                // TODO: Serial number should be unique. Hash android id to it
                cotMsg = cotMsg + lat + "' lon='" + lon + "' hae='" + java.lang.String.format(Locale.ENGLISH, "%.2f", haeActual) +
                        "' ce='" + java.lang.String.format(Locale.ENGLISH, "%.2f", ceActual) + "' le='" + java.lang.String.format(Locale.ENGLISH, "%.2f", leActual) +
                        BIO_PART4 + "SG6WOS" + callsign +
                        "' heartRate='79' " +
                        "callsign='" + callsign +
                        "' model='WEAROS" +
                        "' batteryLevel='$batteryPercentage" + BIO_PART5
            } else {
                cotMsg = cotMsg + "0.0' lon='0.0' hae='9999999.0' ce='9999999.0' le='9999999.0" +
                        BIO_PART4 + "SG6WOS" + callsign +
                        "' heartRate='79' " +
                        "callsign='" + callsign +
                        "' model='WEAROS" +
                        "' batteryLevel='$batteryPercentage" + BIO_PART5
            }

            // Send the CoT message
            Log.d(TAG,"Sending biometric COT msg: $cotMsg")
            val outputStream = secureSock.getOutputStream()
            outputStream.write(cotMsg.toByteArray(charset("UTF-8")))
            outputStream.flush()
        } catch (e: IOException) {
            e.printStackTrace()
            System.err.println("Error sending biometric COT message: " + e.message)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            System.err.println("Unexpected error sending biometric COT message: " + e.message)
        }
    }

    fun connectTAKServer(userCertP12ResourceId: Int, userCertP12Password: String, takServer: String) {
        Log.d(TAG,"connectTAKServer called")
        try {
            // Load client certificate
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(resources.openRawResource(userCertP12ResourceId), userCertP12Password.toCharArray())

            // Initialize the KeyManagerFactory with the client certificate
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, userCertP12Password.toCharArray())

            // Initialize SSL Context with TrustManager
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(keyManagerFactory.keyManagers, arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {
                    }

                    override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> {
                        return emptyArray()
                    }
                }),
                null
            )

            // Create the socket and connect
            val socketFactory = sslContext.socketFactory
            secureSocket = socketFactory.createSocket(takServer, 8089) as SSLSocket
            secureSocket!!.startHandshake()

            Log.d(TAG,"Successfully connected to $takServer on port 8089")
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Error connectTAKServer:", e)
        }

    }

    val locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            CoroutineScope(Dispatchers.IO).launch {
                //sendLocation(secureSocket!!, device_uid, callsign, "Team Member", "Orange", result)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        mainActivity.stopService(Intent(mainActivity, BatteryMonitorService::class.java))
        mainActivity.stopService(Intent(mainActivity, LocationService::class.java))
        CoroutineScope(Dispatchers.IO).launch {
            secureSocket?.close()
        }

        // Unregister the callback.
            runBlocking {
                measureClient.unregisterMeasureCallbackAsync(DataType.Companion.HEART_RATE_BPM, heartRateCallback)
            }
        lifecycleScope.launch {
            healthServicesRepository.unregisterForHeartRateData()
        }

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
        installSplashScreen()
        super.onCreate(savedInstanceState)
        mainActivity = this
        device_uid = "WEAROS_" + Secure.getString(this.getContentResolver(), Secure.ANDROID_ID)
        healthServicesRepository = HealthServicesRepository(this)
        lifecycleScope.launch {
            healthServicesRepository.registerForHeartRateData()
        }

        CoroutineScope(Dispatchers.Main).launch {
            dataRepository.getCallsign.collect { cs -> Log.d(TAG, "Getting and setting initial callsign: " + cs); callsign = cs; callsignState.postValue(cs); dataRepository.storeCallsign(cs);  }
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
            measureClient.registerMeasureCallback(DataType.Companion.HEART_RATE_BPM, heartRateCallback)
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
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toTypedArray(), 1,)
        }

        mainActivity.startService(Intent(mainActivity, BatteryMonitorService::class.java))

        if (hasGps()) {
            Log.i(TAG, "This hardware has GPS")
        } else {
            Log.i(TAG, "This hardware does NOT have GPS")
        }
        //fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TimeUnit.SECONDS.toMillis(2)).build()

        CoroutineScope(Dispatchers.IO).launch {
            /*
            connectTAKServer( R.raw.tak_il_ascent_user, "atakatak", "tak.il.ops.ascentitech.com")
            if (null != secureSocket) {
                val timer = Timer()
                timer.schedule(
                    object : TimerTask() {
                        override fun run() {
                            processBiometrics(secureSocket!!, callsign)
                        }
                    },0, 30000)
*/

                if ((ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) &&
                    (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
                    Log.d(TAG, "Don't have permissions. So NOT invoking requestLocationUpdates")
                } else {
                    Log.d(TAG, "Calling LocationService")
                   mainActivity.startService(Intent(mainActivity, LocationService::class.java))
                    /*
                    fusedLocationClient.requestLocationUpdates(
                        locationRequest,
                        locationCallback,
                        Looper.getMainLooper()
                    )

                     */
                }
        }
        setContent {
            WearTAKCivApp(callsignState)
        }
    }
}

@Composable
fun WearTAKCivApp(greetingNameState: LiveData<String>) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "main_screen") {
        composable("main_screen",
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() }) {
            MainScreen(navController, greetingNameState)
        }
        composable("settings_screen",
            enterTransition = { slideInVertically() },
            exitTransition = { slideOutVertically() }) {
            SettingsScreen(navController)
        }
    }
}

@Composable
fun MainScreen(navController: NavController, greetingNameState: LiveData<String>) {
    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            Greeting(greetingNameState = greetingNameState)
            Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings",
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.BottomCenter)
                    .clickable {
                        navController.navigate("settings_screen")
                    })
        }
    }
}

@Composable
fun Greeting(greetingNameState: LiveData<String>) {
    val greetingName: String? by greetingNameState.observeAsState()
    Text(
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.primary,
        text = "From WearTAK-CIV, Hello ${greetingName}!"
    )
}

@Composable
fun SettingsScreen(navController: NavController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        TimeText()
        Text(
            text = "This is the settings screen",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.primary,
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Done",
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        navController.navigate("main_screen")
                    })
            Spacer(modifier = Modifier.width(12.dp))
            Icon(imageVector = Icons.Default.Check, contentDescription = "Done",
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        navController.navigate("main_screen")
                    })
        }
    }
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    var callsignState = MutableLiveData<String>("Preview Android!")
    WearTAKCivApp(callsignState)
}
