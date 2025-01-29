package com.aitech.weartak_civ.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.DROP_MARKER
import com.aitech.weartak_civ.events.MessageEvent.Companion.RECV_LOCATION
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.service.DittoService.Companion.localPeerKeyString
import com.aitech.weartak_civ.service.TAKConnectService.Companion.sendDataTAKServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.UUID


class CotDispatchService : Service() {
    private val TAG = "WearTAK-" + CotDispatchService::class.java.simpleName
    private var cotDispatchHandler: Handler? = null
    private var cotDispatchThread: HandlerThread? = null
    private val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    var lastLocationUpdateRcvd : Long = 0L
    //private var lastHandledMarkerId = 0  // # of button clicks for differentiating multiple clicks in a row
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val uid = MainActivity.device_uid

    // Acquire DataRepository from a global or singleton reference
    private lateinit var dataRepository: DataRepository

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "CotDispatchService onStartCommand()")
        startForegroundService()
        startDispatchScheduler()
        isRunning = true
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
        instance = this
        dataRepository = MainActivity.dataRepository  // single instance of data repository shared by all instances
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
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
            if ((System.currentTimeMillis()-lastLocationUpdateRcvd) > reportingUpdateIntervalSecs * 1000L) {
                // Somehow location update did not happen within the last reportingUpdateIntervalSecs.
                // Maybe locationService is using fused indoor and reportingUpdateIntervalSecs < 5s OR some other reason
                Log.d(TAG, "Sending locationBiometricUpdate via cotDispatchRunnable")
                buildAndSendLocationBiometricCotMessage()
            }
            // Schedule the next run
            cotDispatchHandler?.postDelayed(this, reportingUpdateIntervalSecs * 1000L)
        }

    }


    private fun buildLocationBiometricCotMessage(location: Location, heartRate: Int, battery: Int): String {
        dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))
        val calendar: Calendar = Calendar.getInstance()

        // Set the current time
        calendar.setTime(Date())

        // Format and set the startTime with the currentTime
        val startTime = dateTimeFormatter.format(calendar.time)

        // add 3* Update interval seconds to the current time for 'stale time', ie when the cot expires
        calendar.add(Calendar.SECOND, 3*reportingUpdateIntervalSecs)
        val staleTime = dateTimeFormatter.format(calendar.time)

        // TODO: Check if these really don't need the null safety check. If not, direct injection into the cot XML is more readable.
        val latActual = location?.latitude ?: 0.0
        val lonActual = location?.longitude ?: 0.0
        val haeActual = location?.altitude ?: 0.0
        var ceActual = location?.accuracy ?: 9999999.0F
        if (ceActual.isNaN() == true) ceActual = 9999999.0F
        var leActual : Float = location?.verticalAccuracyMeters ?: 9999999.0F
        if (leActual.isNaN() == true) leActual = 9999999.0F
        val bearingActual = location?.bearing ?: 0.0F
        val speedActual = location?.speed ?: 0.0F

        val uid = MainActivity.device_uid

        // TODO: set phone number to actual phone number
        return """
        <?xml version="1.0" encoding="UTF-8" standalone='yes'?><event version="2.0" uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g' access='Undefined'><point lat='$latActual' lon='$lonActual' hae='$haeActual' ce='$ceActual' le='$leActual' /><detail><contact endpoint='*:-1:stcp' phone='11234567890' callsign='$callsign'/><__group role='$roleValue' name='$teamValue'/><biometrics><device><model>WEAROS</model><uid>$uid</uid><hr>$heartRate</hr><bat>$battery</bat></device></biometrics><track course='$bearingActual' speed='$speedActual'/></detail></event>
        """.trimIndent()
