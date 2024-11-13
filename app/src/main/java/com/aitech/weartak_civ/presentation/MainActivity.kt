/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter and
 * https://github.com/android/wear-os-samples/tree/main/ComposeAdvanced to find the most up to date
 * changes to the libraries and their usages.
 */

package com.aitech.weartak_civ.presentation

import android.os.Bundle
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.concurrent.futures.await
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.health.services.client.HealthServices
import androidx.health.services.client.awaitWithException
import androidx.health.services.client.data.DataType
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
import com.aitech.weartak_civ.presentation.MainActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MainActivity.Constants.BIO_PART1
import com.aitech.weartak_civ.presentation.MainActivity.Constants.BIO_PART2
import com.aitech.weartak_civ.presentation.MainActivity.Constants.BIO_PART3
import com.aitech.weartak_civ.presentation.MainActivity.Constants.BIO_PART4
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.Timer
import java.util.TimerTask
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


class MainActivity : ComponentActivity() {

    object Constants {
        val TAG = "WearTAK-CIV"
        val BIO_PART1 = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='UID_GALAXY_WEAROS_"
        val BIO_PART2 = "' type='t-x-c-heartRate' time='"
        val BIO_PART3 = "' how='m-g' access='Undefined'><point lat='0.0' lon='0.0' hae='9999999.0' ce='9999999.0' le='9999999.0' /><detail><status serialNumber='"
        val BIO_PART4 = "'/></detail></event>"
    }

    fun sendLocation(secureSock: Socket, uid: String, callsign: String, role: String, teamColor: String, lat: String, lon: String, hae: String, ce: String) {
        try {

        } catch (e: IOException) {
            e.printStackTrace()
            System.err.println("Error sending COT message: " + e.message)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            System.err.println("Unexpected error: " + e.message)
        }
    }

    fun processBiometrics(secureSock: Socket) {
        try {
            // Parse and manipulate timestamps
            val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
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
            val callsign_id = 1
            Log.d(TAG, "current time string: $bioTimeStr")

            // Construct CoT message
            val cotMsg: String = ((BIO_PART1 + callsign_id + BIO_PART2).toString() +
                    bioTimeStr + "' start='" + bioTimeStr + "' stale='" + bioFutureTimeStr +
                    BIO_PART3).toString() + "SG6WOS" + callsign_id +
                    "' heartRate='79' " +
                    "callsign='SAMSUNG_GALAXY_6_WEAROS-" + callsign_id +
                    "' model='SAMSUNG_GALAXY_6_WEAROS" +
                    "' batteryLevel='98" + BIO_PART4

            // Send the CoT message
            Log.d(TAG,"Sending COT msg: $cotMsg")
            val outputStream = secureSock.getOutputStream()
            outputStream.write(cotMsg.toByteArray(charset("UTF-8")))
            outputStream.flush()
        } catch (e: IOException) {
            e.printStackTrace()
            System.err.println("Error sending COT message: " + e.message)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            System.err.println("Unexpected error: " + e.message)
        }
    }
    fun connectTAKServer(userCertP12ResourceId: Int, userCertP12Password: String, takServer: String): SSLSocket? {
        var secureSocket: SSLSocket? = null
        Log.d("WearTAK-CIV","connectTAKServer called")
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

            Log.d("WearTAK-CIV","Successfully connected to $takServer on port 8089")
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("WearTAK-CIV", "Error connectTAKServer:", e)
        }

        return secureSocket
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        val healthClient = HealthServices.getClient(this /*context*/)
        val passiveMonitoringClient = healthClient.passiveMonitoringClient
        lifecycleScope.launchWhenCreated {
            val capabilities = passiveMonitoringClient.getCapabilitiesAsync()
            capabilities.awaitWithException()
            capabilities.await()
            // Supported types for passive data collection
            val passiveMonitoringDataTypes = capabilities.get().supportedDataTypesPassiveMonitoring
            Log.d("WearTAK-CIV", "passiveMonitoringDataTypes: " + passiveMonitoringDataTypes.toString())
            val supportsHeartRate = DataType.HEART_RATE_BPM in passiveMonitoringDataTypes
            val supportsVo2Max = DataType.VO2_MAX in passiveMonitoringDataTypes
            val supportsLocation = DataType.LOCATION in passiveMonitoringDataTypes
            Log.d("WearTAK-CIV", "supportsHeartRate: " + supportsHeartRate + "; supportsVo2Max: " + supportsVo2Max + "; supportsLocation: " + supportsLocation)
        }
        setContent {
            WearTAKCivApp("User")
        }
        CoroutineScope(Dispatchers.IO).launch {
            val secureSocket = connectTAKServer( R.raw.tak_prod_ascent_user, "atakatak", "tak.prod.ops.ascentitech.com")
            if (null != secureSocket) {
                val timer = Timer()
                timer.schedule(
                    object : TimerTask() {
                        override fun run() {
                            processBiometrics(secureSocket)
                        }
                    },0, 60000)

            }
        };
    }
}

@Composable
fun WearTAKCivApp(greetingName: String) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "main_screen") {
        composable("main_screen",
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() }) {
            MainScreen(navController, greetingName)
        }
        composable("settings_screen",
            enterTransition = { slideInVertically() },
            exitTransition = { slideOutVertically() }) {
            SettingsScreen(navController)
        }
    }
}

@Composable
fun MainScreen(navController: NavController, greetingName: String) {
    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            Greeting(greetingName = greetingName)
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
fun Greeting(greetingName: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.primary,
        text = stringResource(R.string.hello_world, greetingName)
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
    WearTAKCivApp("Preview Android")
}