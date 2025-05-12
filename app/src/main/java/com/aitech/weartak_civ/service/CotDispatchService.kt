/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

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
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.CustomEmergencyEvent
import com.aitech.weartak_civ.events.LocationRcvd
import com.aitech.weartak_civ.events.LocationUpdateRequired
import com.aitech.weartak_civ.events.MessageEvent.Companion.CALLSIGN_TEAM_ROLE_CHANGED
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.logFile
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.service.TAKConnectService.Companion.sendDataTAKServers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean


class CotDispatchService : Service() {
    private val TAG = "WearTAK-" + CotDispatchService::class.java.simpleName
    private var cotDispatchHandler: Handler? = null
    private var cotDispatchThread: HandlerThread? = null
    private var lastLocationUpdateRcvd: Long = 0L
    //private var lastHandledMarkerId = 0  // # of button clicks for differentiating multiple clicks in a row
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val markerDropToBeSent = AtomicBoolean(false)
    private var markerDropReceivedTime: Long = 0L
    private val manualSosToBeSent = AtomicBoolean(false)
    private var manualSosReceivedTime: Long = 0L
    private var manualSosReceivedCategory: String = ""
    private var manualSosReceivedDescription: String  = ""

    // Acquire DataRepository from a global or singleton reference
    private lateinit var dataRepository: DataRepository

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.v(TAG, "CotDispatchService onStartCommand()")
        if (!isRunning.getAndSet(true)) {
            startForegroundService()
            startDispatchScheduler()
        }
        return START_NOT_STICKY
    }

    // starting requires notification to the user
    private fun startForegroundService() {
        Log.v(TAG, "Starting $TAG as a ForegroundService")
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
        Log.v(TAG, "CotDispatchService onCreate()")
    }

    private fun startDispatchScheduler() {
        Log.v(TAG, "Starting the dispatch scheduler")
        cotDispatchThread = HandlerThread("CotDispatchThread")
        cotDispatchThread?.start()
        cotDispatchHandler = cotDispatchThread?.looper?.let { Handler(it) }

        // Schedule the first run
        cotDispatchHandler?.postDelayed(cotDispatchRunnable, DISPATCH_INTERVAL)
    }

    private fun stopDispatchScheduler() {
        Log.v(TAG, "Stopping the dispatch Scheduler")
        cotDispatchHandler?.removeCallbacks(cotDispatchRunnable)
        cotDispatchThread?.quitSafely()
    }

    private val cotDispatchRunnable = object : Runnable {
        override fun run() {
            if ((System.currentTimeMillis()-lastLocationUpdateRcvd) > currReportingIntervalInSecs * 1000L) {
                // Somehow location update did not happen within the last currReportingIntevalInSecs.
                // Mostly because watch does not have GPS (e.g., indoor) and fused location at this time
                // will only provide updates every 5 seconds irrespective if currReportingIntevalInSecs < 5s.
                // Once watch catches GPS, updates are at max 1s.
                //Log.v(TAG, "Somehow location update did not happen within the last currReportingIntervalInSecs ($currReportingIntervalInSecs) w/ last update ${((System.currentTimeMillis()-lastLocationUpdateRcvd)/1000.0)}secs ago. Sending Biometric Cot via cotDispatchRunnable")
                //writeToLogFileLocation("Somehow location update did not happen within the last currReportingIntervalInSecs ($currReportingIntervalInSecs) w/ last update ${((System.currentTimeMillis()-lastLocationUpdateRcvd)/1000.0)}secs ago. Sending Biometric Cot via cotDispatchRunnable")
                buildAndSendLocationBiometricCotMessage()
            }
            // Schedule the next run
            cotDispatchHandler?.postDelayed(this, currReportingIntervalInSecs * 1000L)
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
        calendar.add(Calendar.SECOND, 3*currReportingIntervalInSecs)
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
        <?xml version="1.0" encoding="UTF-8" standalone='yes'?><event version="2.0" uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g' access='Undefined'><point lat='$latActual' lon='$lonActual' hae='$haeActual' ce='$ceActual' le='$leActual' /><detail><remarks>HR: $heartRate bpm; Batt: $battery%</remarks><status battery='$battery'/><contact endpoint='*:-1:stcp' phone='11234567890' callsign='$callsign'/><__group role='$roleValue' name='$teamValue'/><biometrics><device><model>WEAROS</model><uid>$uid</uid><hr>$heartRate</hr><bat>$battery</bat></device></biometrics><track course='$bearingActual' speed='$speedActual'/></detail></event>
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

    @Subscribe(threadMode = ThreadMode.MAIN)
    public fun onEmergencyMessageEvent(event: CustomEmergencyEvent) {
        manualSosReceivedCategory = event.category
        manualSosReceivedDescription = event.description
        val currTime = System.currentTimeMillis()
        if ((currTime-lastLocationUpdateRcvd) < FRESH_GPS_INTERVAL) {
            Log.d(TAG, "Emergency message($manualSosReceivedCategory, $manualSosReceivedDescription with 'fresh' gps lastUpdated(${(currTime-lastLocationUpdateRcvd)/1000.0}s ago)")
            manualSosReceivedTime = currTime
            sendEmergencyAlertCotMessage(manualSosReceivedCategory, manualSosReceivedDescription) // isAnyTakConnected is taken care of inside sendEmergencyAlertCotMessage()
        } else {
            if (manualSosToBeSent.getAndSet(true) == false) { // We overwrite with latest SOS
                Log.d(TAG, "Emergency message($manualSosReceivedCategory, $manualSosReceivedDescription with manualSosToBeSent false & 'stale' gps lastUpdated(${(currTime-lastLocationUpdateRcvd)/1000.0}s ago)")
                manualSosReceivedTime = currTime
                EventBus.getDefault().post(LocationUpdateRequired())
            } else {
                Log.d(TAG, "Emergency message($manualSosReceivedCategory, $manualSosReceivedDescription with manualSosToBeSent ${manualSosToBeSent.get()} & 'stale' gps lastUpdated(${(currTime-lastLocationUpdateRcvd)/1000.0}s ago)")
            }
        }
    }

    private fun buildEmergencyAlertCotMessage(location: Location, emergencyCategory: String, emergencyDesc: String): String {
        dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))
        val calendar: Calendar = Calendar.getInstance()

        // Set the current time
        calendar.setTime(Date())

        // Format and set the startTime with the currentTime
        val startTime = dateTimeFormatter.format(calendar.time)
        val emergencyCallsignDateTimeFormatter = SimpleDateFormat("MMdd_HHmmss'Z'")
        emergencyCallsignDateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))
        val emergencyCallsignAppendTime = emergencyCallsignDateTimeFormatter.format(calendar.time)
        val emergencyCallsign = "${callsign}_$emergencyCallsignAppendTime"

        // add 1 year to the current time for 'stale time', ie when the cot expires. Markers should last for a long time.