//        return """
//        <?xml version="1.0" encoding="UTF-8" standalone='yes'?><event version="2.0" uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g' access='Undefined'><point lat='$latActual' lon='$lonActual' hae='$haeActual' ce='$ceActual' le='$leActual' /><detail><contact endpoint='*:-1:stcp' phone='11234567890' callsign='$callsign'/><ditto ip='0.0.0.0' pks='$localPeerKeyString' deviceName='$callsign' version='AndJ4.8.1_9afa7353b2'/><__group role='$roleValue' name='$teamValue'/><biometrics><device><model>WEAROS</model><uid>$uid</uid><hr>$heartRate</hr><bat>$battery</bat></device></biometrics><track course='$bearingActual' speed='$speedActual'/></detail></event>
//        """.trimIndent()



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

    private fun buildMarkerCotMessage(location: Location, heartRate: Int, battery: Int): String {

        dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))
        val calendar: Calendar = Calendar.getInstance()

        // Set the current time
        calendar.setTime(Date())

        // Format and set the startTime with the currentTime
        val startTime = dateTimeFormatter.format(calendar.time)
        val markerCallsignDateTimeFormatter = SimpleDateFormat("MMdd_HHmmss'Z'")
        markerCallsignDateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))
        val markerCallsignAppendTime = markerCallsignDateTimeFormatter.format(calendar.time)
        val markerCallsign = "${callsign}_$markerCallsignAppendTime"

        // add 1 year to the current time for 'stale time', ie when the cot expires. Markers should last for a long time.
        calendar.add(Calendar.YEAR, 1)
        val staleTime = dateTimeFormatter.format(calendar.time)

        // TODO: Check if these really don't need the null safety check. If not, direct injection into the cot XML is more readable.
        val latActual = location?.latitude ?: 0.0
        val lonActual = location?.longitude ?: 0.0
        val haeActual = location?.altitude ?: 0.0
        var ceActual = location?.accuracy ?: 9999999.0F
        if (ceActual.isNaN() == true) ceActual = 9999999.0F
        var leActual : Float = location?.verticalAccuracyMeters ?: 9999999.0F
        if (leActual.isNaN() == true) leActual = 9999999.0F
        val bearingActual = location?.bearing ?: 0.0F
        val speedActual = location?.speed ?: 0.0F

        val uid = UUID.randomUUID().toString()
        val linkUid = MainActivity.device_uid
        val cotType = "b-m-p-s-m"

        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <event version="2.0" uid="$uid" type="a-u-G" time="$startTime" start="$startTime" stale="$staleTime" how="h-g-i-g-o" access="Undefined">
            <point lat="$latActual" lon="$lonActual" hae="$haeActual" ce="$ceActual" le="$leActual"/>
            <detail>
                <status readiness="true"/>
                <precisionlocation altsrc="SRTM1"/>
                <link uid="$linkUid" production_time="$startTime" type="a-f-G-U-C" parent_callsign="$callsign" relation="p-p"/>
                <color argb="-1"/>
                <usericon iconsetpath="COT_MAPPING_2525C/a-u/a-u-G"/>
                <remarks>HeartRate: $heartRate bpm; Battery Level: $battery%</remarks>
                <contact callsign="$markerCallsign"/>
            </detail>
        </event>
        """.trimIndent()
/*
        <?xml version='1.0' encoding='UTF-8' standalone='yes'?>
        <event version='2.0' uid='8b1c80ed-aa14-4747-9bdc-7f931807c9bd' type='a-u-G' time='2025-01-14T17:00:49.286Z' start='2025-01-14T17:00:49.286Z' stale='2025-01-14T17:05:49.286Z' how='h-g-i-g-o' access='Undefined'>
            <point lat='41.886874' lon='-87.659409' hae='126.631' ce='10.5' le='9999999.0' />
            <detail>
                <status readiness='true'/>
                <archive/>
                <color argb='-1'/>
                <remarks/>
                <link uid='ANDROID-4cbffc120e174b01' production_time='2024-10-31T16:41:29.018Z' type='a-f-G-U-C' parent_callsign='MAYHEMINDIA-TAB' relation='p-p'/>
                <archive/>
                <contact callsign='MAYHEMINDIA-TAB SSband_1031_164129'/>
            </detail>
        </event>
*/

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

    private fun sendMarkerCotMessage(location: Location, heartRate: Int, battery: Int) {
        val cotMessage = buildMarkerCotMessage(location, heartRate, battery)
        sendDataTAKServer(cotMessage)
        Log.d(TAG, "Marker CoT message sent: $cotMessage")
    }

    fun buildAndSendLocationBiometricCotMessage() {
        CoroutineScope(Dispatchers.IO).launch {
            val location = dataRepository.latestLocation.value
            var heartRate = dataRepository.latestHeartRate.value
            val battery = dataRepository.latestBatteryPercentage.value
            if (location != null && battery != null) {
                if (heartRate == null) {
                    heartRate = -1
                }
                sendLocationBiometricCotMessage(location, heartRate, battery)
            } else {
                Log.w(TAG, "Missing data for CoT message. Not sending.")
            }
        }
    }

    private fun sendLocationBiometricCotMessage(location: Location, heartRate: Int, battery: Int) {
        val cotMessage = buildLocationBiometricCotMessage(location, heartRate, battery)
        sendDataTAKServer(cotMessage)
        Log.d(TAG, "Location/Biometric CoT message sent: $cotMessage")
    }

    // This method will be called when a MessageEvent is posted (in the UI thread for Toast)
    @Subscribe(threadMode = ThreadMode.MAIN)
    public fun onMessageEvent(event: MessageEvent) {
        val msg = event.message
        //Log.d(TAG, "onMessageEvent w/ msg($msg)")
        if (RECV_LOCATION == msg) {
            // We would like to send the "freshest" location especially if location update is 10s or greater
            lastLocationUpdateRcvd = System.currentTimeMillis()
            buildAndSendLocationBiometricCotMessage()
        } else if (DROP_MARKER == msg) {
            CoroutineScope(Dispatchers.IO).launch {
            val location = dataRepository.latestLocation.value
            var heartRate = dataRepository.latestHeartRate.value
            var battery = dataRepository.latestBatteryPercentage.value
            if (location != null) {
                if (heartRate == null) {
                    heartRate = -1
                }
                if (battery == null) {
                    battery = -1
                }
                    sendMarkerCotMessage(location, heartRate, battery)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        cotDispatchHandler?.removeCallbacks(cotDispatchRunnable)
        cotDispatchThread?.quitSafely()
        isRunning = false
    }

    companion object {
        private val TAG = "WearTAK-" + CotDispatchService::class.java.simpleName
        private const val DISPATCH_INTERVAL = 2_000L // 2 seconds
        private const val COT_DISPATCH_SERVICE_NOTIFICATION_ID = 100
        var isRunning : Boolean = false
        var reportingUpdateIntervalSecs = 10
        lateinit var instance: CotDispatchService
    }
}