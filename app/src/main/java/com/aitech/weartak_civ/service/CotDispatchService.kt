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
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils.getValueFromJson
import com.aitech.weartak_civ.Utils.writeToLogFileLocation
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.CustomEmergencyEvent
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.ALERT_CANCELLED
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.aitech.weartak_civ.events.LocationRcvd
import com.aitech.weartak_civ.events.LocationUpdateRequired
import com.aitech.weartak_civ.events.MarkerEvent
import com.aitech.weartak_civ.events.MarkerEvent.Companion.FRIENDLY
import com.aitech.weartak_civ.events.MarkerEvent.Companion.HOSTILE
import com.aitech.weartak_civ.events.MarkerEvent.Companion.NEUTRAL
import com.aitech.weartak_civ.events.MarkerEvent.Companion.UNKNOWN
import com.aitech.weartak_civ.events.MessageEvent.Companion.CALLSIGN_TEAM_ROLE_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED
import com.aitech.weartak_civ.manager.SMSManager
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.logFile
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.smsNumbersList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleAlt
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleBeacon
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleBeaconUpdateTime
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleDistance
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleLat
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleLon
import com.aitech.weartak_civ.service.TAKConnectService.Companion.sendDataTAKServers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs


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
    private var manualSosReceivedSendSms: Boolean = false
    private var iconSetPath: String = ""
    private var markerType: String = ""
    private var alertOrCancel = false
    private var sosAlertUid: String = ""

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
            CoroutineScope(Dispatchers.Main).launch {
                setValuesFromCustomDeveloperConfig()
            }
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
                //writeToLogFileLocation(logFile, "Somehow location update did not happen within the last currReportingIntervalInSecs. Sending Biometric Cot via cotDispatchRunnable")
                buildAndSendLocationBiometricCotMessage()
            }
            // Schedule the next run
            cotDispatchHandler?.postDelayed(this, currReportingIntervalInSecs * 1000L)
        }
    }

    data class BleLocationOutput(
        val lat: Double,
        val lon: Double,
        val hae: Double,
        val ce: Float // Horizontal Error
    )

    private fun mapLocationAgainstClosestBleBeacon(startTimeInMs: Long, lat: Double, lon: Double, hae: Double, ce: Float): BleLocationOutput {
        var latActual = lat; var lonActual = lon; var haeActual = hae; var ceActual = ce

        //Log.d(TAG, "closestBleBeaconUpdateTime($closestBleBeaconUpdateTime); currTime($startTimeInMs); diff (${abs(startTimeInMs - closestBleBeaconUpdateTime)})")
        if ((closestBleBeaconUpdateTime > 0) && (abs(startTimeInMs - closestBleBeaconUpdateTime) <= GPS_STALE_TIME_INTERVAL_MS)) {
            if (ceActual > closestBleDistance) {
                //val logString = "Using BLE($closestBleLat, $closestBleLon, $closestBleAlt): gpsHorizError($ceActual) > bleClosestDist($closestBleDistance) w/ ble updated ${String.format("%.3f", abs(startTimeInMs - closestBleBeaconUpdateTime)/1000.0)}s ago"
                //Log.d(TAG, logString)
                //writeToLogFileLocation(logFile, logString)
                ceActual = closestBleDistance.toFloat()
                latActual = closestBleLat
                lonActual = closestBleLon
                haeActual = closestBleAlt
                closestBleBeacon?.overrideGPSWithBLECount = 0
            } else {
                val currClosestBleBeacon = closestBleBeacon!!
                if (currClosestBleBeacon.overrideGPSWithBLECount++ < OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT) {
                    //val logString = "GPS Hysteresis(${currClosestBleBeacon.overrideGPSWithBLECount}) Using BLE($closestBleLat, $closestBleLon, $closestBleAlt): gpsHorizError($ceActual) <= bleClosestDist($closestBleDistance) w/ ble updated ${String.format("%.3f", abs(startTimeInMs - closestBleBeaconUpdateTime)/1000.0)}s ago"
                    //Log.d(TAG, logString)
                    //writeToLogFileLocation(logFile, logString)
                    ceActual = closestBleDistance.toFloat()
                    latActual = closestBleLat
                    lonActual = closestBleLon
                    haeActual = closestBleAlt
                } else {
                    currClosestBleBeacon.overrideGPSWithBLECount = 0
                    //val logString = "Using GPS: gpsHorizAcc($ceActual) <= bleClosestDist($closestBleDistance) w/ ble updated ${String.format("%.3f", abs(startTimeInMs - closestBleBeaconUpdateTime)/1000.0)}s ago"
                    //Log.d(TAG, logString)
                    //writeToLogFileLocation(logFile, logString)
                }
            }
        } else {
            val logString = "Using GPS: gpsHorizAcc($ceActual) w/ closestBleBeaconUpdateTime($closestBleBeaconUpdateTime); currTime($startTimeInMs); diff (${abs(startTimeInMs - closestBleBeaconUpdateTime)})"
            Log.d(TAG, logString)
            //writeToLogFileLocation(logFile, logString)
        }
        return BleLocationOutput(latActual, lonActual, haeActual, ceActual)
    }

    private fun buildLocationBiometricCotMessage(location: Location?, heartRate: Int, battery: Int?, skinTemp: Float): String {
        dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))
        val calendar: Calendar = Calendar.getInstance()

        // Set the current time
        calendar.setTime(Date())

        // Format and set the startTime with the currentTime
        val startTime = dateTimeFormatter.format(calendar.time)
        val startTimeInMs = calendar.timeInMillis

        // add 3* Update interval seconds to the current time for 'stale time', ie when the cot expires
        calendar.add(Calendar.SECOND, 3*currReportingIntervalInSecs)
        val staleTime = dateTimeFormatter.format(calendar.time)

        // TODO: Check if these really don't need the null safety check. If not, direct injection into the cot XML is more readable.
        var latActual = location?.latitude ?: 0.0
        var lonActual = location?.longitude ?: 0.0
        var haeActual = location?.altitude ?: 0.0
        var ceActual = location?.accuracy ?: 9999999.0F
        if (ceActual.isNaN() == true) ceActual = 9999999.0F
        var leActual : Float = location?.verticalAccuracyMeters ?: 9999999.0F
        if (leActual.isNaN() == true) leActual = 9999999.0F
        val bearingActual = location?.bearing ?: 0.0F
        val speedActual = location?.speed ?: 0.0F

        // Ble Beacon
        if (MainActivity.enableBleBeaconService == true) {
            val bleLocationOutput = mapLocationAgainstClosestBleBeacon(startTimeInMs, latActual, lonActual, haeActual, ceActual)
            latActual = bleLocationOutput.lat; lonActual = bleLocationOutput.lon; haeActual = bleLocationOutput.hae; ceActual = bleLocationOutput.ce
        }

        val uid = MainActivity.device_uid
        val skinTempStr = String.format("%.2f", skinTemp)
        //Log.i(TAG, "Skt: $skinTempStr ºF | int: $currReportingIntervalInSecs seconds")
        // TODO: set phone number to actual phone number
        return """
        <?xml version="1.0" encoding="UTF-8" standalone='yes'?><event version="2.0" uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g' access='Undefined'><point lat='$latActual' lon='$lonActual' hae='$haeActual' ce='$ceActual' le='$leActual' /><detail><remarks>HR: $heartRate bpm; SkinTemp: ${skinTempStr}ºF; Batt: $battery%</remarks><status battery='$battery'/><contact endpoint='*:-1:stcp' phone='11234567890' callsign='$callsign'/><__group role='$roleValue' name='$teamValue'/><biometrics><device><model>WEAROS</model><uid>$uid</uid><hr>$heartRate</hr><skt>$skinTempStr</skt><bat>$battery</bat></device></biometrics><track course='$bearingActual' speed='$speedActual'/></detail></event>
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
        manualSosReceivedSendSms = event.sendSms
        val currTime = System.currentTimeMillis()
        alertOrCancel = event.category == ALERT_CANCELLED

        if ((currTime-lastLocationUpdateRcvd) < FRESH_GPS_INTERVAL) {
            Log.d(TAG, "Emergency message($manualSosReceivedCategory, $manualSosReceivedDescription with 'fresh' gps lastUpdated(${(currTime-lastLocationUpdateRcvd)/1000.0}s ago)")
            manualSosReceivedTime = currTime
            sendEmergencyAlertCotMessage(manualSosReceivedCategory, manualSosReceivedDescription, event.sendSms, alertOrCancel) // isAnyTakConnected is taken care of inside sendEmergencyAlertCotMessage()
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

    private fun buildEmergencyAlertCotMessage(location: Location, emergencyCategory: String, emergencyDesc: String, alertOrCancel:Boolean): String {
        dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))
        val calendar: Calendar = Calendar.getInstance()

        // Set the current time
        calendar.setTime(Date())

        // Format and set the startTime with the currentTime
        val startTime = dateTimeFormatter.format(calendar.time)
        val startTimeInMs = calendar.timeInMillis
        val emergencyCallsignDateTimeFormatter = SimpleDateFormat("MMdd_HHmmss'Z'")
        emergencyCallsignDateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))
        val emergencyCallsignAppendTime = emergencyCallsignDateTimeFormatter.format(calendar.time)
        val emergencyCallsign = "${callsign}_$emergencyCallsignAppendTime"

        // add 1 year to the current time for 'stale time', ie when the cot expires. Markers should last for a long time.
//        calendar.add(Calendar.YEAR, 1)
        calendar.add(Calendar.SECOND, 10)
        val staleTime = dateTimeFormatter.format(calendar.time)

        // TODO: Check if these really don't need the null safety check. If not, direct injection into the cot XML is more readable.
        var latActual = location?.latitude ?: 0.0
        var lonActual = location?.longitude ?: 0.0
        var haeActual = location?.altitude ?: 0.0
        var ceActual = location?.accuracy ?: 9999999.0F
        if (ceActual.isNaN() == true) ceActual = 9999999.0F
        var leActual : Float = location?.verticalAccuracyMeters ?: 9999999.0F
        if (leActual.isNaN() == true) leActual = 9999999.0F

        // Ble Beacon
        if (MainActivity.enableBleBeaconService == true) {
            val bleLocationOutput = mapLocationAgainstClosestBleBeacon(startTimeInMs, latActual, lonActual, haeActual, ceActual)
            latActual = bleLocationOutput.lat; lonActual = bleLocationOutput.lon; haeActual = bleLocationOutput.hae; ceActual = bleLocationOutput.ce
        }

//        val uid = UUID.randomUUID().toString()
        val uid = if (!alertOrCancel) {
            // Generate and store a new UID
            val newUid = UUID.randomUUID().toString()
            sosAlertUid = newUid
            newUid
        } else {
            // Use the previously stored UID
            sosAlertUid ?: "undefined"
        }
        val linkUid = MainActivity.device_uid

        if (alertOrCancel) {
            return """
        <?xml version='1.0' encoding='UTF-8' standalone='yes'?>
        <event version='2.0' uid='$uid' type='b-a-o-can' time='$startTime' start='$startTime' stale='$staleTime' how='h-e' access='Undefined'>
        	<point lat='$latActual' lon='$lonActual' hae='$haeActual' ce='$ceActual' le='$leActual' />
        	<detail>
        		<emergency cancel='true'>$callsign</emergency>
        	</detail>
        </event>
        """.trimIndent()

        } else {
            return """
        <?xml version='1.0' encoding='UTF-8' standalone='yes'?>
        <event version='2.0' uid='$uid' type='b-a-o' time='$startTime' start='$startTime' stale='$staleTime' how='h-e' access='Undefined'>
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
    }

    private fun buildMarkerCotMessage(location: Location, heartRate: Int, battery: Int): String {

        dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))
        val calendar: Calendar = Calendar.getInstance()

        // Set the current time
        calendar.setTime(Date())

        // Format and set the startTime with the currentTime
        val startTime = dateTimeFormatter.format(calendar.time)
        val startTimeInMs = calendar.timeInMillis
        val markerCallsignDateTimeFormatter = SimpleDateFormat("MMdd_HHmmss'Z'")
        markerCallsignDateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))
        val markerCallsignAppendTime = markerCallsignDateTimeFormatter.format(calendar.time)
        val markerCallsign = "${callsign}_$markerCallsignAppendTime"

        // add 1 year to the current time for 'stale time', ie when the cot expires. Markers should last for a long time.
        calendar.add(Calendar.YEAR, 1)
        val staleTime = dateTimeFormatter.format(calendar.time)

        // TODO: Check if these really don't need the null safety check. If not, direct injection into the cot XML is more readable.
        var latActual = location?.latitude ?: 0.0
        var lonActual = location?.longitude ?: 0.0
        var haeActual = location?.altitude ?: 0.0
        var ceActual = location?.accuracy ?: 9999999.0F
        if (ceActual.isNaN() == true) ceActual = 9999999.0F
        var leActual : Float = location?.verticalAccuracyMeters ?: 9999999.0F
        if (leActual.isNaN() == true) leActual = 9999999.0F
        val bearingActual = location?.bearing ?: 0.0F
        val speedActual = location?.speed ?: 0.0F

        // Ble Beacon
        if (MainActivity.enableBleBeaconService == true) {
            val bleLocationOutput = mapLocationAgainstClosestBleBeacon(startTimeInMs, latActual, lonActual, haeActual, ceActual)
            latActual = bleLocationOutput.lat; lonActual = bleLocationOutput.lon; haeActual = bleLocationOutput.hae; ceActual = bleLocationOutput.ce
        }

        val uid = UUID.randomUUID().toString()
        val linkUid = MainActivity.device_uid
        val cotType = "b-m-p-s-m"

        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <event version="2.0" uid="$uid" type="$markerType" time="$startTime" start="$startTime" stale="$staleTime" how="h-g-i-g-o" access="Undefined">
            <point lat="$latActual" lon="$lonActual" hae="$haeActual" ce="$ceActual" le="$leActual"/>
            <detail>
                <status readiness="true" battery='$battery'/>
                <precisionlocation altsrc="SRTM1"/>
                <link uid="$linkUid" production_time="$startTime" type="a-f-G-U-C" parent_callsign="$callsign" relation="p-p"/>
                <color argb="-1"/>
                <usericon iconsetpath="$iconSetPath"/>
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
        ioScope.launch(Dispatchers.IO) {
            // 1) Grab latest location; if null, take note & bail out
            val loc = dataRepository.latestLocation.value
            if (loc == null) {
                Log.w(TAG, "Marker skipped: no location available")
                markerDropToBeSent.set(true)
                return@launch
            }

            // 2) Gather telemetry (heartRate, battery) with defaults
            val heartRate = dataRepository.latestHeartRate.value ?: -1
            val battery   = dataRepository.latestBatteryPercentage.value ?: -1

            // 3) Build CoT XML (unchanged signature)
            val cotMessage = buildMarkerCotMessage(loc, heartRate, battery)

            // 5) Only send to TAK servers if connected
            if (TAKConnectService.isAnyTakConnected) {
                if (DittoService.isRunning.get()) {
                    EventBus.getDefault().post(DittoDocumentEvent("mapitem", cotMessage))
                    Log.v(TAG, "Marker CoT meshed into Ditto: $cotMessage")
                }
                markerDropToBeSent.set(false)
                sendDataTAKServers(cotMessage, true)
                Log.v(TAG, "Marker CoT sent to TAK: $cotMessage")
            } else {
                Log.w(TAG, "Marker CoT NOT sent to TAK (disconnected)")
                // 4) Mesh into Ditto if the DittoService is running
                if (DittoService.isRunning.get()) {
                    EventBus.getDefault().post(DittoDocumentEvent("mapitem", cotMessage))
                    markerDropToBeSent.set(false)
                    Log.v(TAG, "Marker CoT meshed into Ditto: $cotMessage")
                } else {
                    markerDropToBeSent.set(true) // we need to try again
                }
            }
        }
    }

    private fun sendEmergencyAlertCotMessage(emergencyCategory: String, emergencyDesc: String, sendSms: Boolean, alertOrCancel: Boolean) {
        ioScope.launch(Dispatchers.IO) {
            // don’t send an alert if we don’t have a location
            val loc = dataRepository.latestLocation.value
            if ((MainActivity.enableSmsForAlerts) && sendSms) {
                SMSManager.sendSMSMessage(numbers = smsNumbersList, description = emergencyDesc)
            }
            if (loc == null) {
                Log.w(TAG, "Skipping emergency alert: no GPS fix")
                manualSosToBeSent.set(true)
                return@launch
            }

            // build the CoT XML for this alert
            val cotMessage = buildEmergencyAlertCotMessage(loc, emergencyCategory, emergencyDesc, alertOrCancel)

            // only forward to TAK if we’re connected
            if (TAKConnectService.isAnyTakConnected) {
                if (DittoService.isRunning.get()) {
                    EventBus.getDefault()
                        .post(DittoDocumentEvent("alert", cotMessage))
                    manualSosToBeSent.set(false) // we sent it over ditto
                    Log.v(TAG, "Posted emergency alert to Ditto")
                }
                manualSosToBeSent.set(false)
                sendDataTAKServers(cotMessage, true)
                Log.v(TAG, "Sent emergency alert to TAK server")
            } else {
                // push into Ditto if the service is up
                if (DittoService.isRunning.get()) {
                    EventBus.getDefault()
                        .post(DittoDocumentEvent("alert", cotMessage))
                    manualSosToBeSent.set(false) // we sent it over ditto
                    Log.v(TAG, "Posted emergency alert to Ditto")
                } else {
                    Log.w(TAG, "TAk Server is not connected & there is no Ditto Mesh.")
                    manualSosToBeSent.set(true)
                    // TODO: Create an in memory (or persistent store) array of markers / SOS to be sent for when the device reconnects to the server.
                }
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
                var skinTemp = dataRepository.latestSkinTemp.value
                if (heartRate == null) {
                    heartRate = -1
                }
                if (skinTemp == null) {
                    skinTemp = (-1).toFloat()
                }
                sendLocationBiometricCotMessage(location, heartRate, battery, skinTemp)
            }
        }

    }

    private fun sendLocationBiometricCotMessage(location: Location?, heartRate: Int, battery: Int?, skinTemp: Float) {
        val cotMessage = buildLocationBiometricCotMessage(location, heartRate, battery, skinTemp)
        sendDataTAKServers(cotMessage, false)
        //val logString = "Location/Biometric CoT message sent ($currReportingIntervalInSecs): $cotMessage"
        //Log.d(TAG, logString)
        //writeToLogFileLocation(logFile, logString)
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
            sendEmergencyAlertCotMessage(manualSosReceivedCategory, manualSosReceivedDescription, manualSosReceivedSendSms, alertOrCancel)
        }
        lastLocationUpdateRcvd = System.currentTimeMillis()
        buildAndSendLocationBiometricCotMessage()
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onMarkerEvent(event: MarkerEvent) {
        val msg = event.message

        if (HOSTILE == msg) {
            iconSetPath = "COT_MAPPING_2525C/a-h/a-h-G"
            markerType = "a-h-G"
        } else if (FRIENDLY == msg) {
            iconSetPath = "COT_MAPPING_2525C/a-f/a-f-G"
            markerType = "a-f-G"
        } else if (NEUTRAL == msg) {
            iconSetPath = "COT_MAPPING_2525C/a-n/a-n-G"
            markerType = "a-n-G"
        } else { // UNKNOWN or any other
            iconSetPath = "COT_MAPPING_2525C/a-u/a-u-G"
            markerType = "a-u-G"
        }
        val currTime = System.currentTimeMillis()
        if ((currTime-lastLocationUpdateRcvd) < FRESH_GPS_INTERVAL) {
            Log.d(TAG, "MarkerEvent with 'fresh' gps lastUpdated(${(currTime-lastLocationUpdateRcvd)/1000.0}s ago)")
            markerDropReceivedTime = currTime
            sendMarkerCotMessage() // iSAnyTakConnected is taken care of inside sendMarkerCotMessage()
        } else {
            if (markerDropToBeSent.getAndSet(true) == false) {
                Log.d(TAG, "MarkerEvent with markerDropToBeSent false & 'stale' gps lastUpdated(${(currTime-lastLocationUpdateRcvd)/1000.0}s ago)")
                markerDropReceivedTime = currTime
                EventBus.getDefault().post(LocationUpdateRequired())
            } else {
                Log.d(TAG, "MarkerEvent with markerDropToBeSent true & 'stale' gps lastUpdated(${(currTime-lastLocationUpdateRcvd)/1000.0}s ago)")
            }
        }
    }

    // This method will be called when a MessageEvent is posted (in the UI thread for Toast)
    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onMessageEvent(event: MessageEvent) {
        val msg = event.message
        Log.d(TAG, "onMessageEvent w/ msg($msg)")
        if (CALLSIGN_TEAM_ROLE_CHANGED == msg) {
            // send the "freshest" cot message with the newly updated callsign
            buildAndSendLocationBiometricCotMessage()
        } else if (CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED == msg) {
            // Check for OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT and GPS_STALE_TIME_INTERVAL_MS
            // Json could be {"bleExitHysteresisCount" : "20", "bleStaleTimeIntervalMs" : "5000", "overrideGpsWithBleExitHysteresisCount" : "30", "gpsStaleTimeInterval" : "10000" }
            CoroutineScope(Dispatchers.Main).launch {
                setValuesFromCustomDeveloperConfig()
            }
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
        Log.d(TAG, "CotDispatchService onDestroy called")

        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        stopDispatchScheduler()
        isRunning.set(false)
    }

    suspend private fun setValuesFromCustomDeveloperConfig() {
        val jsonString = MainActivity.Constants.dataRepository.getCustomDeveloperConfigJson.first()
        // Check for BLE_EXIT_HYSTERESIS_COUNT and BLE_STALE_TIME_INTERVAL_MS
        // Json could be {"bleExitHysteresisCount" : "20", "bleStaleTimeIntervalMs" : "5000", "overrideGpsWithBleExitHysteresisCount" : "30", "gpsStaleTimeInterval" : "10000" }
        var value: String? = getValueFromJson(jsonString, OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT_JSON_KEY)
        if (null != value) {
            val intValue = value.toIntOrNull()
            Log.d(TAG, "$OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT_JSON_KEY: $intValue")
            if (null != intValue) {
                OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT = intValue
            }
        }
        value = getValueFromJson(jsonString, GPS_STALE_TIME_INTERVAL_MS_JSON_KEY)
        if (null != value) {
            val longValue = value.toLongOrNull()
            Log.d(TAG, "$GPS_STALE_TIME_INTERVAL_MS_JSON_KEY: $longValue")
            if (null != longValue) {
                GPS_STALE_TIME_INTERVAL_MS = longValue
            }
        }
    }

    companion object {
        private val TAG = "WearTAK-" + CotDispatchService::class.java.simpleName
        private const val DISPATCH_INTERVAL = 2_000L // 2 seconds
        // Time interval within which GPS is considered "fresh"
        // We will send out SOS and marker drop if last time GPS was received is within 3 seconds of receiving the Event to send marker or SOS respectively, else we will get new GPS location and then send out marker or SOS as needed
        private const val FRESH_GPS_INTERVAL = 3_000L
        private const val COT_DISPATCH_SERVICE_NOTIFICATION_ID = 100
        private var GPS_STALE_TIME_INTERVAL_MS = 10_000L
        private var OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT = 30
        private const val GPS_STALE_TIME_INTERVAL_MS_JSON_KEY = "gpsStaleTimeInterval"
        private const val OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT_JSON_KEY = "overrideGpsWithBleExitHysteresisCount"
        val isRunning : AtomicBoolean = AtomicBoolean(false)
        var reportingUpdateIntervalSecs = 10
        var currReportingIntervalInSecs = 10
        lateinit var instance: CotDispatchService
        val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    }
}