//        calendar.add(Calendar.YEAR, 1)
        calendar.add(Calendar.SECOND, 10)
        val staleTime = dateTimeFormatter.format(calendar.time)

        // TODO: Check if these really don't need the null safety check. If not, direct injection into the cot XML is more readable.
        val latActual = location?.latitude ?: 0.0
        val lonActual = location?.longitude ?: 0.0
        val haeActual = location?.altitude ?: 0.0
        var ceActual = location?.accuracy ?: 9999999.0F
        if (ceActual.isNaN() == true) ceActual = 9999999.0F
        var leActual : Float = location?.verticalAccuracyMeters ?: 9999999.0F
        if (leActual.isNaN() == true) leActual = 9999999.0F

        val uid = UUID.randomUUID().toString()
        val linkUid = MainActivity.device_uid

        return """
        <?xml version='1.0' encoding='UTF-8' standalone='yes'?>
        <event version='2.0' uid='$uid' type='b-a-u-a' time='$startTime' start='$startTime' stale='$staleTime' how='h-e' access='Undefined'>
        	<point lat='$latActual' lon='$lonActual' hae='$haeActual' ce='$ceActual' le='$leActual' />
        	<detail>
        		<link uid='$linkUid' type='a-f-G-U-C' relation='p-p'/>
        		<contact callsign='$emergencyCallsign&#10;$emergencyCategory'/>
        		<emergency type='$emergencyDesc'>$callsign</emergency>
                <usericon iconsetpath='911 Alert'/>
                <color argb='-1'/>
        	</detail>
        </event>
        """.trimIndent()
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
                <status readiness="true" battery='$battery'/>
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

    private fun sendMarkerCotMessage() {
        CoroutineScope(Dispatchers.IO).launch {
            val location = dataRepository.latestLocation.value
            if (TAKConnectService.isAnyTakConnected && (location != null)) {
                markerDropToBeSent.set(false)
                var heartRate = dataRepository.latestHeartRate.value
                var battery = dataRepository.latestBatteryPercentage.value
                if (heartRate == null) {
                    heartRate = -1
                }
                if (battery == null) {
                    battery = -1
                }
                val cotMessage = buildMarkerCotMessage(location, heartRate, battery)
                sendDataTAKServers(cotMessage, true)
                Log.v(TAG, "Marker CoT message sent: $cotMessage")
            } else {
                Log.w(TAG, "Marker CoT message NOT sent as isAnyTakConnected(${TAKConnectService.isAnyTakConnected}) and location($location)")
                markerDropToBeSent.set(true)
                //markerDropReceivedTime = System.currentTimeMillis() // NOTE: This is taken care of in the calling function
            }
        }
    }

    private fun sendEmergencyAlertCotMessage(emergencyCategory: String, emergencyDesc: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val location = dataRepository.latestLocation.value
            if (TAKConnectService.isAnyTakConnected && (location != null)) {
                manualSosToBeSent.set(false)
                val cotMessage = buildEmergencyAlertCotMessage(location, emergencyCategory, emergencyDesc)
                sendDataTAKServers(cotMessage, true)
                Log.v(TAG, "EmergencyAlert CoT message sent: $cotMessage")
            } else {
                Log.w(TAG, "EmergencyAlert CoT message NOT sent as isAnyTakConnected(${TAKConnectService.isAnyTakConnected}) and location($location)")
                manualSosToBeSent.set(true)
            }
        }
    }

    fun buildAndSendLocationBiometricCotMessage() {
        if (!TAKConnectService.isAnyTakConnected) {
            Log.w(TAG, "Not connected to server. Not sending Cot message")
        } else {
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

    }

    private fun sendLocationBiometricCotMessage(location: Location, heartRate: Int, battery: Int) {
        val cotMessage = buildLocationBiometricCotMessage(location, heartRate, battery)
        sendDataTAKServers(cotMessage, false)
        Log.v(TAG, "Location/Biometric CoT message sent: $cotMessage")
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onLocationRcvdEvent(event: LocationRcvd) {
        //Log.d(TAG, "onLocationRcvdEvent called")
        // We would like to send the "freshest" location especially if location update is 10s or greater
        if (markerDropToBeSent.get() == true) {
            Log.d(TAG, "onLocationRcvdEvent called with markerDropToBeSent true")
            sendMarkerCotMessage() // iSAnyTakConnected is taken care of inside sendMarkerCotMessage()
        }
        if (manualSosToBeSent.get() == true) {
            Log.d(TAG, "onLocationRcvdEvent called with manualSosToBeSent true")
            sendEmergencyAlertCotMessage(manualSosReceivedCategory, manualSosReceivedDescription)
        }
        lastLocationUpdateRcvd = System.currentTimeMillis()
        buildAndSendLocationBiometricCotMessage()
    }

    // This method will be called when a MessageEvent is posted (in the UI thread for Toast)
    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onMessageEvent(event: MessageEvent) {
        val msg = event.message
        Log.d(TAG, "onMessageEvent w/ msg($msg)")
            if (DROP_MARKER == msg) {
                val currTime = System.currentTimeMillis()
                if ((currTime-lastLocationUpdateRcvd) < FRESH_GPS_INTERVAL) {
                    Log.d(TAG, "DROP_MARKER with 'fresh' gps lastUpdated(${(currTime-lastLocationUpdateRcvd)/1000.0}s ago)")
                    markerDropReceivedTime = currTime
                    sendMarkerCotMessage() // iSAnyTakConnected is taken care of inside sendMarkerCotMessage()
                } else {
                    if (markerDropToBeSent.getAndSet(true) == false) {
                        Log.d(TAG, "DROP_MARKER with markerDropToBeSent false & 'stale' gps lastUpdated(${(currTime-lastLocationUpdateRcvd)/1000.0}s ago)")
                        markerDropReceivedTime = currTime
                        EventBus.getDefault().post(LocationUpdateRequired())
                    } else {
                        Log.d(TAG, "DROP_MARKER with markerDropToBeSent true & 'stale' gps lastUpdated(${(currTime-lastLocationUpdateRcvd)/1000.0}s ago)")
                    }
                }
            } else if (CALLSIGN_TEAM_ROLE_CHANGED == msg) {
                // send the "freshest" cot message with the newly updated callsign
                buildAndSendLocationBiometricCotMessage()
            }
    }

    /*
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTakConnectionSuccess(event: TakServerConnStatusEvent) {
        if (event.connEvent == CONNECTION_SUCCESS) {
            Log.d(TAG, "TAKConnectService connected. Starting to dispatch cots...")
            startDispatchScheduler()
        } else {
            Log.v(TAG, "Disconnected from TAK Server. Wasn't Success message. Not dispatching Cots.")
            stopDispatchScheduler()
        }
    }

     */

    override fun onDestroy() {
        super.onDestroy()
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        stopDispatchScheduler()
        isRunning.set(false)
    }

    companion object {
        private val TAG = "WearTAK-" + CotDispatchService::class.java.simpleName
        private const val DISPATCH_INTERVAL = 2_000L // 2 seconds
        // Time interval within which GPS is considered "fresh"
        // We will send out SOS and marker drop if last time GPS was received is within 3 seconds of receiving the Event to send marker or SOS respectively, else we will get new GPS location and then send out marker or SOS as needed
        private const val FRESH_GPS_INTERVAL = 3_000L
        private const val COT_DISPATCH_SERVICE_NOTIFICATION_ID = 100
        val isRunning : AtomicBoolean = AtomicBoolean(false)
        var reportingUpdateIntervalSecs = 10
        var currReportingIntervalInSecs = 10
        lateinit var instance: CotDispatchService
        val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    }
}