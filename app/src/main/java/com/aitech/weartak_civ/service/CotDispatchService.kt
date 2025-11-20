/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.DEFAULT_REPORTING_INTERVAL
import com.aitech.weartak_civ.Utils.PhysioUtils.calculateExertion
import com.aitech.weartak_civ.Utils.PhysioUtils.convertFarenheitToCelsius
import com.aitech.weartak_civ.Utils.getValueFromJson
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.CustomEmergencyEvent
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.CAT_SOS_ALERT_CANCELLED
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.DESC_SOS_ALERT
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.aitech.weartak_civ.events.LocationRcvd
import com.aitech.weartak_civ.events.LocationUpdateRequired
import com.aitech.weartak_civ.events.MessageEvent.Companion.CALLSIGN_TEAM_ROLE_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED
import com.aitech.weartak_civ.manager.SMSManager
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableEsriEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableTabletCommandEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBatdokCot
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.smsNumbersList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleAlt
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleBeacon
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleBeaconUpdateTime
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleDistance
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleLat
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleLon
import com.aitech.weartak_civ.service.HailService.checkLicense
import com.aitech.weartak_civ.service.HailService.checkLocalHailLicense
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.TAKConnectService.Companion.sendDataTAKServers
import com.rain.hail.sdk.HailDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import kotlin.math.roundToInt

class CotDispatchService : Service() {
    private val TAG = "WearTAK-" + CotDispatchService::class.java.simpleName
    private var cotDispatchHandler: Handler? = null
    private var cotDispatchThread: HandlerThread? = null
    private var lastLocationUpdateRcvd: Long = 0L
    //private var lastHandledMarkerId = 0  // # of button clicks for differentiating multiple clicks in a row
    private val ioScope = CoroutineScope(Dispatchers.IO)
    //    private val markerDropToBeSent = AtomicBoolean(false)
//    private var markerDropReceivedTime: Long = 0L
    private val manualSosToBeSent = AtomicBoolean(false)
    private var manualSosReceivedTime: Long = 0L
    private var manualSosReceivedCategory: String = ""
    private var manualSosReceivedDescription: String  = ""
    private var manualSosReceivedSendSms: Boolean = false
    private var iconSetPath: String = ""
    private var markerType: String = ""
    private var alertOrCancel = false
    private var inManualAlertingState = false
    private var sosAlertUid: String = ""

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var combatId: Int = 0

    // Acquire DataRepository from a global or singleton reference
    private lateinit var dataRepository: DataRepository

