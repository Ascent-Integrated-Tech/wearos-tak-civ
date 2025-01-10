package com.aitech.weartak_civ.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.HardwarePropertiesManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.service.BatteryService.Companion.batteryPercentage
import com.aitech.weartak_civ.service.LocationService.Companion.ce
import com.aitech.weartak_civ.service.LocationService.Companion.hae
import com.aitech.weartak_civ.service.LocationService.Companion.lat
import com.aitech.weartak_civ.service.LocationService.Companion.lon
import com.aitech.weartak_civ.service.LocationService.Companion.le
import com.aitech.weartak_civ.service.LocationService.Companion.speed
import com.aitech.weartak_civ.service.LocationService.Companion.bearing
import com.aitech.weartak_civ.service.TAKConnectService.Companion.sendDataTAKServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone


class CotDispatchService : Service() {
    private val TAG = "WearTAK-" + CotDispatchService::class.java.simpleName
    private var cotDispatchHandler: Handler? = null
    private var cotDispatchThread: HandlerThread? = null
    private val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")


    // Acquire DataRepository from a global or singleton reference
    private lateinit var dataRepository: DataRepository

    //
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "CotDispatchService onStartCommand()")

        startForegroundService()
        startDispatchScheduler()

        return START_NOT_STICKY
    }

    // starting requires notification to the user
    private fun startForegroundService() {
        Log.d(TAG, "Starting $TAG as a ForegroundService")
        val channelId = createNotificationChannel()
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentText("WearTAK-CIV Cot Dispatch service running")
            .setPriority(Context.BIND_WAIVE_PRIORITY)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        try {
            startForeground(COT_DISPATCH_SERVICE_NOTIFICATION_ID, notification)
        } catch (e: NullPointerException) {
            Log.e(TAG, "Failed to set CotDispatcher ForegroundService",e)
        }
    }

    private fun createNotificationChannel(): String {
        val channelId = "Cot_Dispatch_Service"
        val channelName = "Cot Dispatch Background Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.GRAY
        chan.importance = NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    override fun onCreate() {
        super.onCreate()
        dataRepository = MainActivity.dataRepository  // or another injection approach
        Log.d(TAG, "CotDispatchService onCreate()")
    }

    private fun startDispatchScheduler() {
        Log.d(TAG, "Starting the dispatch scheduler")
        cotDispatchThread = HandlerThread("CotDispatchThread")
        cotDispatchThread?.start()
        cotDispatchHandler = cotDispatchThread?.looper?.let { Handler(it) }

        // Schedule the first run
        cotDispatchHandler?.postDelayed(cotDispatchRunnable, DISPATCH_INTERVAL)
    }

    private val cotDispatchRunnable = object : Runnable {
        override fun run() {
            CoroutineScope(Dispatchers.IO).launch {

                val location = dataRepository.latestLocation.value
                var heartRate = dataRepository.latestHeartRate.value
                val battery = dataRepository.latestBatteryPercentage.value

                if (location != null && battery != null) {
                    if (heartRate == null) {
                        heartRate = -1
                    }
                    val cotMessage = buildCotMessage(location, heartRate, battery)
                    sendDataTAKServer(cotMessage)
//                    Log.d(TAG, "CoT message sent: $cotMessage")
                } else {
                    Log.w(TAG, "Missing data for CoT message. Not sending.")
                }
            }

            // Schedule the next run
            cotDispatchHandler?.postDelayed(this, locationUpdateIntervalSecs * 1000L)
        }
    }


    private fun buildCotMessage(location: Location, heartRate: Int, battery: Int): String {

        dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        val calendar: Calendar = Calendar.getInstance()

        // Set the current time
        calendar.setTime(Date())

        // Format and set the startTime with the currentTime
        val startTime = dateTimeFormatter.format(calendar.time)

        // add 3* Update interval seconds to the current time for 'stale time', ie when the cot expires
        calendar.add(Calendar.SECOND, 3*locationUpdateIntervalSecs)
        val staleTime = dateTimeFormatter.format(calendar.time)

        // TODO: Check if these really don't need the null safety check. If not, direct injection into the cot XML is more readable.
        val latActual = location?.latitude ?: "0.00000"
        val lonActual = location?.longitude ?: "0.00000"
        val haeActual = location?.altitude ?: "0.0"
        val ceActual = location?.accuracy ?: "0.0"
        val leActual = if (location?.verticalAccuracyMeters?.isNaN() == false) location.verticalAccuracyMeters else "Unknown"
        val bearingActual = location?.bearing ?: "0.0"
        val speedActual = location?.speed ?: "0.0"

        val uid = MainActivity.device_uid

        return """
        <?xml version="1.0" encoding="UTF-8" standalone='yes'?>
        <event version="2.0" uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g' access='Undefined'>
            <point lat='$latActual' lon='$lonActual' hae='$haeActual' ce='$ceActual' le='$leActual' />
            <detail>
                <contact endpoint='*:-1:stcp' phone='11234567890' callsign='$callsign'/>
                <__group role='$roleValue' name='$teamValue'/>
                <biometrics>
                    <device>
                        <model>WEAROS</model>
                        <uid>$uid</uid>
                        <hr>$heartRate</hr>
                        <bat>$battery</bat>
                    </device>
                </biometrics>
                <track course='$bearingActual' speed='$speedActual'/>
            </detail>
        </event>
        """.trimIndent()

        /*
                return """
                    <?xml version="1.0" encoding="UTF-8" standalone='yes'?>
                    <event version="2.0" uid='${MainActivity.device_uid}' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g' access='Undefined'>
                        <point lat='${location.latitude}' lon='${location.longitude}' hae='${location.altitude}' ce='${location.accuracy}' le='${location.verticalAccuracyMeters}' />
                        <detail>
                            <contact endpoint='*:-1:stcp' phone='11234567890' callsign='$callsign'/>
                            <__group role='$roleValue' name='$teamValue'/>
                            <biometrics hr='$heartRate'/>
                            <status battery='$battery' model='WEAROS'/>
                            <track course='${location.bearing}' speed='${location.speed}'/>
                        </detail>
                    </event>
                """.trimIndent()
         */
    }

    override fun onDestroy() {
        super.onDestroy()
        cotDispatchHandler?.removeCallbacks(cotDispatchRunnable)
        cotDispatchThread?.quitSafely()
    }

    companion object {
        private val TAG = "WearTAK-" + CotDispatchService::class.java.simpleName
        private const val DISPATCH_INTERVAL = 2_000L // 2 seconds
        private const val COT_DISPATCH_SERVICE_NOTIFICATION_ID = 100
        var locationUpdateIntervalSecs = 2
        var locationUpdateFastestIntervalSecs = 2
    }
}

// TODO: change the biometrics child of details to seperate into arrays called "device". This way a cot with multiple devices connected to it can dispatch various biometrics in one cot message (ie biostrap + oura ring).
/*
<detail>
    <biometrics>
        <device>
            <model>WEAROS</model>
            <hr>94</hr>
        </device>
         <device>
            <model>WEAROS</model>
            <hr>94</hr>
        </device>
        <device>
            <model>WEAROS</model>
            <hr>94</hr>
        </device>
    </biometrics>
</detail>
 */