    private var sentBatteryWarnChatMsg = false
    private var sentBatteryAlertChatMsg = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.v(TAG, "CotDispatchService onStartCommand()")
        return START_NOT_STICKY
    }

    @Volatile private var lastLicenseState: HailService.HailLicenseStates = HailService.HailLicenseStates.INVALID

    private var gateCtr = 0
    private fun lgGate(msg: String) = Log.d(TAG, "[gate#${++gateCtr}] $msg")

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CotDispatchService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            notificationId = COT_DISPATCH_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        instance = this
        dataRepository = MainActivity.dataRepository  // single instance of data repository shared by all instances

        startDispatchScheduler()
        CoroutineScope(Dispatchers.Main).launch {
            setValuesFromCustomDeveloperConfig()
        }
        //TODO: WEAR-312: Prevent this from calling when battery alerts disabled vvv
        startBatteryAlerts()

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        collectHailFlows()

        startMarkerPipelines()
    }

    private fun startBatteryAlerts() {
        // TODO: We should only send this when the device is NOT charging... And change to CEP rule
        serviceScope.launch(Dispatchers.IO) {
            dataRepository.latestBatteryPercentage.collect { batt ->
                val batteryAlertsEnabled = (dataRepository.getEnableBatteryAlerts.first())
                val endpointsToSendTo: Boolean = (TAKConnectService.isAnyTakConnected || dataRepository.getEnableMulticast.first())
                if ((null != batt) && endpointsToSendTo && batteryAlertsEnabled) {
                    if (batt <= MainActivity.battWarnPercent) {
                        Log.d(TAG, "battery: $batt")
                        if (batt > MainActivity.battAlertPercent) {
                            if (!sentBatteryWarnChatMsg) {
                                Log.d(TAG, "battery: $batt")
                                val loc = //dataRepository.latestLocation.value ?:
                                    Location("manual").apply {
                                        latitude = 0.0
                                        longitude = 0.0
                                        altitude = 0.0
                                        accuracy = 999999.0f
                                        verticalAccuracyMeters = 999999.0f
                                        time = System.currentTimeMillis()
                                    }
                                val chatMessage = buildChatCotMessage(
                                    loc,
                                    "Battery Warning: ${MainActivity.callsign} battery less than ${MainActivity.battWarnPercent}% (actual=$batt%)",
                                    "All Chat Rooms",
                                    "All Chat Rooms"
                                )
                                sentBatteryWarnChatMsg = true
                                sendDataTAKServers(chatMessage, true)
                                Log.d(TAG, "Sending chat msg to 'All Chat Rooms': $chatMessage")
                            }
                        } else {
                            if (!sentBatteryAlertChatMsg) {
                                val loc = dataRepository.latestLocation.value ?:
                                Location("manual").apply {
                                    latitude = 0.0
                                    longitude = 0.0
                                    altitude = 0.0
                                    accuracy = 999999.0f
                                    verticalAccuracyMeters = 999999.0f
                                    time = System.currentTimeMillis()
                                }
                                val chatMessage = buildChatCotMessage(
                                    loc,
                                    "Battery ALERT: ${MainActivity.callsign} battery less than ${MainActivity.battAlertPercent}% (actual=$batt%)",
                                    "All Chat Rooms",
                                    "All Chat Rooms"
                                )
                                sentBatteryAlertChatMsg = true
                                sendDataTAKServers(chatMessage, true)
                                Log.d(TAG, "Sending chat msg to 'All Chat Rooms': $chatMessage")
                            }
                        }
                    } else {
                        sentBatteryWarnChatMsg = false
                        sentBatteryAlertChatMsg = false
                    }
                }
            }
        }
    }

    private fun startMarkerPipelines() {
        startDropCollector()
        startClearCollector()
        startDropListCollector()
    }

    private fun startDropCollector() {
        serviceScope.launch(Dispatchers.IO) {
            dataRepository.dropRequests.collect { req ->
                Log.i(TAG, "Received drop request...")
                val loc = dataRepository.latestLocation.value
                if (sendMarkerReady()) {
                    sendDropNow(req , loc!!)
                    sendPendingMarkerDrops()
                } else {
                    EventBus.getDefault().post(LocationUpdateRequired())
                    enqueueDrop(req)
                }
            }
        }
    }

    // TODO: I think we can only send the marker cot message once we have established connection and sent atleast one PLI message. The queue doesn't seem to flush and send to server on reconnects. Needs more work. MVP works.
    private fun sendMarkerReady(): Boolean {
        val loc = dataRepository.latestLocation.value
        val fresh = (System.currentTimeMillis() - lastLocationUpdateRcvd) < FRESH_GPS_INTERVAL
        return if (TAKConnectService.isAnyTakConnected && (null != loc) && fresh) {
            true
        } else false
    }

    private fun startDropListCollector() {
        serviceScope.launch(Dispatchers.IO) {
            dataRepository.droppedMarkerList.collect { list ->
                logMarkerDropList(list)
            }
        }
    }

    private fun logMarkerDropList(list: List<DataRepository.DroppedMarker>) {
        if (list.isEmpty()) {
            Log.v(TAG, "Dropped Marker List is empty")
        } else {
            Log.v(TAG, "Dropped Marker List updated (${list.size} items):")
            list.forEachIndexed { index, marker ->
                Log.v(
                    TAG,
                    "[$index] uid=${marker.uid}, type=${marker.markerType}, time=${marker.timeDropped}"
                )
            }
        }
    }

    private fun startClearCollector() {
        serviceScope.launch(Dispatchers.IO) {
            dataRepository.clearRequests.collect { req ->
                when (req) {
                    is DataRepository.ClearRequest.Specific -> handleClearUid(req.uid)
                    DataRepository.ClearRequest.Last -> {
                        val last = dataRepository.droppedMarkerList.value.maxByOrNull { it.timeDropped }
                        if (last != null) handleClearUid(last.uid)
                    }
                    DataRepository.ClearRequest.All -> handleClearAll()
                }
            }
        }
    }

    // Point used at DROP time, reused on CLEAR
    data class CotMarkerInfo(
        val lat: Double,
        val lon: Double,
        val hae: Double?,
        val ce: Float?,
        val le: Float?,
        val type: String,
        val icon: String
    )
    private val markerDropMap = java.util.concurrent.ConcurrentHashMap<String, CotMarkerInfo>()

    private val pendingDropQueue = ArrayDeque<DataRepository.DropRequest>()

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
                //val logStr = "Somehow location update did not happen within the last currReportingIntervalInSecs ($currReportingIntervalInSecs) w/ last update ${((System.currentTimeMillis()-lastLocationUpdateRcvd)/1000.0)}secs ago. Sending Biometric Cot via cotDispatchRunnable"
                //Log.v(TAG, logStr)
                //writeToLogFileLocation(logFile, logStr)
                buildAndSendPhysioLocationCotMsg()
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
            //val logString = "Using GPS: gpsHorizAcc($ceActual) w/ closestBleBeaconUpdateTime($closestBleBeaconUpdateTime); currTime($startTimeInMs); diff (${abs(startTimeInMs - closestBleBeaconUpdateTime)})"
            //Log.d(TAG, logString)
            //writeToLogFileLocation(logFile, logString)
        }
        return BleLocationOutput(latActual, lonActual, haeActual, ceActual)
    }

    data class PhysioData(
        val heartRate: Int,
        val skinTemp: Float,
        val exertion: Double,
        val battery: Int?,
        val steps: Long,
        val calories: Double,
        val floors: Double,
        val elevationM: Double?,
        val weight: Int,
        val age: Int
    )

    private fun buildConciseLocationPhysioCot(location: Location?, physioData: PhysioData): String {
        // Grab unique device identifier from MainActivity
        val uid = MainActivity.device_uid

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

        // Raw coords & altitude
        var lat = location?.latitude  ?: 0.0
        var lon = location?.longitude ?: 0.0
        var hae = location?.altitude  ?: 0.0

        // Strip precision & default to “9999” if NaN
        var ceTrim     = location?.accuracy?.takeIf { !it.isNaN() }?.toInt() ?: 9999
        val leTrim     = location?.verticalAccuracyMeters?.takeIf { !it.isNaN() }?.toInt() ?: 9999
        val courseTrim = location?.bearing?.toInt() ?: 0
        val speedTrim  = location?.speed?.toInt()   ?: 0

        // Account for Ble Beacons
        if (MainActivity.enableBleBeaconService == true) {
            val bleLocationOutput = mapLocationAgainstClosestBleBeacon(startTimeInMs, lat, lon, hae, ceTrim*1.0F)
            lat = bleLocationOutput.lat; lon = bleLocationOutput.lon; hae = bleLocationOutput.hae; ceTrim = bleLocationOutput.ce.toInt()
        }

        // Pull out physio values
        val hr          = physioData.heartRate
        val skt         = String.format("%.1f", physioData.skinTemp).let { str -> if (str.endsWith(".0")) str.replace(".0", "") else str }
        val exert       = String.format("%.2f", physioData.exertion).let { str -> if (str.endsWith(".00")) str.replace(".00", "") else str }
        val bat         = physioData.battery
        val steps       = physioData.steps
        val calories    = physioData.calories
        val floors      = physioData.floors
        val elevationM  = physioData.elevationM

        // Inject physio data into remarks

        val remarks = if (MainActivity.enablePhysiologicalServices && (MainActivity.enableActivityRecognitionService || MainActivity.enableDynamicReportingStrategy)) {
            /*
            var activity : ActivityRecognized = ActivityRecognized.UNKNOWN
            runBlocking {
                activity = dataRepository.latestActivityRecognized.value
            }

             */
            buildString {
                append("HR:$hr;")
                append("SknTmp:$skt;")
                append("Exert:$exert;")
                append("Bat:${bat ?: -1};")
                append("Steps:$steps;")
                append("Cals:${calories.roundToInt()};")
                append("Floors:${floors.roundToInt()};")
                append("ElevGain(m):${elevationM?.roundToInt() ?: 0}")
                //append(";Act:${activity.name}($currReportingIntervalInSecs)")
            }
        } else {
            buildString {
                append("HR:$hr;")
                append("SknTmp:$skt;")
                append("Exert:$exert;")
                append("Bat:${bat ?: -1}")
            }
        }

        val hailCotDetailString = buildHailDetail(physioData)

        // Build the cot XML
        val cot = if (enableBatdokCot) {
            val sktCelcius = String.format("%.1f", convertFarenheitToCelsius(physioData.skinTemp)).let { str -> if (str.endsWith(".0")) str.replace(".0", "") else str }
            var batdokStr = if (-1 == physioData.heartRate) {
                if (-1F == physioData.skinTemp) {
                    ""
                } else {
                    """<_atmist_ age="${physioData.age}" timeOfIncident="$startTime"><vitalSign index="0" timestamp="$startTime">(Temp,$skt (F) $sktCelcius (C),$startTimeInMs)</vitalSign></_atmist_>""".trimIndent()

                }
            } else {
                if (-1F == physioData.skinTemp) {
                    """<_atmist_ age="${physioData.age}" timeOfIncident="$startTime"><vitalSign index="0" timestamp="$staleTime">(HR,$hr,$startTimeInMs)</vitalSign></_atmist_>""".trimIndent()

                } else {
                    """<_atmist_ age="${physioData.age}" timeOfIncident="$startTime"><vitalSign index="0" timestamp="$staleTime">(HR,$hr,$startTimeInMs)</vitalSign><vitalSign index="1" timestamp="$startTime">(Temp,$skt (F) $sktCelcius (C),$startTimeInMs)</vitalSign></_atmist_>""".trimIndent()
                }
            }
            """<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g'><point lat='${"%.6f".format(lat)}' lon='${"%.6f".format(lon)}' hae='${"%.1f".format(hae)}' ce='$ceTrim' le='$leTrim'/><detail><remarks>$remarks</remarks>$batdokStr<contact endpoint='*:-1:stcp' callsign='$callsign'/><__group role='$role' name='$teamValue'/><track course='$courseTrim' speed='$speedTrim'/>$hailCotDetailString</detail></event>""".trimIndent()
        } else {
            """<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g'><point lat='${"%.6f".format(lat)}' lon='${"%.6f".format(lon)}' hae='${"%.1f".format(hae)}' ce='$ceTrim' le='$leTrim'/><detail><remarks>$remarks</remarks><contact endpoint='*:-1:stcp' callsign='$callsign'/><__group role='$role' name='$teamValue'/><track course='$courseTrim' speed='$speedTrim'/>$hailCotDetailString</detail></event>""".trimIndent()
        }
        //Log.i(TAG, "Size: ${cot.length} Bytes \n $cot")
        //Log.i(TAG, "===> HailCotDetail: $hailCotDetailString")


        if (enableEsriEndpoint) {
            val isWorn = if (-1 == physioData.heartRate) { false } else { true }
            esriGeojsonMsg =
                if (MainActivity.enablePhysiologicalServices && (MainActivity.enableActivityRecognitionService || MainActivity.enableDynamicReportingStrategy)) {
                    """
        { "type": "FeatureCollection", "features": [ { "type": "Feature", "id": "$uid", "properties": { "timeutc": "$startTime", "staletimeutc": "$staleTime", "callsign": "$callsign", "role": "$role", "color": "$teamValue", "phone": "+11234567890", "model": "WEAROS", "device": "Samsung Galaxy Watch", "hae": $hae, "horizaccuracy": $ceTrim, "vertaccuracy": $leTrim, "heading": $courseTrim, "speed": $speedTrim, "batt": ${physioData.battery}, "biometrics": [ { "model": "WEAROS", "device": "Samsung Galaxy Watch", "isworn": $isWorn, "hr": ${physioData.heartRate}, "hribi": -1, "hrv": -1, "skintemp": $skt, "ambienttemp": -1, "coretemp": -1, "spo2": -1, "spo2measuretimeutc": "", "vo2": -1, "vo2measuretimeutc": "", "vo2max": -1, "vo2maxmeasuretimeutc": "", "sweatloss": -1, "sweatlossduration": -1, "sweatlossmeasuretimeutc": "", "sleepscore": -1, "sleepduration": -1, "deepsleepduration": -1, "sleepscoremeasuretimeutc": "", "energyscore": -1, "energyscoremeasuretimeutc": "", "steps": ${physioData.steps}, "floors": ${physioData.floors.roundToInt()}, "calories": ${physioData.calories.roundToInt()}, "exertion": $exert, "fatmass": -1, "musclemass": -1, "biameasuretimeutc": "", "batt": ${physioData.battery} } ], "alerts" : [ ], "resolvedalerts" : [ ] }, "geometry": { "type": "Point", "coordinates": [ $lon, $lat ] } } ] }
        """.trimIndent()

                } else {
                    """
        { "type": "FeatureCollection", "features": [ { "type": "Feature", "id": "$uid", "properties": { "timeutc": "$startTime", "staletimeutc": "$staleTime", "callsign": "$callsign", "role": "$role", "color": "$teamValue", "phone": "+11234567890", "model": "WEAROS", "device": "Samsung Galaxy Watch", "hae": $hae, "horizaccuracy": $ceTrim, "vertaccuracy": $leTrim, "heading": $courseTrim, "speed": $speedTrim, "batt": ${physioData.battery}, "biometrics": [ { "model": "WEAROS", "device": "Samsung Galaxy Watch", "isworn": $isWorn, "hr": ${physioData.heartRate}, "hribi": -1, "hrv": -1, "skintemp": $skt, "ambienttemp": -1, "coretemp": -1, "spo2": -1, "spo2measuretimeutc": "", "vo2": -1, "vo2measuretimeutc": "", "vo2max": -1, "vo2maxmeasuretimeutc": "", "sweatloss": -1, "sweatlossduration": -1, "sweatlossmeasuretimeutc": "", "sleepscore": -1, "sleepduration": -1, "deepsleepduration": -1, "sleepscoremeasuretimeutc": "", "energyscore": -1, "energyscoremeasuretimeutc": "", "steps": -1, "floors": -1, "calories": -1, "exertion": $exert, "fatmass": -1, "musclemass": -1, "biameasuretimeutc": "", "batt": ${physioData.battery} } ], "alerts" : [ ], "resolvedalerts" : [ ] }, "geometry": { "type": "Point", "coordinates": [ $lon, $lat ] } } ] }
        """.trimIndent()
                }

            Log.d(TAG, "esriGeojsonMsg: $esriGeojsonMsg")
        }

        if (enableTabletCommandEndpoint) {
            val isWorn = if (-1 == physioData.heartRate) { false } else { true }
            tabletCommandJsonMsg = if (MainActivity.enablePhysiologicalServices && (MainActivity.enableActivityRecognitionService || MainActivity.enableDynamicReportingStrategy)) {
                """
                {"id":"$uid","timeutc":"$startTime","staletimeutc":"$staleTime","callsign":"$callsign","role":"$role","color":"$teamValue","phone":"+13014058648","model":"WEAROS","device":"Samsung Galaxy Watch","lat":$lat,"lon":$lon,"hae":$hae,"horizaccuracy":$ceTrim,"vertaccuracy":$leTrim,"heading":$courseTrim,"speed":$speedTrim,"batt":${physioData.battery},"biometrics":[{"model":"WEAROS","device":"Samsung Galaxy Watch","isworn":$isWorn,"hr":${physioData.heartRate},"hribi":-1,"hrv":-1,"skintemp":$skt,"ambienttemp":-1,"coretemp":-1,"spo2":-1,"spo2measuretimeutc":"","vo2":-1,"vo2measuretimeutc":"","vo2max":-1,"vo2maxmeasuretimeutc":"","sweatloss":-1,"sweatlossduration":-1,"sweatlossmeasuretimeutc":"","sleepscore":-1,"sleepduration":-1,"deepsleepduration":-1,"sleepscoremeasuretimeutc":"","energyscore":-1,"energyscoremeasuretimeutc":"","steps":${physioData.steps},"floors":${physioData.floors.roundToInt()},"calories":${physioData.calories.roundToInt()},"exertion":$exert,"fatmass":-1,"musclemass":-1,"biameasuretimeutc":"","batt":${physioData.battery}}],"alerts":[],"resolvedalerts":[]}
                """.trimIndent()
            } else {
                """
                {"id":"$uid","timeutc":"$startTime","staletimeutc":"$staleTime","callsign":"$callsign","role":"$role","color":"$teamValue","phone":"+13014058648","model":"WEAROS","device":"Samsung Galaxy Watch","lat":$lat,"lon":$lon,"hae":$hae,"horizaccuracy":$ceTrim,"vertaccuracy":$leTrim,"heading":$courseTrim,"speed":$speedTrim,"batt":${physioData.battery},"biometrics":[{"model":"WEAROS","device":"Samsung Galaxy Watch","isworn":$isWorn,"hr":${physioData.heartRate},"hribi":-1,"hrv":-1,"skintemp":$skt,"ambienttemp":-1,"coretemp":-1,"spo2":-1,"spo2measuretimeutc":"","vo2":-1,"vo2measuretimeutc":"","vo2max":-1,"vo2maxmeasuretimeutc":"","sweatloss":-1,"sweatlossduration":-1,"sweatlossmeasuretimeutc":"","sleepscore":-1,"sleepduration":-1,"deepsleepduration":-1,"sleepscoremeasuretimeutc":"","energyscore":-1,"energyscoremeasuretimeutc":"","steps":-1,"floors":-1,"calories":-1,"exertion":$exert,"fatmass":-1,"musclemass":-1,"biameasuretimeutc":"","batt":${physioData.battery}}],"alerts":[],"resolvedalerts":[]}
                """.trimIndent()
            }
            //Log.d(TAG, "tabletCommandJsonMsg: $tabletCommandJsonMsg")
        }


        return cot
    }

    private fun collectHailFlows() {
        serviceScope.launch {
            dataRepository.hailLicenseInfoFlow.first()?.let { HailService.hailLicenseInfo = it }
        }

        // @Molly this is how we can collect the button state, so you know when it becomes false again.
        serviceScope.launch {
            dataRepository.hailCheckLicenseButtonPressed
                .collect { pressed ->
                    if (pressed) {
                        dataRepository.hailCheckLicenseButtonPressed.emit(false)
                        if (!dataRepository.getEnableHailService.first()) {
                            return@collect
                        }
                        Log.d(TAG,"Hail Check Button pressed ... Entering CHECKING state")
                        checkLicense {
                            checkLocalHailLicense()
                        }
                    }
                }
        }

        // gives the dispatchers
        serviceScope.launch {
            dataRepository.getCombatId
                .collect { id -> combatId = id ?: 0 }
        }

        serviceScope.launch {
            dataRepository.hailInfoStateFlow
                .collect { info ->
                    lgGate("""
                    hailInfoStateFlow changed:
                    %-20s %s
                    %-20s %s
                    %-20s %s
                    %-20s %s
                    %-20s %s
                    %-20s %s
                    """.trimIndent().format(
                        "Pairing code:", info?.pairingCode ?: "null",
                        "Expiration date:", info?.expirationDate ?: "null",
                        "Soft exp. date:", info?.softExpirationDate ?: "null",
                        "Account number:", info?.accountNumber ?: "null",
                        "License ID:", info?.licenseId ?: "null",
                        "Device ID:", info?.deviceId ?: "null"
                    )
                    )
                }
        }
    }

    private fun buildHailDetail(physioData: PhysioData): String {
        val licenseValid = HailService.isHailLicenseValid() // so we call this everytime.
        if (!dataRepository.buildHailDetail.value || !licenseValid) {
            return ""
        } else {
            val hailEncoded: Result<String> = HailService.encodeHailDetail(combatId, callsign) { hail ->
                hail.setHeartRate(physioData.heartRate.toShort())
                hail.setSkinTemp(physioData.skinTemp.toInt().toShort())
                hail.setWeight(physioData.weight.toShort())
                hail.setAge(physioData.age.toByte())
                if (inManualAlertingState) {
                    hail.setMedevent(true)
                } else {
                    hail.setMedevent(false)
                }
            }

            val cotDetail = hailEncoded.fold(
                onSuccess = { encoded ->
                    Utils.CotUtils.CotDetail(HailDetail.HAIL_DETAIL)
                        .setAttribute("version", HailDetail.HAIL_DETAIL_ENCODE_VERSION.toString())
                        .setInnerText(encoded)
                },
                onFailure = { e ->
                    Log.e("HAIL", "Error creating Hail CotDetail", e)
                    null
                }
            )
            return cotDetail?.toXmlString() ?: ""
        }
    }

    /*
     * Full GeoJSON sent to ESRI:
     * { "type": "FeatureCollection", "features": [ { "type": "Feature", "id": "WEAROS_cffb09973cda1813", "properties": { "timeutc": "2025-05-14T19:21:51.401Z", "staletimeutc": "2025-05-14T19:27:57.401Z", "callsign": "SIERRAPAPA-WTC-1", "role": "Engine 1 - Firefighter", "color": "orange", "phone": "+13014058648", "model": "WEAROS", "device": "Samsung Galaxy Watch 7", "hae": 161.3000030517578, "horizaccuracy": 12.191, "vertaccuracy": 1.9328669, "heading": 359.3, "speed": 1.3, "batt": 17, "biometrics": [ { "model": "WEAROS", "device": "Samsung Galaxy Watch 7 (44mm)", "isworn": true, "hr": 78, "hribi": -1, "hrv": -1, "skintemp": -1, "ambienttemp": -1, "coretemp": -1, "spo2": -1, "spo2measuretimeutc": "", "vo2": -1, "vo2measuretimeutc": "", "vo2max": -1, "vo2maxmeasuretimeutc": "", "sweatloss": -1, "sweatlossduration": -1, "sweatlossmeasuretimeutc": "", "sleepscore": -1, "sleepduration": -1, "deepsleepduration": -1, "sleepscoremeasuretimeutc": "", "energyscore": -1, "energyscoremeasuretimeutc": "", "steps": -1, "floors": -1, "calories": -1, "exertion": -1, "fatmass": -1, "musclemass": -1, "biameasuretimeutc": "", "batt": 17 } ], "alerts" : [ ], "resolvedalerts" : [ ] }, "geometry": { "type": "Point", "coordinates": [ -87.6590724, 41.8870087 ] } } ] }
     * Compact GeoJSON sent to ESRI:
     * { "type": "FeatureCollection", "features": [ { "type": "Feature", "id": "WEAROS_cffb09973cda1813", "properties": { "timeutc": "2025-05-17T19:21:51.401Z", "staletimeutc": "2025-05-17T19:27:57.401Z", "callsign": "SIERRAPAPA-WTC-1", "role": "Engine 1 - Firefighter", "color": "orange", "phone": "+13014058648", "model": "WEAROS", "device": "Samsung Galaxy Watch 7", "hae": 161.3000030517578, "horizaccuracy": 12.191, "vertaccuracy": 1.9328669, "heading": 359.3, "speed": 1.3, "batt": 17, "biometrics": [ { "model": "WEAROS", "device": "Samsung Galaxy Watch 7 (44mm)", "isworn": true, "hr": 78 } ], "alerts" : [ ], "resolvedalerts" : [ ] }, "geometry": { "type": "Point", "coordinates": [ -87.6590724, 41.8870087 ] } } ] }
    */
    private fun buildVerboseLocationPhysioCot(location: Location?, physioData: PhysioData): String {
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
        val skinTempStr = String.format("%.2f", physioData.skinTemp).let { str -> if (str.endsWith(".00")) str.replace(".00", "") else str }
        val exertionStr = String.format("%.2f", physioData.exertion).let { str -> if (str.endsWith(".00")) str.replace(".00", "") else str }

        if (enableEsriEndpoint) {
            val isWorn = if (-1 == physioData.heartRate) { false } else { true }

            esriGeojsonMsg =
                if (MainActivity.enablePhysiologicalServices && (MainActivity.enableActivityRecognitionService || MainActivity.enableDynamicReportingStrategy)) {
                    """
        { "type": "FeatureCollection", "features": [ { "type": "Feature", "id": "$uid", "properties": { "timeutc": "$startTime", "staletimeutc": "$staleTime", "callsign": "$callsign", "role": "$role", "color": "$teamValue", "phone": "+11234567890", "model": "WEAROS", "device": "Samsung Galaxy Watch", "hae": $haeActual, "horizaccuracy": $ceActual, "vertaccuracy": $leActual, "heading": $bearingActual, "speed": $speedActual, "batt": ${physioData.battery}, "biometrics": [ { "model": "WEAROS", "device": "Samsung Galaxy Watch", "isworn": $isWorn, "hr": ${physioData.heartRate}, "hribi": -1, "hrv": -1, "skintemp": $skinTempStr, "ambienttemp": -1, "coretemp": -1, "spo2": -1, "spo2measuretimeutc": "", "vo2": -1, "vo2measuretimeutc": "", "vo2max": -1, "vo2maxmeasuretimeutc": "", "sweatloss": -1, "sweatlossduration": -1, "sweatlossmeasuretimeutc": "", "sleepscore": -1, "sleepduration": -1, "deepsleepduration": -1, "sleepscoremeasuretimeutc": "", "energyscore": -1, "energyscoremeasuretimeutc": "", "steps": ${physioData.steps}, "floors": ${physioData.floors.roundToInt()}, "calories": ${physioData.calories.roundToInt()}, "exertion": $exertionStr, "fatmass": -1, "musclemass": -1, "biameasuretimeutc": "", "batt": ${physioData.battery} } ], "alerts" : [ ], "resolvedalerts" : [ ] }, "geometry": { "type": "Point", "coordinates": [ $lonActual, $latActual ] } } ] }
        """.trimIndent()

                } else {
                    """
        { "type": "FeatureCollection", "features": [ { "type": "Feature", "id": "$uid", "properties": { "timeutc": "$startTime", "staletimeutc": "$staleTime", "callsign": "$callsign", "role": "$role", "color": "$teamValue", "phone": "+11234567890", "model": "WEAROS", "device": "Samsung Galaxy Watch", "hae": $haeActual, "horizaccuracy": $ceActual, "vertaccuracy": $leActual, "heading": $bearingActual, "speed": $speedActual, "batt": ${physioData.battery}, "biometrics": [ { "model": "WEAROS", "device": "Samsung Galaxy Watch", "isworn": $isWorn, "hr": ${physioData.heartRate}, "hribi": -1, "hrv": -1, "skintemp": $skinTempStr, "ambienttemp": -1, "coretemp": -1, "spo2": -1, "spo2measuretimeutc": "", "vo2": -1, "vo2measuretimeutc": "", "vo2max": -1, "vo2maxmeasuretimeutc": "", "sweatloss": -1, "sweatlossduration": -1, "sweatlossmeasuretimeutc": "", "sleepscore": -1, "sleepduration": -1, "deepsleepduration": -1, "sleepscoremeasuretimeutc": "", "energyscore": -1, "energyscoremeasuretimeutc": "", "steps": -1, "floors": -1, "calories": -1, "exertion": $exertionStr, "fatmass": -1, "musclemass": -1, "biameasuretimeutc": "", "batt": ${physioData.battery} } ], "alerts" : [ ], "resolvedalerts" : [ ] }, "geometry": { "type": "Point", "coordinates": [ $lonActual, $latActual ] } } ] }
        """.trimIndent()
                }

            Log.d(TAG, "esriGeojsonMsg: $esriGeojsonMsg")
        }

        if (enableTabletCommandEndpoint) {
            val isWorn = if (-1 == physioData.heartRate) { false } else { true }
            tabletCommandJsonMsg = if (MainActivity.enablePhysiologicalServices && (MainActivity.enableActivityRecognitionService || MainActivity.enableDynamicReportingStrategy)) {
                """
                {"id":"$uid","timeutc":"$startTime","staletimeutc":"$staleTime","callsign":"$callsign","role":"$role","color":"$teamValue","phone":"+13014058648","model":"WEAROS","device":"Samsung Galaxy Watch","lat":$latActual,"lon":$lonActual,"hae":$haeActual,"horizaccuracy":$ceActual,"vertaccuracy":$leActual,"heading":$bearingActual,"speed":$speedActual,"batt":${physioData.battery},"biometrics":[{"model":"WEAROS","device":"Samsung Galaxy Watch","isworn":$isWorn,"hr":${physioData.heartRate},"hribi":-1,"hrv":-1,"skintemp":$skinTempStr,"ambienttemp":-1,"coretemp":-1,"spo2":-1,"spo2measuretimeutc":"","vo2":-1,"vo2measuretimeutc":"","vo2max":-1,"vo2maxmeasuretimeutc":"","sweatloss":-1,"sweatlossduration":-1,"sweatlossmeasuretimeutc":"","sleepscore":-1,"sleepduration":-1,"deepsleepduration":-1,"sleepscoremeasuretimeutc":"","energyscore":-1,"energyscoremeasuretimeutc":"","steps":${physioData.steps},"floors":${physioData.floors.roundToInt()},"calories":${physioData.calories.roundToInt()},"exertion":$exertionStr,"fatmass":-1,"musclemass":-1,"biameasuretimeutc":"","batt":${physioData.battery}}],"alerts":[],"resolvedalerts":[]}
                """.trimIndent()
            } else {
                """
                {"id":"$uid","timeutc":"$startTime","staletimeutc":"$staleTime","callsign":"$callsign","role":"$role","color":"$teamValue","phone":"+13014058648","model":"WEAROS","device":"Samsung Galaxy Watch","lat":$latActual,"lon":$lonActual,"hae":$haeActual,"horizaccuracy":$ceActual,"vertaccuracy":$leActual,"heading":$bearingActual,"speed":$speedActual,"batt":${physioData.battery},"biometrics":[{"model":"WEAROS","device":"Samsung Galaxy Watch","isworn":$isWorn,"hr":${physioData.heartRate},"hribi":-1,"hrv":-1,"skintemp":$skinTempStr,"ambienttemp":-1,"coretemp":-1,"spo2":-1,"spo2measuretimeutc":"","vo2":-1,"vo2measuretimeutc":"","vo2max":-1,"vo2maxmeasuretimeutc":"","sweatloss":-1,"sweatlossduration":-1,"sweatlossmeasuretimeutc":"","sleepscore":-1,"sleepduration":-1,"deepsleepduration":-1,"sleepscoremeasuretimeutc":"","energyscore":-1,"energyscoremeasuretimeutc":"","steps":-1,"floors":-1,"calories":-1,"exertion":$exertionStr,"fatmass":-1,"musclemass":-1,"biameasuretimeutc":"","batt":${physioData.battery}}],"alerts":[],"resolvedalerts":[]}
                """.trimIndent()
            }
            //Log.d(TAG, "tabletCommandJsonMsg: $tabletCommandJsonMsg")
        }

        // TODO: set phone number to actual phone number
//        return """
//        <?xml version="1.0" encoding="UTF-8" standalone='yes'?><event version="2.0" uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g' access='Undefined'><point lat='$latActual' lon='$lonActual' hae='$haeActual' ce='$ceActual' le='$leActual' /><detail><remarks>HR: ${physioData.heartRate} bpm; SkinTemp: ${skinTempStr}ºF; Batt: ${physioData.battery}%</remarks><status battery='${physioData.battery}'/><contact endpoint='*:-1:stcp' phone='11234567890' callsign='$callsign'/><__group role='$role' name='$teamValue'/><biometrics><device><model>WEAROS</model><uid>$uid</uid><hr>${physioData.heartRate}</hr><skt>$skinTempStr</skt><bat>${physioData.battery}</bat></device></biometrics><track course='$bearingActual' speed='$speedActual'/></detail></event>
//        """.trimIndent()
        val cot = if (MainActivity.enablePhysiologicalServices && (MainActivity.enableActivityRecognitionService || MainActivity.enableDynamicReportingStrategy)) {
            """<?xml version="1.0" encoding="UTF-8" standalone='yes'?><event version="2.0" uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g' access='Undefined'><point lat='$latActual' lon='$lonActual' hae='$haeActual' ce='$ceActual' le='$leActual' /><detail><remarks>HR: ${physioData.heartRate} bpm; SkinTemp: $skinTempStr ºF; Exertion: $exertionStr; Batt: ${physioData.battery}%; Steps:${physioData.steps}; Cals:${physioData.calories.roundToInt()}; Floors:${physioData.floors.roundToInt()}; ElevGain(m):${physioData.elevationM?.roundToInt() ?: 0}</remarks><status battery='${physioData.battery}'/><contact endpoint='*:-1:stcp' phone='11234567890' callsign='$callsign'/><__group role='$role' name='$teamValue'/><biometrics><device><model>WEAROS</model><uid>$uid</uid><hr>${physioData.heartRate}</hr><skt>$skinTempStr</skt><exert>$exertionStr</exert><bat>${physioData.battery}</bat></device></biometrics><track course='$bearingActual' speed='$speedActual'/></detail></event>""".trimIndent()
        } else {
            """<?xml version="1.0" encoding="UTF-8" standalone='yes'?><event version="2.0" uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g' access='Undefined'><point lat='$latActual' lon='$lonActual' hae='$haeActual' ce='$ceActual' le='$leActual' /><detail><remarks>HR: ${physioData.heartRate} bpm; SkinTemp: $skinTempStr ºF; Exertion: $exertionStr; Batt: ${physioData.battery}%</remarks><status battery='${physioData.battery}'/><contact endpoint='*:-1:stcp' phone='11234567890' callsign='$callsign'/><__group role='$role' name='$teamValue'/><biometrics><device><model>WEAROS</model><uid>$uid</uid><hr>${physioData.heartRate}</hr><skt>$skinTempStr</skt><exert>$exertionStr</exert><bat>${physioData.battery}</bat></device></biometrics><track course='$bearingActual' speed='$speedActual'/></detail></event>""".trimIndent()
        }
        //Log.i(TAG, "Size: ${cot.length} Bytes \n$cot")
        return cot
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public fun onEmergencyMessageEvent(event: CustomEmergencyEvent) {
        manualSosReceivedCategory = event.category
        manualSosReceivedDescription = event.description
        manualSosReceivedSendSms = event.sendSms
        val currTime = System.currentTimeMillis()
        alertOrCancel = event.category == CAT_SOS_ALERT_CANCELLED
        if (manualSosReceivedCategory == DESC_SOS_ALERT) {
            inManualAlertingState = true
            serviceScope.launch {
                dataRepository.storeIsAlerting(inManualAlertingState)
            }
        } else {
            inManualAlertingState = false
            serviceScope.launch {
                dataRepository.storeIsAlerting(inManualAlertingState)
            }
        }

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

    private fun buildChatCotMessage(location: Location, chatMsg: String, dstUid: String, dstChatroom: String): String {
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
        var latActual   = location.latitude
        var lonActual   = location.longitude
        var haeActual   = location.altitude
        var ceActual    = location.accuracy
        if (ceActual.isNaN()) ceActual = 9999999.0F
        var leActual : Float = location.verticalAccuracyMeters
        if (leActual.isNaN()) leActual = 9999999.0F

        // Ble Beacon
        if (MainActivity.enableBleBeaconService) {
            val bleLocationOutput = mapLocationAgainstClosestBleBeacon(startTimeInMs, latActual, lonActual, haeActual, ceActual)
            latActual = bleLocationOutput.lat; lonActual = bleLocationOutput.lon; haeActual = bleLocationOutput.hae; ceActual = bleLocationOutput.ce
        }

        val uid = UUID.randomUUID().toString()
        val linkUid = MainActivity.device_uid
        //val dstUid = "ANDROID-dfd8f500aac449de"
        //val dstChatroom = "SIERRAPAPA-PHN2" // Callsign
        //val dstUid = "ANDROID-5c4916b8baffaf8d"
        //val dstChatroom = "SIERRAPAPA-PHN0" // Callsign
        //val dstUid = "TAKBOT-12345"
        //val dstChatroom = "takGPT" // Callsign
        //val dstUid = "All Chat Rooms"
        //val dstChatroom = "All Chat Rooms" // Callsign
        val martiDetails = if (dstUid.equals("All Chat Rooms")) {
            ""
        } else {
            """
            <marti> <dest callsign='$dstChatroom'/> </marti>
            """.trimIndent()
        }

        return """
        <?xml version='1.0' encoding='UTF-8' standalone='yes'?>
        <event version='2.0' uid='GeoChat.$linkUid.$dstUid.$uid' type='b-t-f' time='$startTime' start='$startTime' stale='$startTime' how='h-g-i-g-o' access='Undefined'>
        	<point lat='$latActual' lon='$lonActual' hae='$haeActual' ce='$ceActual' le='$leActual' />
        	<detail>
        		<__chat parent='RootContactGroup' groupOwner='false' messageId='$uid' chatroom='$dstChatroom' id='$dstUid' senderCallsign='$callsign'>
                    <chatgrp uid0='$linkUid' uid1='$dstUid' id='$dstUid'/>
                </__chat>
                <link uid='$linkUid' type='a-f-G-U-C' relation='p-p'/>
                <remarks source='$linkUid' to='$dstUid' time='$startTime'>$chatMsg</remarks>
                $martiDetails
        	</detail>
        </event>
        """.trimIndent()
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
        var latActual   = location.latitude
        var lonActual   = location.longitude
        var haeActual   = location.altitude
        var ceActual    = location.accuracy
        if (ceActual.isNaN()) ceActual = 9999999.0F
        var leActual : Float = location.verticalAccuracyMeters
        if (leActual.isNaN()) leActual = 9999999.0F

        // Ble Beacon
        if (MainActivity.enableBleBeaconService) {
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
            sosAlertUid
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

    private fun buildMarkerCotMessage(uid: String, markerType: String, location: Location, heartRate: Int, battery: Int): String {
        dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))
        val calendar: Calendar = Calendar.getInstance()
        calendar.setTime(Date()) // Set the current time
        val startTime = dateTimeFormatter.format(calendar.time)
        val startTimeInMs = calendar.timeInMillis
        calendar.add(Calendar.YEAR, 1) // add 1 year to the current time for 'stale time', ie when the cot expires. Markers should last for a long time.
        val staleTime = dateTimeFormatter.format(calendar.time)

        // Marker with Time no Date.
        val markerCallsignTimeFormatter = SimpleDateFormat("HHmmss'Z'")
        markerCallsignTimeFormatter.timeZone = TimeZone.getTimeZone("UTC")
        val markerCallsignAppendTime = markerCallsignTimeFormatter.format(calendar.time)
        val markerCallsign_Time = "${callsign}_$markerCallsignAppendTime"

        // TODO: Check if these really don't need the null safety check. If not, direct injection into the cot XML is more readable.
        var latActual   = location.latitude
        var lonActual   = location.longitude
        var haeActual   = location.altitude
        var ceActual    = location.accuracy
        if (ceActual.isNaN()) ceActual = 9999999.0F
        var leActual : Float = location.verticalAccuracyMeters
        if (leActual.isNaN()) leActual = 9999999.0F

        // Ble Beacon
        if (MainActivity.enableBleBeaconService) {
            val bleLocationOutput = mapLocationAgainstClosestBleBeacon(startTimeInMs, latActual, lonActual, haeActual, ceActual)
            latActual = bleLocationOutput.lat; lonActual = bleLocationOutput.lon; haeActual = bleLocationOutput.hae; ceActual = bleLocationOutput.ce
        }

        val linkUid = MainActivity.device_uid

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
                <contact callsign="$markerCallsign_Time"/>
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
                            <__group role='$role' name='$teamValue'/>
                            <biometrics hr='$heartRate'/>
                            <status battery='$battery' model='WEAROS'/>
                            <track course='${location.bearing}' speed='${location.speed}'/>
                        </detail>
                    </event>
                """.trimIndent()
         */
    }

    private fun buildClearMarkerCotMessage(uid: String, cotInfo: CotMarkerInfo?): String {
        dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))
        val calendar: Calendar = Calendar.getInstance()
        calendar.setTime(Date())   // Set the current time
        calendar.add(Calendar.SECOND, -4)   // to clear the cot, we gotta make it stale.
        val startTime = dateTimeFormatter.format(calendar.time)
        calendar.add(Calendar.SECOND, -3)   // to clear the cot, we gotta make it stale.
        val staleTime = dateTimeFormatter.format(calendar.time)

        // TODO: Figure out how to actually clear it. We may just have to send the same uid and hack the staletime.

        // Marker with Time no Date.
        val markerCallsignTimeFormatter = SimpleDateFormat("HHmmss'Z'")
        markerCallsignTimeFormatter.timeZone = TimeZone.getTimeZone("UTC")
        val markerCallsignAppendTime = markerCallsignTimeFormatter.format(calendar.time)
        val markerCallsign_Time = "${callsign}_$markerCallsignAppendTime"

        val linkUid = MainActivity.device_uid
        val type = cotInfo?.type

//        val cot = """
//        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
//        <event version="2.0" uid="$uid" type="$type-can" time="$startTime" start="$startTime" stale="$staleTime" how="h-g-i-g-o" access="Undefined">
//            <point lat="${cotInfo?.lat}" lon="${cotInfo?.lon}" hae="${cotInfo?.hae}" ce="${cotInfo?.ce}" le="${cotInfo?.le}"/>
//            <detail>
//                <status readiness="true"/>
//                <precisionlocation altsrc="SRTM1"/>
//                <link uid="$linkUid" production_time="$startTime" type="a-f-G-U-C" parent_callsign="$callsign" relation="p-p"/>
//                <color argb="-1"/>
//                <usericon iconsetpath="${cotInfo?.icon}"/>
//                <contact callsign="$markerCallsign_Time"/>
//            </detail>
//        </event>
//        """.trimIndent()

        val cot = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <event version="2.0" uid="$uid" type="$type" time="$startTime" start="$startTime" stale="$staleTime" how="h-g-i-g-o" access="Undefined">
            <point lat="${cotInfo?.lat}" lon="${cotInfo?.lon}" hae="${cotInfo?.hae}" ce="${cotInfo?.ce}" le="${cotInfo?.le}"/>
            <detail>
                <status readiness="true"/>
                <precisionlocation altsrc="SRTM1"/>
                <link uid="$linkUid" production_time="$startTime" type="a-f-G-U-C" parent_callsign="$callsign" relation="p-p"/>
                <color argb="-1"/>
                <usericon iconsetpath="${cotInfo?.icon}"/>
                <contact callsign="$markerCallsign_Time"/>
            </detail>
        </event>
        """.trimIndent()

        return cot
    }

    private suspend fun handleClearUid(uid: String) {
        val info = markerDropMap[uid]  // may be null for very old markers
        // TODO: Need to check if info is NOT null
        // TODO: Also check logic of NOT remove it from dataRepository (wait if NOT TAKConnectService.isAnyTakConnected)
        val clearXml = buildClearMarkerCotMessage(uid, info)
        Log.i(TAG, "Clearing Marker Drop [ $uid ] of type ${info?.type}")

        var sent = false
        if (DittoService.isRunning.get()) {
            EventBus.getDefault().post(DittoDocumentEvent("mapitem", clearXml))
            sent = true
        }
        if (TAKConnectService.isAnyTakConnected) {
            sendDataTAKServers(clearXml, true)
            sent = true
        }
        if (!sent) return

        dataRepository.removeByUid(uid)
        markerDropMap.remove(uid)
    }

    private suspend fun handleClearAll() {
        val uids = dataRepository.droppedMarkerList.value.map { it.uid }
        uids.forEach { handleClearUid(it) }
    }

/*
    // Used by the Location Updates EventBus
    private fun sendMarkerCotMessage() {
        serviceScope.launch(Dispatchers.IO) {
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
            val cotMessage = buildLegacyMarkerCotMsg(loc, heartRate, battery)

            // 5) Only send to TAK servers if connected
            if (TAKConnectService.isAnyTakConnected) {
                if (DittoService.isRunning.get()) {
                    EventBus.getDefault().post(DittoDocumentEvent("mapitem", cotMessage))
                    Log.v(TAG, "Marker CoT meshed into Ditto: $cotMessage")
                }
                markerDropToBeSent.set(false)
                sendDataTAKServers(cotMessage, true)
                sendMulticastDatagram(cotMessage)
//                Log.v(TAG, "Marker CoT sent to TAK: $cotMessage")
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
            sendMulticastDatagram(cotMessage)
        }
    }

 */

    private fun sendMulticastDatagram(cotXml: String) {
        // the publisher will return if not connected to wifi. Just pass it every time for POC. //TODO: design a better system for cots to be dispatched to their proper endpoints (e.g. Ditto, LAN Multicast, TAK Server, Esri)
        if (isLicenseValid()) {
            Log.i(TAG, "Trying to multicast data to the wifi network! ")
            NetworkUtils.MulticastPublisher.send(cotXml)
        }
    }

    private fun sendEmergencyAlertCotMessage(emergencyCategory: String, emergencyDesc: String, sendSms: Boolean, alertOrCancel: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
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
            val takGptChatMessage = buildChatCotMessage(loc, "$emergencyCategory: $emergencyDesc", "TAKBOT-12345","takGPT")

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
                if (!takGptChatMessage.isEmpty()) {
                    //val rawXml="<?xml version=\"1.0\" encoding=\"UTF-8\"?><event version=\"2.0\" uid=\"GeoChat.TAKBot.Chat\" type=\"b-t-f\" how=\"h-g-i-g-o\" time=\"2025-08-27T18:12:54Z\" start=\"2025-08-27T18:12:54Z\" stale=\"2025-08-28T06:12:54Z\"><point lat=\"0.0\" lon=\"0.0\" hae=\"999999.0\" ce=\"999999.0\" le=\"999999.0\"/><detail><__chat parent=\"RootContactGroup\" messageId=\"3c5eb0a3-31f8-4a32-b6cc-6916eff3cbd1\" senderCallsign=\"takGPT\" chatroom=\"takGPT\" id=\"TAKBOT-12345\"><chatgrp id=\"WEAROS-dce34c83708172e9\" uid1=\"WEAROS-dce34c83708172e9\" uid0=\"TAKBOT-12345\"/></__chat><remarks time=\"2025-08-27T18:12:54Z\" to=\"SP-Dev\">**CONFIDENCE:** HIGH - Patient’s heart rate &gt;150 bpm sustained for 30 seconds is a clinically significant tachycardia warranting immediate assessment and intervention.**CLINICAL REASONING:**1. Sustained heart rate &gt;150 bpm suggests a tachyarrhythmia that may compromise cardiac output.2. Assess for hemodynamic stability, symptoms, and possible causes (hypovolemia, hypoxia, pain, anxiety, or arrhythmia).3. Prioritize restoring effective circulation to prevent shock or cardiac arrest.4. Interventions depend on clinical context; chemical or electrical cardioversion considered for unstable supraventricular or ventricular tachycardia with appropriate diagnosis.5. Follow MARCH priority focused on Circulation due to hemodynamic compromise risk.**PRIORITY:**Circulation is the greatest immediate threat for this scenario due to the risk of hemodynamic instability or progression to cardiac arrest from sustained high-rate tachycardia.**IMMEDIATE ACTIONS:**- Assess airway patency and respiratory effort.- Provide high-flow oxygen via non-rebreather mask or appropriate delivery device.- Establish large-bore IV access.- Perform focused history/physical to identify tachycardia etiology.- Obtain a 12-lead ECG ASAP.- If unstable (hypotension, altered LOC, chest pain, signs of shock), prepare for synchronized cardioversion.- Fluid resuscitation if hypovolemia suspected.- Consider analgesia/anxiolysis if pain or anxiety contributing.**MEDICATIONS:**- Adenosine or other antiarrhythmics only after rhythm diagnosis via ECG and following ACLS protocols.- Vasopressors/inotropes per hemodynamics if indicated.- Specific medication agents and doses depend on arrhythmia type and patient status.**If additional clinical data or diagnostics become available, recommendations can be refined.**</remarks><link relation=\"p-p\" type=\"a-f-G-U-C-I\" uid=\"TAKBOT-12345\"/><_flow-tags_ TAK-Server-7b47b67360f347689e0c580092d1c325=\"2025-08-27T18:12:54Z\"/></detail></event>"
                    sendDataTAKServers(takGptChatMessage, true)
                    Log.d(TAG, "Sending takGPT chat msg: $takGptChatMessage")
                    //EventBus.getDefault().post(TakChatEvent(rawXml))
                }
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
            sendMulticastDatagram(cotMessage)
        }
    }

    private data class PhysioPli(
        val loc: Location?,
        val phys: PhysioData
    )

    private suspend fun getPhsyioAndPliData(): PhysioPli = withContext(Dispatchers.IO) {
        val heartRate  = dataRepository.latestHeartRate.value ?: -1
        val skinTempF  = dataRepository.latestSkinTemp.value ?: -1f
        val exertion   = calculateExertion(heartRate, dataRepository.getPhysiologicalMetricAge.first(), dataRepository.getRestingHeartRate.first())
        val battery    = dataRepository.latestBatteryPercentage.value
        val daily      = dataRepository.latestDailyMetrics.value

        val calories   = daily?.calories   ?: -1.0
        val floors     = daily?.floors     ?: -1.0
        val elevationM = daily?.elevationM ?: -1.0
        val steps      = daily?.steps ?: -1L

        val weight     = dataRepository.getPhysiologicalMetricWeight.first()
        val age        = dataRepository.getPhysiologicalMetricAge.first()

        val location   = dataRepository.latestLocation.value
        val physioData = PhysioData(
            heartRate  = heartRate,
            skinTemp   = skinTempF,
            exertion   = exertion,
            battery    = battery,
            steps      = steps,
            calories   = calories,
            floors     = floors,
            elevationM = elevationM,
            weight     = weight,
            age        = age
        )

        PhysioPli(location, physioData)
    }

    fun buildAndSendPhysioLocationCotMsg() {
        // Blocks the current (scheduler) thread until the work is done.
        runBlocking(Dispatchers.IO) {
            val data = getPhsyioAndPliData() // suspending
            val location   = data.loc
            val physioData = data.phys

            if (!TAKConnectService.isAnyTakConnected) {
                Log.w(TAG, "Not connected to a TAK Server. Not sending to TAK Server. Attempting Multicast…")
            } else {
                sendPhsyioLocationCotMsg(location, physioData)
            }

            multicastPhysioPliCot(data) // always try multicast; helper handles Wi-Fi
        }
    }

    private fun multicastPhysioPliCot(physioPli: PhysioPli) {
        val cotMessage = buildConciseLocationPhysioCot(physioPli.loc, physioPli.phys)
        NetworkUtils.MulticastPublisher.send(cotMessage)
    }

    private fun sendPhsyioLocationCotMsg(location: Location?, physioData: PhysioData) {
        val cotMessage = buildConciseLocationPhysioCot(location, physioData)
        sendDataTAKServers(cotMessage, false)
        //val logString = "Location/Biometric CoT message sent ($currReportingIntervalInSecs): $cotMessage"
        //Log.d(TAG, logString)
        //writeToLogFileLocation(logFile, logString)
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onLocationRcvdEvent(event: LocationRcvd) {
        if (manualSosToBeSent.get() == true) {
            Log.d(TAG, "onLocationRcvdEvent called with manualSosToBeSent true")
            sendEmergencyAlertCotMessage(manualSosReceivedCategory, manualSosReceivedDescription, manualSosReceivedSendSms, alertOrCancel)
        }
        lastLocationUpdateRcvd = System.currentTimeMillis()
        serviceScope.launch {
            if (pendingDropQueue.isNotEmpty() && sendMarkerReady()) {
                // TODO: Need to take care of situation if no TAK server is connected
                sendPendingMarkerDrops()
            }
        }
        buildAndSendPhysioLocationCotMsg()
    }

    private fun enqueueDrop(req: DataRepository.DropRequest) {
        Log.i(TAG, "===> adding to the marker drop queue ...")
        pendingDropQueue.addLast(req)
    }

    private fun sendPendingMarkerDrops() {
        serviceScope.launch(Dispatchers.IO) {
            val loc = dataRepository.latestLocation.value ?: return@launch
            while (pendingDropQueue.isNotEmpty()) {
                Log.i(TAG, "===> removing ${pendingDropQueue.first().markerType} from time ${(pendingDropQueue.first().timeMillis)}...")
                val req = pendingDropQueue.removeFirst()
                sendDropNow(req, loc)
            }
        }
    }

    private suspend fun sendDropNow(req: DataRepository.DropRequest, loc: Location) {
        val heartRate = dataRepository.latestHeartRate.value ?: -1
        val battery   = dataRepository.latestBatteryPercentage.value ?: -1

        val uid = UUID.randomUUID().toString() // backend owns UID
        val type = req.markerType.type
        iconSetPath = req.markerType.icon
        Log.i(TAG, "sendDropNow::markerType:: = [ ${type} ]")
        val xml = buildMarkerCotMessage(uid, type, loc, heartRate, battery)
        sendMulticastDatagram(xml)

        var sent = false
        if (dataRepository.getEnableMulticast.first()) {
            sendMulticastDatagram(xml)
        }
        if (DittoService.isRunning.get()) {
            EventBus.getDefault().post(DittoDocumentEvent("mapitem", xml))
            sent = true
        }
        if (TAKConnectService.isAnyTakConnected) {
            sendDataTAKServers(xml, true)
            sent = true
        }

        if (!sent) return  // don’t reflect in UI until actually sent/meshed

        markerDropMap[uid] = CotMarkerInfo(loc.latitude, loc.longitude, loc.altitude, loc.accuracy, loc.verticalAccuracyMeters, type, iconSetPath)
        Log.i(TAG, "=== ===> sending marker type ${type} with uid = [ $uid ] ")

        dataRepository.appendDroppedMarker(
            DataRepository.DroppedMarker(uid = uid, markerType = type, timeDropped = req.timeMillis)
        )

    }

//    @Subscribe(threadMode = ThreadMode.ASYNC)
//    public fun onMarkerEvent(event: MarkerEvent) {
//        val msg = event.message
//
//        if (HOSTILE == msg) {
//            iconSetPath = "COT_MAPPING_2525C/a-h/a-h-G"
//            markerType = "a-h-G"
//        } else if (FRIENDLY == msg) {
//            iconSetPath = "COT_MAPPING_2525C/a-f/a-f-G"
//            markerType = "a-f-G"
//        } else if (NEUTRAL == msg) {
//            iconSetPath = "COT_MAPPING_2525C/a-n/a-n-G"
//            markerType = "a-n-G"
//        } else { // UNKNOWN or any other
//            iconSetPath = "COT_MAPPING_2525C/a-u/a-u-G"
//            markerType = "a-u-G"
//        }
//        val currTime = System.currentTimeMillis()
//        if ((currTime-lastLocationUpdateRcvd) < FRESH_GPS_INTERVAL) {
//            Log.d(TAG, "MarkerEvent with 'fresh' gps lastUpdated(${(currTime-lastLocationUpdateRcvd)/1000.0}s ago)")
//            markerDropReceivedTime = currTime
//            sendMarkerCotMessage() // iSAnyTakConnected is taken care of inside sendMarkerCotMessage()
//        } else {
//            if (markerDropToBeSent.getAndSet(true) == false) {
//                Log.d(TAG, "MarkerEvent with markerDropToBeSent false & 'stale' gps lastUpdated(${(currTime-lastLocationUpdateRcvd)/1000.0}s ago)")
//                markerDropReceivedTime = currTime
//                EventBus.getDefault().post(LocationUpdateRequired())
//            } else {
//                Log.d(TAG, "MarkerEvent with markerDropToBeSent true & 'stale' gps lastUpdated(${(currTime-lastLocationUpdateRcvd)/1000.0}s ago)")
//            }
//        }
//    }

    // This method will be called when a MessageEvent is posted (in the UI thread for Toast)
    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onMessageEvent(event: MessageEvent) {
        val msg = event.message
        if (CALLSIGN_TEAM_ROLE_CHANGED == msg) {
            // send the "freshest" cot message with the newly updated callsign
            Log.d(TAG, "onMessageEvent w/ msg($msg)")
            buildAndSendPhysioLocationCotMsg()
        } else if (CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED == msg) {
            Log.d(TAG, "onMessageEvent w/ msg($msg)")
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
        Log.d(TAG, "CotDispatchService onDestroy()")
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_DETACH)
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        stopDispatchScheduler()
        HailService.hailScope.cancel()
        serviceScope.cancel()
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
        var reportingUpdateIntervalSecs = DEFAULT_REPORTING_INTERVAL
        var currReportingIntervalInSecs = DEFAULT_REPORTING_INTERVAL
        lateinit var instance: CotDispatchService
        val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        var esriGeojsonMsg = ""
        var tabletCommandJsonMsg = ""
    }
}
