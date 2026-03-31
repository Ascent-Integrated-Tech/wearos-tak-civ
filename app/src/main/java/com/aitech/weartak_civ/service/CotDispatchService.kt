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
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.DEFAULT_CONSTANT_REPORTING_INTERVAL
import com.aitech.weartak_civ.Utils.PhysioUtils.convertFarenheitToCelsius
import com.aitech.weartak_civ.Utils.getValueFromJson
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DataRepository.PhysioData
import com.aitech.weartak_civ.data.DataRepository.SitxServerStatus
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
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.smsNumbersList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.presentation.requestPhysioStatusAndAlertingComplicationUpdate
import com.aitech.weartak_civ.service.HailService.checkLicense
import com.aitech.weartak_civ.service.HailService.checkLocalHailLicense
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.TAKConnectService.Companion.sendDataTAKServers
import com.aitech.weartak_civ.service.sitx.SitxServiceCommand
import com.aitech.weartak_civ.service.sitx.SitxServiceCommandBus
import com.rain.hail.sdk.HailDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class CotDispatchService : Service() {
    private val TAG = "WearTAK-" + CotDispatchService::class.java.simpleName
    private var cotDispatchHandler: Handler? = null
    private var cotDispatchThread: HandlerThread? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var dataRepository: DataRepository
    private var lastLocationUpdateRcvd: Long = 0L

    data class CustomEmergencyAlertWithAddedState(
        var smsSent: Boolean = false,
        val alert: DataRepository.CustomEmergencyAlert
    )

    private val manualAlertMap = java.util.concurrent.ConcurrentHashMap<String, DataRepository.CustomEmergencyAlert>()
    private val pendingManualAlertQueue = ArrayDeque<CustomEmergencyAlertWithAddedState>()

    private val physioAndAutoGenAlertMap = java.util.concurrent.ConcurrentHashMap<String, DataRepository.CustomEmergencyAlert>()
    private val pendingPhysioAndAutoGenAlertQueue = ArrayDeque<CustomEmergencyAlertWithAddedState>()

    private var iconSetPath: String = ""
    private var markerType: String = ""
    // TODO Senni: should MarkerSnapshot be nullable below
    private val markerDropMap = java.util.concurrent.ConcurrentHashMap<String, DataRepository.MarkerSnapshot?>()
    private val pendingMarkerDropQueue = ArrayDeque<DataRepository.MarkerDropRequest>()
    private val pendingMarkerEditQueue = ArrayDeque<DataRepository.MarkerEditRequest>()

    private val pendingChatMsgQueue = ArrayDeque<Utils.ChatMessage>()

    private var combatId: Int = 0

    // Acquire DataRepository from a global or singleton reference

    private var sentBatteryWarnChatMsg = false
    private var sentBatteryAlertChatMsg = false

    private val capRepo by lazy {
        try {
            (applicationContext as WearTakApplication).capRepo
        } catch (_: Throwable) {
            CompanionAppRepository(applicationContext)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.v(TAG, "CotDispatchService onStartCommand()")
        return START_NOT_STICKY
    }
    val context: Context = mainActivity.applicationContext


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CotDispatchService onCreate()")
        isRunning.set(true)
        instance = this
        dataRepository = MainActivity.dataRepository  // single instance of data repository shared by all instances

        startForegroundServiceCompat(
            notificationId = COT_DISPATCH_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        CoroutineScope(Dispatchers.Main).launch {
            setValuesFromCustomDeveloperConfig()
        }

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        startDispatchScheduler()

        startBatteryAlerts()
        //startWearTakDebugAlerts()

        collectHailFlows()

        // TODO Senni: combine both and change name almightyReportingIntervalSecs
        collectReportingIntervalSecs()
        startReportingIntervalRepoBridge()

        startMarkerPipelines()

        startManualAlertPipelines()

        startPhysioAndAutoGenAlertPipelines()

        startSendChatMessageCollector()

        startAnyEndpointOrMulticastConnectedCollector()
    }

    private fun startAnyEndpointOrMulticastConnectedCollector() {
        serviceScope.launch {
            combine(dataRepository.anyEndpointConnected, dataRepository.multicastPublisherRunning) {
                anyEndpointConnected, multicastRunning ->
                anyEndpointConnected || multicastRunning
            }.distinctUntilChanged().collectLatest{ shouldSend ->
                if (shouldSend) {
                    sendPendingChatMessages()
                }
            }
        }
    }

    /* Expose the companion object CotDispatchService.currReportingIntervalSecs to the flow
    *  pipelines for new services to collect. Bridging legacy usage into the new flow ecosystem.
    */
    private fun startReportingIntervalRepoBridge() {
        serviceScope.launch {
            actualReportingIntervalSecsFlow
                .collect { secs ->
                    dataRepository.setAlmightyReportingInterval(secs)
                }
        }
    }

    // Whenever the ACTUAL reporting is changed, we need to tell the companion the interval in secs
    // to act as heartbeat for connection to handle timeouts better.
    private fun collectReportingIntervalSecs() {
        serviceScope.launch {
            dataRepository.almightyReportingIntervalSecs
                .collect{ interval ->
                    Log.v(TAG, "Almighty reporting interval in DR has changed to -> $interval Seconds")
                    Log.v(TAG, "Cotdispatch Companion Obj: $desiredReportingIntervalSecs")
                    if (companionAppAvailable()) {
                        Log.v(TAG, "Sending to companion app via BLE")
                        capRepo.enqueueReportIntForBle(interval)
                    }
                }
        }
    }

    private fun startBatteryAlerts() {
        serviceScope.launch {
            dataRepository.latestBatteryPercentage
                .filterNotNull()
                .collect { batt ->
                    val batteryAlertsEnabled = dataRepository.getEnableBatteryAlerts.first()
                    if (!batteryAlertsEnabled) return@collect

                    val charging = dataRepository.chargingState.value
                    if (charging) {
                        if (batt > MainActivity.battAlertPercent) {
                            sentBatteryAlertChatMsg = false
                        } else if (batt > MainActivity.battWarnPercent) {
                            sentBatteryWarnChatMsg = false
                        }
                        return@collect
                    }

                    val endpointsToSendTo = (TAKConnectService.isAnyTakConnected || dataRepository.getEnableMulticast.first() || companionAppAvailable())

                    if (endpointsToSendTo) {
                        if ((batt <= MainActivity.battWarnPercent)) {
                            Log.i(TAG, "Battery is at or below the WARN threshold: $batt <= ${MainActivity.battWarnPercent}")

                            if (batt > MainActivity.battAlertPercent) {
                                Log.i(TAG, "Battery is at or below the ALERT threshold: $batt <= ${MainActivity.battWarnPercent}")
                                if (!sentBatteryWarnChatMsg) {
                                    val msgText =
                                        "Watch Battery Warning: ${MainActivity.callsign} battery less than " +
                                                "${MainActivity.battWarnPercent}% (actual=$batt%)"
                                    // 1. Build snapshot (roomUid/title can be whatever your TAK chat expects)
                                    val chatSnap = dataRepository.snapshotChatEvent(
                                        roomUid = "All Chat Rooms",
                                        roomTitle = "All Chat Rooms",
                                        message = msgText
                                    )

                                    // TODO Senni: We need to add this and below (!sentBatteryAlertChatMsg) as being sent to "All Chat Rooms"
                                    // 2. Build CoT XML from snapshot
                                    val chatCot = buildChatCotFromSnapshot(chatSnap)
                                    Log.d(TAG, "Sending chat msg to 'All Chat Rooms' via snapshot: $chatCot")
                                    sendDataTAKServers(chatCot, true)
                                    if (companionAppAvailable()) {
                                        capRepo.enqueueChatSnapshotForBle(chatSnap)
                                    }
                                    SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(chatCot))


                                    sentBatteryWarnChatMsg = true
                                }
                            } else {
                                if (!sentBatteryAlertChatMsg) {
                                    val msgText =
                                        "Battery ALERT: ${MainActivity.callsign} battery less than " +
                                                "${MainActivity.battAlertPercent}% (actual=$batt%)"
                                    val chatSnap = dataRepository.snapshotChatEvent(
                                        roomUid = "All Chat Rooms",
                                        roomTitle = "All Chat Rooms",
                                        message = msgText
                                    )

                                    val chatCot = buildChatCotFromSnapshot(chatSnap)
                                    Log.d(TAG, "Sending chat msg to 'All Chat Rooms' via snapshot: $chatCot")
                                    sendDataTAKServers(chatCot, true)
                                    if (companionAppAvailable()) {
                                        capRepo.enqueueChatSnapshotForBle(chatSnap)
                                    }
                                    SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(chatCot))

                                    sentBatteryAlertChatMsg = true
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

    private fun startWearTakDebugAlerts() {
        serviceScope.launch {
            dataRepository.getEnableWearTakDevMode
                .filterNotNull()
                .collect { enabled ->
                    val sendToTak = TAKConnectService.isAnyTakConnected || dataRepository.getEnableMulticast.first()
                    val sendToCompApp = companionAppAvailable()
                    val endpointsToSendTo = sendToTak || sendToCompApp

                    if (endpointsToSendTo && enabled) {
                        val msg = "Debug mode changed to { $enabled } on Watch: $callsign with uid: $deviceId"
                        val chatSnap = dataRepository.snapshotChatEvent(
                            roomUid = "All Chat Rooms",
                            roomTitle = "All Chat Rooms",
                            message = msg
                        )

                        if (sendToTak) {
                            val chatCot = buildChatCotFromSnapshot(chatSnap)
                            sendDataTAKServers(chatCot, true)
                            SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(chatCot))
                            Log.d(TAG, "Sending chat msg to 'All Chat Rooms' via snapshot: $chatCot")
                        }

                        if (sendToCompApp) {
                            capRepo.enqueueChatSnapshotForBle(chatSnap)
                        }
                    }
                }
        }
    }

    private fun startManualAlertPipelines() {
        startAddManualAlertCollector()
        startClearManualAlertCollector()
    }

    private fun readyToSendAlertEvents(): Boolean {
        val loc = dataRepository.latestLocation.value
        // If no location at all, i.e., loc is null, we still send out as these are alerts; ELS
        // But if location is stale, we don't immediately send out but wait for fresh location
        val send = if (null == loc) true else (System.currentTimeMillis() - lastLocationUpdateRcvd) < FRESH_GPS_INTERVAL_MILLIS
        //Log.i(TAG, "loc: ${if (null == loc) "null" else "NOT null"}; FRESH: ${(System.currentTimeMillis() - lastLocationUpdateRcvd) < FRESH_GPS_INTERVAL_MILLIS}, ${(System.currentTimeMillis() - lastLocationUpdateRcvd) / 1000.0} s ago")
        return ((dataRepository.anyEndpointConnected.value || dataRepository.multicastPublisherRunning.value) && send)
    }

    private fun startAddManualAlertCollector() {
        serviceScope.launch {
            dataRepository.addManualAlert.collect { req ->
                Log.i(TAG, "Received req to add manual alert: $req")
                val manualAlert = DataRepository.CustomEmergencyAlert(uuid = UUID.randomUUID().toString(), alertState = DataRepository.AlertState.ALERT, category = req.category, description = req.description, priority = 1, sendSms = true, timeMillis = req.timeMillis, enqueuedToBeSent = false)
                val manualAlertWithState = CustomEmergencyAlertWithAddedState(smsSent = false, alert = manualAlert)
                manualAlertMap[manualAlert.uuid] = manualAlert
                if (readyToSendAlertEvents()) {
                    sendAllManualAlerts(manualAlertWithState)
                } else {
                    if (dataRepository.anyEndpointConnected.value || dataRepository.multicastPublisherRunning.value) {
                        EventBus.getDefault().post(LocationUpdateRequired())
                    }
                    enqueueManualAlertAndUpdateUI(manualAlertWithState)
                }
            }
        }
    }

    private suspend fun sendPendingManualAlerts(): Boolean {
        var sentAll = true
        // First send all pending manual alerts (so that earliest event is sent first) and then send the current latest 'manualAlert'
        Log.i(TAG, "pendingManualAlertQueue.size=${pendingManualAlertQueue.size}")
        while (pendingManualAlertQueue.isNotEmpty()) {
            val alertWithAddedState = pendingManualAlertQueue.first()
            Log.i(TAG, "Trying to send pending manual alert: $alertWithAddedState")
            if (sendEmergencyAlert(alertWithAddedState)) {
                pendingManualAlertQueue.removeFirst()
                if (alertWithAddedState.alert.alertState == DataRepository.AlertState.ALERT) {
                    dataRepository.changeManualAlertEnqueueStatusUid(alertWithAddedState.alert.uuid, false)
                } else {
                    dataRepository.removeManualAlertByUid(alertWithAddedState.alert.uuid)
                }
            } else {
                sentAll = false
                break
            }
        }
        return sentAll
    }
    private suspend fun sendAllManualAlerts(manualAlertWithState: CustomEmergencyAlertWithAddedState?) {
        val sentPendingAlerts = sendPendingManualAlerts()

        if (null == manualAlertWithState) return

        if (sentPendingAlerts) {
            if (sendEmergencyAlert(manualAlertWithState)) {
                if (manualAlertWithState.alert.alertState == DataRepository.AlertState.ALERT) {
                    dataRepository.appendManualAlert(manualAlertWithState.alert)
                } else {
                    dataRepository.removeManualAlertByUid(manualAlertWithState.alert.uuid)
                }
            } else {
                enqueueManualAlertAndUpdateUI(manualAlertWithState)
            }
        } else {
            enqueueManualAlertAndUpdateUI(manualAlertWithState)
        }
    }

    private suspend fun enqueueManualAlertAndUpdateUI(manualAlertWithState: CustomEmergencyAlertWithAddedState) {
        val pendingManualAlert = manualAlertWithState.alert.copy(enqueuedToBeSent = true)
        val pendingManualAlertWithState = CustomEmergencyAlertWithAddedState(smsSent = manualAlertWithState.smsSent, alert = pendingManualAlert)
        Log.i(TAG, "===> adding to pendingManualAlertQueue=$pendingManualAlert...")
        pendingManualAlertQueue.addLast(pendingManualAlertWithState)
        Log.i(TAG, "===> updating UI...")
        if (pendingManualAlert.alertState == DataRepository.AlertState.ALERT) {
            dataRepository.appendManualAlert(pendingManualAlert)
        } else {
            dataRepository.changeManualAlertToClearAndEnqueueStatusUid(pendingManualAlert.uuid, true)
        }
    }

    private fun startClearManualAlertCollector() {
        serviceScope.launch {
            dataRepository.clearManualAlert.collect { req ->
                Log.i(TAG, "Received req to clear manual alert: $req")
                when (req) {
                    is DataRepository.ClearRequest.Specific -> handleClearManualAlertUid(req.uid)
                    DataRepository.ClearRequest.Last -> {
                        val manualAlerts = dataRepository.manualAlertList.value.filter { it.alertState == DataRepository.AlertState.ALERT }
                        if (manualAlerts.isNotEmpty()) {
                            val last = manualAlerts.maxByOrNull { it.timeMillis }
                            if (last != null) {
                                Log.i(TAG, "Clearing last manual alert: $last")
                                handleClearManualAlertUid(last.uuid)
                            }
                        }
                    }
                    DataRepository.ClearRequest.All -> handleClearManualAlertAll()
                }
            }
        }
    }

    private suspend fun handleClearManualAlertUid(uid: String) {
        val manualAlert = manualAlertMap[uid]
        if (null == manualAlert) return

        val manualAlertClearWithState = CustomEmergencyAlertWithAddedState(smsSent = false, alert = manualAlert.copy(alertState = DataRepository.AlertState.CANCEL))
        // TODO Senni: Do we need latest location for clear event
        if (readyToSendAlertEvents()) {
            sendAllManualAlerts(manualAlertClearWithState)
        } else {
            if (dataRepository.anyEndpointConnected.value || dataRepository.multicastPublisherRunning.value) {
                EventBus.getDefault().post(LocationUpdateRequired())
            }
            enqueueManualAlertAndUpdateUI(manualAlertClearWithState)
        }
        manualAlertMap.remove(uid)
    }

    private suspend fun handleClearManualAlertAll() {
        val manualAlerts = dataRepository.manualAlertList.value.filter { it.alertState == DataRepository.AlertState.ALERT }
        if (manualAlerts.isNotEmpty()) {
            val uids = manualAlerts.map { it.uuid }
            uids.forEach { uid ->
                handleClearManualAlertUid(uid)
            }
        }
    }

    private fun startPhysioAndAutoGenAlertPipelines() {
        startAddPhysioAndAutoGenAlertCollector()
        startClearPhysioAndAutoGenAlertCollector()
    }

    private fun startAddPhysioAndAutoGenAlertCollector() {
        serviceScope.launch {
            dataRepository.addPhysioAndAutoGenAlert.collect { req ->
                Log.i(TAG, "Received req to add physio and auto generated alert: $req")
                val physioAndAutoGenAlert = DataRepository.CustomEmergencyAlert(uuid = req.uuid, alertState = DataRepository.AlertState.ALERT, category = req.category, description = req.description, priority = req.priority, sendSms = req.sendSms, timeMillis = req.timeMillis, enqueuedToBeSent = false)
                val physioAndAutoGenAlertAlertWithState = CustomEmergencyAlertWithAddedState(smsSent = false, alert = physioAndAutoGenAlert)
                physioAndAutoGenAlertMap[physioAndAutoGenAlert.uuid] = physioAndAutoGenAlert
                if (readyToSendAlertEvents()) {
                    sendAllPhysioAndAutoGenAlerts(physioAndAutoGenAlertAlertWithState)
                } else {
                    if (dataRepository.anyEndpointConnected.value || dataRepository.multicastPublisherRunning.value) {
                        EventBus.getDefault().post(LocationUpdateRequired())
                    }
                    enqueuePhysioAndAutoGenAlertAndUpdateUI(physioAndAutoGenAlertAlertWithState)
                }
            }
        }
    }

    private suspend fun sendPendingPhysioAndAutoGenAlerts(): Boolean {
        var sentAll = true
        Log.i(TAG, "pendingPhysioAndAutoGenAlertQueue.size=${pendingPhysioAndAutoGenAlertQueue.size}")
        while (pendingPhysioAndAutoGenAlertQueue.isNotEmpty()) {
            val alertWithAddedState = pendingPhysioAndAutoGenAlertQueue.first()
            Log.i(TAG, "Trying to send pending physio and auto gen alert: $alertWithAddedState")
            if (sendEmergencyAlert(alertWithAddedState)) {
                pendingPhysioAndAutoGenAlertQueue.removeFirst()
                if (alertWithAddedState.alert.alertState == DataRepository.AlertState.ALERT) {
                    dataRepository.changePhysioAndAutoGenAlertEnqueueStatusUid(alertWithAddedState.alert.uuid, false)
                } else {
                    dataRepository.removePhysioAndAutoGenAlertByUid(alertWithAddedState.alert.uuid)
                }
            } else {
                sentAll = false
                break
            }
        }
        return sentAll
    }
    private suspend fun sendAllPhysioAndAutoGenAlerts(alertWithState: CustomEmergencyAlertWithAddedState?) {
        // First send all pending physio and auto gen alerts (so that earliest event is sent first) and then send the current latest 'physioAndAutoGenAlert'
        val sentPendingAlerts = sendPendingPhysioAndAutoGenAlerts()

        if (null == alertWithState) return

        if (sentPendingAlerts) {
            if (sendEmergencyAlert(alertWithState)) {
                if (alertWithState.alert.alertState == DataRepository.AlertState.ALERT) {
                    dataRepository.appendPhysioAndAutoGenAlert(alertWithState.alert)
                } else {
                    dataRepository.removePhysioAndAutoGenAlertByUid(alertWithState.alert.uuid)
                }
            } else {
                enqueuePhysioAndAutoGenAlertAndUpdateUI(alertWithState)
            }
        } else {
            enqueuePhysioAndAutoGenAlertAndUpdateUI(alertWithState)
        }
    }

    private suspend fun enqueuePhysioAndAutoGenAlertAndUpdateUI(alertWithState: CustomEmergencyAlertWithAddedState) {
        val pendingAlert = alertWithState.alert.copy(enqueuedToBeSent = true)
        val pendingAlertWithState = CustomEmergencyAlertWithAddedState(smsSent = alertWithState.smsSent, alert = pendingAlert)
        Log.i(TAG, "===> adding to pendingPhysioAndAutoGenAlertQueue=$pendingAlert...")
        pendingPhysioAndAutoGenAlertQueue.addLast(pendingAlertWithState)
        Log.i(TAG, "===> updating UI...")
        if (pendingAlert.alertState == DataRepository.AlertState.ALERT) {
            dataRepository.appendPhysioAndAutoGenAlert(pendingAlert)
        } else {
            dataRepository.changePhysioAndAutoGenAlertToClearAndEnqueueStatusUid(pendingAlert.uuid, true)
        }
    }

    private fun startClearPhysioAndAutoGenAlertCollector() {
        serviceScope.launch {
            dataRepository.clearPhysioAndAutoGenAlert.collect { req ->
                Log.i(TAG, "Received req to clear physio or auto gen alert: $req")
                when (req) {
                    is DataRepository.ClearRequest.Specific -> handleClearPhysioAndAutoGenAlertUid(req.uid)
                    DataRepository.ClearRequest.Last -> {
                        val physioAndAutoGenAlerts = dataRepository.physioAndAutoGenAlertList.value.filter { it.alertState == DataRepository.AlertState.ALERT }
                        if (physioAndAutoGenAlerts.isNotEmpty()) {
                            val last = physioAndAutoGenAlerts.maxByOrNull { it.timeMillis }
                            if (last != null) {
                                Log.i(TAG, "Clearing last physio or auto gen alert: $last")
                                handleClearPhysioAndAutoGenAlertUid(last.uuid)
                            }
                        }
                    }
                    DataRepository.ClearRequest.All -> handleClearPhysioAndAutoGenAlertAll()
                }
                requestPhysioStatusAndAlertingComplicationUpdate(context)
            }
        }
    }

    private suspend fun handleClearPhysioAndAutoGenAlertUid(uid: String) {
        val physioAndAutoGenAlert = physioAndAutoGenAlertMap[uid]
        if (null == physioAndAutoGenAlert) return

        val physioAndAutoGenAlertClearWithState = CustomEmergencyAlertWithAddedState(smsSent = false, alert = physioAndAutoGenAlert.copy(alertState = DataRepository.AlertState.CANCEL))
        // TODO Senni: Do we need latest location for clear event
        if (readyToSendAlertEvents()) {
            sendAllPhysioAndAutoGenAlerts(physioAndAutoGenAlertClearWithState)
        } else {
            if (dataRepository.anyEndpointConnected.value || dataRepository.multicastPublisherRunning.value) {
                EventBus.getDefault().post(LocationUpdateRequired())
            }
            enqueuePhysioAndAutoGenAlertAndUpdateUI(physioAndAutoGenAlertClearWithState)
        }
        physioAndAutoGenAlertMap.remove(uid)
    }

    private suspend fun handleClearPhysioAndAutoGenAlertAll() {
        val physioAndAutoGenAlerts = dataRepository.physioAndAutoGenAlertList.value.filter { it.alertState == DataRepository.AlertState.ALERT }
        if (physioAndAutoGenAlerts.isNotEmpty()) {
            val uids = physioAndAutoGenAlerts.map { it.uuid }
            uids.forEach { uid ->
                handleClearPhysioAndAutoGenAlertUid(uid)
            }
        }
    }

    private fun startSendChatMessageCollector() {
        serviceScope.launch {
            dataRepository.chatRequestSend.collect { req ->
                Log.i(TAG, "Received req to send chat message: $req")
                if (dataRepository.anyEndpointConnected.value || dataRepository.multicastPublisherRunning.value) {
                    sendAllChatMessages(req)
                } else {
                    val chatMsg = Utils.ChatMessage(message = req.message, uid = req.dstUid, callsign = req.dstChatroom, timeReceived = req.timeMillis)
                    pendingChatMsgQueue.addLast(chatMsg)
                    Log.i(TAG, "pendingChatMsgQueue=${pendingChatMsgQueue.size}")
                }
            }
        }
    }

    private suspend fun sendPendingChatMessages(): Boolean {
        var sentAll = true
        Log.i(TAG, "pendingChatMsgQueue.size=${pendingChatMsgQueue.size}")
        while (pendingChatMsgQueue.isNotEmpty()) {
            val chatMsg = pendingChatMsgQueue.first()
            Log.i(TAG, "Trying to send pending chat message: $chatMsg")
            if (sendChatMessage(chatMsg)) {
                pendingChatMsgQueue.removeFirst()
            } else {
                sentAll = false
                break
            }
        }
        return sentAll
    }

    private suspend fun sendChatMessage(chatMsg: Utils.ChatMessage): Boolean {
        val chatSnap = dataRepository.snapshotChatEvent(roomUid = chatMsg.uid, roomTitle = chatMsg.callsign, message = chatMsg.message)
        val chatCotXml = buildChatCotFromSnapshot(chatSnap)
        var sent = false
        if (companionAppAvailable()) {
            Log.i(TAG, "Send Chat payload via BLE ...")
            capRepo.enqueueChatSnapshotForBle(chatSnap)
            sent = true
        }
        if (dataRepository.getEnableMulticast.first()) {
            sendMulticastDatagram(chatCotXml)
            sent = true
        }
        if (DittoService.isRunning.get()) {
            EventBus.getDefault().post(DittoDocumentEvent("mapitem", chatCotXml))
            sent = true
        }
        if (TAKConnectService.isAnyTakConnected) {
            sendDataTAKServers(chatCotXml, true)
            sent = true
        }
        if (sitxAvailable()) {
            SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(chatCotXml))
            sent = true
        }

        if (sent) {
            Log.i(TAG, "sendChatMessage: Sent chat message: $chatMsg")
        } else {
            Log.i(TAG, "sendChatMessage: Could NOT send chat message: $chatMsg")
        }

        return sent
    }

    private suspend fun sendAllChatMessages(chatReq: DataRepository.SendChatRequest?) {
        val sentPendingChatMessages = sendPendingChatMessages()

        if (null == chatReq) return

        if (sentPendingChatMessages) {
            val chatMsg = Utils.ChatMessage(message = chatReq.message, uid = chatReq.dstUid, callsign = chatReq.dstChatroom, timeReceived = chatReq.timeMillis)
            if (!sendChatMessage(chatMsg)) {
                pendingChatMsgQueue.addLast(chatMsg)
                Log.i(TAG, "pendingChatMsgQueue=${pendingChatMsgQueue.size}")
            }
        }
    }


    private fun buildChatCotFromSnapshot(chat: DataRepository.ChatEventSnapshot): String {
        // Rebuild an android.location.Location from snapshot, if needed.
        val locSnapshot = chat.location
        val loc = Location("snapshot").apply {
            latitude = locSnapshot?.lat ?: 0.0
            longitude = locSnapshot?.lon ?: 0.0
            altitude = locSnapshot?.hae ?: 0.0
            accuracy = (locSnapshot?.ce ?: 999_999.0).toFloat()
            verticalAccuracyMeters = (locSnapshot?.le ?: 999_999.0).toFloat()
            time = System.currentTimeMillis()
        }

        return buildChatCotMessage(
            location    = loc,
            chatMsg     = chat.message,
            dstUid      = chat.roomUid,
            dstChatroom = chat.roomTitle
        )
    }

    private fun startMarkerPipelines() {
        startMarkerDropCollector()
        startMarkerEditCollector()
        startMarkerClearCollector()
        startMarkerDropListCollector()
    }

    private fun startMarkerDropCollector() {
        serviceScope.launch {
            dataRepository.markerDropRequests.collect { req ->
                Log.i(TAG, "Received drop request...")
                if (readyToSendMarkerEvents()) {
                    sendMarkerDropNow(req)
                    sendPendingMarkerDrops()
                } else {
                    if (dataRepository.anyEndpointConnected.value || dataRepository.multicastPublisherRunning.value) {
                        EventBus.getDefault().post(LocationUpdateRequired())
                    }
                    enqueueDrop(req)
                }
            }
        }
    }

    private fun startMarkerEditCollector() {
        serviceScope.launch {
            dataRepository.markerEditRequests.collect { req ->
                Log.i(TAG, "Received marker edit request for uid=${req.uid}")
                if (readyToSendMarkerEvents()) {
                    sendMarkerEditNow(req)
                    sendPendingMarkerEdits()
                } else {
                    if (dataRepository.anyEndpointConnected.value || dataRepository.multicastPublisherRunning.value) {
                        EventBus.getDefault().post(LocationUpdateRequired())
                    }
                    enqueueEdit(req)
                }
            }
        }
    }

    // TODO: I think we can only send the marker cot message once we have established connection and sent atleast one PLI message. The queue doesn't seem to flush and send to server on reconnects. Needs more work. MVP works.
    private fun readyToSendMarkerEvents(): Boolean {
        val loc = dataRepository.latestLocation.value
        val fresh = (System.currentTimeMillis() - lastLocationUpdateRcvd) < FRESH_GPS_INTERVAL_MILLIS
        return ((dataRepository.anyEndpointConnected.value || dataRepository.multicastPublisherRunning.value) && (null != loc) && fresh)
    }

    private fun startMarkerDropListCollector() {
        serviceScope.launch {
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

    private fun startMarkerClearCollector() {
        serviceScope.launch {
            dataRepository.clearMarkerRequests.collect { req ->
                when (req) {
                    is DataRepository.ClearRequest.Specific -> handleClearMarkerUid(req.uid)
                    DataRepository.ClearRequest.Last -> {
                        val last = dataRepository.droppedMarkerList.value.maxByOrNull { it.timeDropped }
                        if (last != null) handleClearMarkerUid(last.uid)
                    }
                    DataRepository.ClearRequest.All -> handleClearMarkerAll()
                }
            }
        }
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
            if ((System.currentTimeMillis()-lastLocationUpdateRcvd) > actualReportingIntervalSecs * 1000L) {
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
            cotDispatchHandler?.postDelayed(this, actualReportingIntervalSecs * 1000L)
        }
    }

    data class BleLocationOutput(
        val lat: Double,
        val lon: Double,
        val hae: Double,
        val ce: Float // Horizontal Error
    )

    /*
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
    */

    /*
    private suspend fun buildConciseLocationPhysioCot(): String {
        val staleOffset = 3 * currReportingIntervalInSecs

        val physSnap     = dataRepository.snapshotPhysio()
        val locSnap      = dataRepository.snapshotLocation()
        val timeSnap     = getCotEventTimeSnapshot(staleOffset = Utils.TimeOffset(staleOffset))
        val sysSnap      = dataRepository.snapshotSystem()
        val dailyMetrics = physSnap.data.dailyMetrics

        val startTime     = timeSnap.startCot
        val staleTime     = timeSnap.staleCot
        val startTimeInMs = timeSnap.time

        val lat = locSnap?.lat  ?: 0.0
        val lon = locSnap?.lon  ?: 0.0
        val hae = locSnap?.alt  ?: 0.0

        // Strip precision & default to “9999” if NaN
        val ceTrim     = locSnap?.ce?.takeIf { !it.isNaN() }?.toInt() ?: 9999
        val leTrim     = locSnap?.le?.takeIf { !it.isNaN() }?.toInt() ?: 9999
        val courseTrim = locSnap?.course?.toInt()  ?: 0
        val speedTrim  = locSnap?.speed?.toInt()   ?: 0

        // Pull out physio values
        val uid         = sysSnap.deviceId
        val hr          = physSnap.data.heartRateBpm
        val skt         = String.format("%.1f", physSnap.data.skinTempF).let { str -> if (str.endsWith(".0")) str.replace(".0", "") else str }
        val exert       = String.format("%.2f", physSnap.data.exertion).let { str -> if (str.endsWith(".00")) str.replace(".00", "") else str }
        val bat         = sysSnap.batteryPercent
        val steps       = dailyMetrics?.steps
        val calories    = dailyMetrics?.calories
        val floors      = dailyMetrics?.floors
        val elevationM  = dailyMetrics?.elevationM
        val age         = physSnap.data.ageYears

        // Inject physio data into remarks
        val remarks = if (MainActivity.enablePhysiologicalServices && (MainActivity.enableActRecService || MainActivity.enableDynamicReportingStrategy)) {
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
                append("Cals:${calories?.roundToInt()};")
                append("Floors:${floors?.roundToInt()};")
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


        val hailCotDetailString = buildHailDetail(physSnap.data)

        // Build the cot XML
        val cot = if (enableBatdokCot) {
            val sktCelcius = String.format("%.1f", convertFarenheitToCelsius(physSnap.data.skinTempF)).let { str -> if (str.endsWith(".0")) str.replace(".0", "") else str }
            var batdokStr = if (-1 == hr) {
                if (-1F == physSnap.data.skinTempF) {
                    ""
                } else {
                    """<_atmist_ age="$age" timeOfIncident="$startTime"><vitalSign index="0" timestamp="$startTime">(Temp,$skt (F) $sktCelcius (C),$startTimeInMs)</vitalSign></_atmist_>""".trimIndent()

                }
            } else {
                if (-1F == physSnap.data.skinTempF) {
                    """<_atmist_ age="$age" timeOfIncident="$startTime"><vitalSign index="0" timestamp="$staleTime">(HR,$hr,$startTimeInMs)</vitalSign></_atmist_>""".trimIndent()

                } else {
                    """<_atmist_ age="$age" timeOfIncident="$startTime"><vitalSign index="0" timestamp="$staleTime">(HR,$hr,$startTimeInMs)</vitalSign><vitalSign index="1" timestamp="$startTime">(Temp,$skt (F) $sktCelcius (C),$startTimeInMs)</vitalSign></_atmist_>""".trimIndent()
                }
            }
            """<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g'><point lat='${"%.6f".format(lat)}' lon='${"%.6f".format(lon)}' hae='${"%.1f".format(hae)}' ce='$ceTrim' le='$leTrim'/><detail><remarks>$remarks</remarks>$batdokStr<contact endpoint='*:-1:stcp' callsign='$callsign'/><__group role='$role' name='$teamValue'/><track course='$courseTrim' speed='$speedTrim'/>$hailCotDetailString</detail></event>""".trimIndent()
        } else {
            """<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g'><point lat='${"%.6f".format(lat)}' lon='${"%.6f".format(lon)}' hae='${"%.1f".format(hae)}' ce='$ceTrim' le='$leTrim'/><detail><remarks>$remarks</remarks><contact endpoint='*:-1:stcp' callsign='$callsign'/><__group role='$role' name='$teamValue'/><track course='$courseTrim' speed='$speedTrim'/>$hailCotDetailString</detail></event>""".trimIndent()
        }
        //Log.i(TAG, "Size: ${cot.length} Bytes \n $cot")
        //Log.i(TAG, "===> HailCotDetail: $hailCotDetailString")


        if (enableEsriEndpoint) {
            val isWorn = if (-1 == physSnap.data.heartRateBpm) { false } else { true }
            esriGeojsonMsg =
                if (MainActivity.enablePhysiologicalServices && (MainActivity.enableActRecService || MainActivity.enableDynamicReportingStrategy)) {
                    """
        { "type": "FeatureCollection", "features": [ { "type": "Feature", "id": "$uid", "properties": { "timeutc": "$startTime", "staletimeutc": "$staleTime", "callsign": "$callsign", "role": "$role", "color": "$teamValue", "phone": "+11234567890", "model": "WEAROS", "device": "Samsung Galaxy Watch", "hae": $hae, "horizaccuracy": $ceTrim, "vertaccuracy": $leTrim, "heading": $courseTrim, "speed": $speedTrim, "batt": ${bat}, "biometrics": [ { "model": "WEAROS", "device": "Samsung Galaxy Watch", "isworn": $isWorn, "hr": $hr, "hribi": -1, "hrv": -1, "skintemp": $skt, "ambienttemp": -1, "coretemp": -1, "spo2": -1, "spo2measuretimeutc": "", "vo2": -1, "vo2measuretimeutc": "", "vo2max": -1, "vo2maxmeasuretimeutc": "", "sweatloss": -1, "sweatlossduration": -1, "sweatlossmeasuretimeutc": "", "sleepscore": -1, "sleepduration": -1, "deepsleepduration": -1, "sleepscoremeasuretimeutc": "", "energyscore": -1, "energyscoremeasuretimeutc": "", "steps": $steps, "floors": ${floors?.roundToInt()}, "calories": ${calories?.roundToInt()}, "exertion": $exert, "fatmass": -1, "musclemass": -1, "biameasuretimeutc": "", "batt": $bat } ], "alerts" : [ ], "resolvedalerts" : [ ] }, "geometry": { "type": "Point", "coordinates": [ $lon, $lat ] } } ] }
        """.trimIndent()

                } else {
                    """
        { "type": "FeatureCollection", "features": [ { "type": "Feature", "id": "$uid", "properties": { "timeutc": "$startTime", "staletimeutc": "$staleTime", "callsign": "$callsign", "role": "$role", "color": "$teamValue", "phone": "+11234567890", "model": "WEAROS", "device": "Samsung Galaxy Watch", "hae": $hae, "horizaccuracy": $ceTrim, "vertaccuracy": $leTrim, "heading": $courseTrim, "speed": $speedTrim, "batt": ${bat}, "biometrics": [ { "model": "WEAROS", "device": "Samsung Galaxy Watch", "isworn": $isWorn, "hr": $hr, "hribi": -1, "hrv": -1, "skintemp": $skt, "ambienttemp": -1, "coretemp": -1, "spo2": -1, "spo2measuretimeutc": "", "vo2": -1, "vo2measuretimeutc": "", "vo2max": -1, "vo2maxmeasuretimeutc": "", "sweatloss": -1, "sweatlossduration": -1, "sweatlossmeasuretimeutc": "", "sleepscore": -1, "sleepduration": -1, "deepsleepduration": -1, "sleepscoremeasuretimeutc": "", "energyscore": -1, "energyscoremeasuretimeutc": "", "steps": -1, "floors": -1, "calories": -1, "exertion": $exert, "fatmass": -1, "musclemass": -1, "biameasuretimeutc": "", "batt": $bat } ], "alerts" : [ ], "resolvedalerts" : [ ] }, "geometry": { "type": "Point", "coordinates": [ $lon, $lat ] } } ] }
        """.trimIndent()
                }

            Log.d(TAG, "esriGeojsonMsg: $esriGeojsonMsg")
        }

        if (enableTabletCommandEndpoint) {
            val isWorn = if (-1 == physSnap.data.heartRateBpm) { false } else { true }
            tabletCommandJsonMsg = if (MainActivity.enablePhysiologicalServices && (MainActivity.enableActRecService || MainActivity.enableDynamicReportingStrategy)) {
                """
                {"id":"$uid","timeutc":"$startTime","staletimeutc":"$staleTime","callsign":"$callsign","role":"$role","color":"$teamValue","phone":"+13014058648","model":"WEAROS","device":"Samsung Galaxy Watch","lat":$lat,"lon":$lon,"hae":$hae,"horizaccuracy":$ceTrim,"vertaccuracy":$leTrim,"heading":$courseTrim,"speed":$speedTrim,"batt":${bat},"biometrics":[{"model":"WEAROS","device":"Samsung Galaxy Watch","isworn":$isWorn,"hr":${bat},"hribi":-1,"hrv":-1,"skintemp":$skt,"ambienttemp":-1,"coretemp":-1,"spo2":-1,"spo2measuretimeutc":"","vo2":-1,"vo2measuretimeutc":"","vo2max":-1,"vo2maxmeasuretimeutc":"","sweatloss":-1,"sweatlossduration":-1,"sweatlossmeasuretimeutc":"","sleepscore":-1,"sleepduration":-1,"deepsleepduration":-1,"sleepscoremeasuretimeutc":"","energyscore":-1,"energyscoremeasuretimeutc":"","steps":${steps},"floors":${dailyMetrics?.floors?.roundToInt()},"calories":${dailyMetrics?.calories?.roundToInt()},"exertion":$exert,"fatmass":-1,"musclemass":-1,"biameasuretimeutc":"","batt":${bat}}],"alerts":[],"resolvedalerts":[]}
                """.trimIndent()
            } else {
                """
                {"id":"$uid","timeutc":"$startTime","staletimeutc":"$staleTime","callsign":"$callsign","role":"$role","color":"$teamValue","phone":"+13014058648","model":"WEAROS","device":"Samsung Galaxy Watch","lat":$lat,"lon":$lon,"hae":$hae,"horizaccuracy":$ceTrim,"vertaccuracy":$leTrim,"heading":$courseTrim,"speed":$speedTrim,"batt":${bat},"biometrics":[{"model":"WEAROS","device":"Samsung Galaxy Watch","isworn":$isWorn,"hr":${bat},"hribi":-1,"hrv":-1,"skintemp":$skt,"ambienttemp":-1,"coretemp":-1,"spo2":-1,"spo2measuretimeutc":"","vo2":-1,"vo2measuretimeutc":"","vo2max":-1,"vo2maxmeasuretimeutc":"","sweatloss":-1,"sweatlossduration":-1,"sweatlossmeasuretimeutc":"","sleepscore":-1,"sleepduration":-1,"deepsleepduration":-1,"sleepscoremeasuretimeutc":"","energyscore":-1,"energyscoremeasuretimeutc":"","steps":-1,"floors":-1,"calories":-1,"exertion":$exert,"fatmass":-1,"musclemass":-1,"biameasuretimeutc":"","batt":${bat}}],"alerts":[],"resolvedalerts":[]}
                """.trimIndent()
            }
            //Log.d(TAG, "tabletCommandJsonMsg: $tabletCommandJsonMsg")
        }


        return cot
    }
     */

    private fun rectifiedFormattedNumber(
        value: Number?,
        format: String,
        invalidString: String = "N/A",
        negIsInvalid: Boolean = true,
        zeroIsInvalid: Boolean = true
    ): String {
        if (value == null) return invalidString

        val v = value.toDouble()
        if (negIsInvalid && v < 0.0) return invalidString
        if (zeroIsInvalid && v == 0.0) return invalidString

        val s = String.format(format, v)

        // Preserve your exact prior formatting contract:
        // - if you used "%.1f" drop ".0"
        // - if you used "%.2f" drop ".00"
        return when {
            format.endsWith("1f") && s.endsWith(".0")  -> s.removeSuffix(".0")
            format.endsWith("2f") && s.endsWith(".00") -> s.removeSuffix(".00")
            else -> s
        }
    }

    private fun buildPliCot(snapshot: DataRepository.PliSnapshot): String {
        val timeSnap     = snapshot.time

        val physSnap     = snapshot.physioData
        val locSnap      = snapshot.loc
        val sysSnap      = snapshot.systemData
        val dailyMetrics = physSnap.data.dailyMetrics

        val startTime     = timeSnap.startCot
        val staleTime     = timeSnap.staleCot
        val startTimeInMs = timeSnap.time

        val lat = locSnap?.lat  ?: 0.0
        val lon = locSnap?.lon  ?: 0.0
        val hae = locSnap?.hae  ?: 0.0

        // Strip precision & default to “9999” if NaN
        val ceTrim     = locSnap?.ce?.takeIf { !it.isNaN() }?.toInt() ?: 9999
        val leTrim     = locSnap?.le?.takeIf { !it.isNaN() }?.toInt() ?: 9999
        val courseTrim = locSnap?.course?.toInt()  ?: 0
        val speedTrim  = locSnap?.speed?.toInt()   ?: 0

        // Pull out physio values
        val uid         = sysSnap.deviceId
        val hr          = rectifiedIntToString(physSnap.data.heartRateBpm)
        val skt         = rectifiedFormattedNumber(physSnap.data.skinTempF, "%.1f", negIsInvalid = false, zeroIsInvalid = false)
        val exert       = rectifiedFormattedNumber(physSnap.data.exertion, "%.2f", zeroIsInvalid = false)
        val bat         = sysSnap.batteryPercent
        val steps       = dailyMetrics?.steps ?: 0L
        val calories    = dailyMetrics?.calories ?: 0.0
        val floors      = dailyMetrics?.floors ?: 0.0
        val elevationM  = dailyMetrics?.elevationM ?: 0.0
        val age         = physSnap.data.ageYears

        // Inject physio data into remarks
        val remarks = if (MainActivity.enablePhysiologicalServices && (MainActivity.enableActRecService || MainActivity.enableDynamicReportingStrategy)) {
            buildString {
                append("HR:$hr;")
                append("SknTmp:$skt;")
                append("Exert:$exert;")
                append("Bat:${bat ?: -1}%;")
                append("Steps:$steps;")
                append("Cals:${calories.roundToInt()};")
                append("Floors:${floors.roundToInt()};")
                append("ElevGain(m):${elevationM.roundToInt()}")
                // Line below is for debug only:
                //append(";Act:${dataRepository.latestActivityRecognized.value.name}($actualReportingIntervalSecs)")
            }
        } else {
            buildString {
                append("HR:$hr;")
                append("SknTmp:$skt;")
                append("Exert:$exert;")
                append("Bat:${bat ?: -1}%")
            }
        }

        val hailCotDetailString = buildHailDetail(physSnap.data)

        // Build the cot XML
        val cot = if (enableBatdokCot) {
            val sktCelcius = String.format("%.1f", convertFarenheitToCelsius(physSnap.data.skinTempF)).let { str -> if (str.endsWith(".0")) str.replace(".0", "") else str }
            var batdokStr = if (-1 == (physSnap.data.heartRateBpm)) {
                if (-1F == physSnap.data.skinTempF) {
                    ""
                } else {
                    """<_atmist_ age="$age" timeOfIncident="$startTime"><vitalSign index="0" timestamp="$startTime">(Temp,$skt (F) $sktCelcius (C),$startTimeInMs)</vitalSign></_atmist_>""".trimIndent()
                }
            } else {
                if (-1F == physSnap.data.skinTempF) {
                    """<_atmist_ age="$age" timeOfIncident="$startTime"><vitalSign index="0" timestamp="$staleTime">(HR,$hr,$startTimeInMs)</vitalSign></_atmist_>""".trimIndent()

                } else {
                    """<_atmist_ age="$age" timeOfIncident="$startTime"><vitalSign index="0" timestamp="$staleTime">(HR,$hr,$startTimeInMs)</vitalSign><vitalSign index="1" timestamp="$startTime">(Temp,$skt (F) $sktCelcius (C),$startTimeInMs)</vitalSign></_atmist_>""".trimIndent()
                }
            }
            """<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g'><point lat='${"%.6f".format(lat)}' lon='${"%.6f".format(lon)}' hae='${"%.1f".format(hae)}' ce='$ceTrim' le='$leTrim'/><detail><remarks>$remarks</remarks>$batdokStr<contact endpoint='*:-1:stcp' callsign='$callsign'/><__group role='$role' name='$teamValue'/><track course='$courseTrim' speed='$speedTrim'/>$hailCotDetailString</detail></event>""".trimIndent()
        } else {
            """<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='$uid' type='a-f-G-U-C' time='$startTime' start='$startTime' stale='$staleTime' how='m-g'><point lat='${"%.6f".format(lat)}' lon='${"%.6f".format(lon)}' hae='${"%.1f".format(hae)}' ce='$ceTrim' le='$leTrim'/><detail><remarks>$remarks</remarks><contact endpoint='*:-1:stcp' callsign='$callsign'/><__group role='$role' name='$teamValue'/><track course='$courseTrim' speed='$speedTrim'/>$hailCotDetailString</detail></event>""".trimIndent()
        }
        //Log.i(TAG, "Size: ${cot.length} Bytes \n $cot")
        //Log.i(TAG, "===> HailCotDetail: $hailCotDetailString")


        if (enableEsriEndpoint) {
            val isWorn = if (-1 == physSnap.data.heartRateBpm) { false } else { true }
            esriGeojsonMsg =
                if (MainActivity.enablePhysiologicalServices && (MainActivity.enableActRecService || MainActivity.enableDynamicReportingStrategy)) {
                    """
        { "type": "FeatureCollection", "features": [ { "type": "Feature", "id": "$uid", "properties": { "timeutc": "$startTime", "staletimeutc": "$staleTime", "callsign": "$callsign", "role": "$role", "color": "$teamValue", "phone": "+11234567890", "model": "WEAROS", "device": "Samsung Galaxy Watch", "hae": $hae, "horizaccuracy": $ceTrim, "vertaccuracy": $leTrim, "heading": $courseTrim, "speed": $speedTrim, "batt": ${bat}, "biometrics": [ { "model": "WEAROS", "device": "Samsung Galaxy Watch", "isworn": $isWorn, "hr": $hr, "hribi": -1, "hrv": -1, "skintemp": $skt, "ambienttemp": -1, "coretemp": -1, "spo2": -1, "spo2measuretimeutc": "", "vo2": -1, "vo2measuretimeutc": "", "vo2max": -1, "vo2maxmeasuretimeutc": "", "sweatloss": -1, "sweatlossduration": -1, "sweatlossmeasuretimeutc": "", "sleepscore": -1, "sleepduration": -1, "deepsleepduration": -1, "sleepscoremeasuretimeutc": "", "energyscore": -1, "energyscoremeasuretimeutc": "", "steps": $steps, "floors": ${floors?.roundToInt()}, "calories": ${calories?.roundToInt()}, "exertion": $exert, "fatmass": -1, "musclemass": -1, "biameasuretimeutc": "", "batt": $bat } ], "alerts" : [ ], "resolvedalerts" : [ ] }, "geometry": { "type": "Point", "coordinates": [ $lon, $lat ] } } ] }
        """.trimIndent()

                } else {
                    """
        { "type": "FeatureCollection", "features": [ { "type": "Feature", "id": "$uid", "properties": { "timeutc": "$startTime", "staletimeutc": "$staleTime", "callsign": "$callsign", "role": "$role", "color": "$teamValue", "phone": "+11234567890", "model": "WEAROS", "device": "Samsung Galaxy Watch", "hae": $hae, "horizaccuracy": $ceTrim, "vertaccuracy": $leTrim, "heading": $courseTrim, "speed": $speedTrim, "batt": ${bat}, "biometrics": [ { "model": "WEAROS", "device": "Samsung Galaxy Watch", "isworn": $isWorn, "hr": $hr, "hribi": -1, "hrv": -1, "skintemp": $skt, "ambienttemp": -1, "coretemp": -1, "spo2": -1, "spo2measuretimeutc": "", "vo2": -1, "vo2measuretimeutc": "", "vo2max": -1, "vo2maxmeasuretimeutc": "", "sweatloss": -1, "sweatlossduration": -1, "sweatlossmeasuretimeutc": "", "sleepscore": -1, "sleepduration": -1, "deepsleepduration": -1, "sleepscoremeasuretimeutc": "", "energyscore": -1, "energyscoremeasuretimeutc": "", "steps": -1, "floors": -1, "calories": -1, "exertion": $exert, "fatmass": -1, "musclemass": -1, "biameasuretimeutc": "", "batt": $bat } ], "alerts" : [ ], "resolvedalerts" : [ ] }, "geometry": { "type": "Point", "coordinates": [ $lon, $lat ] } } ] }
        """.trimIndent()
                }

            Log.d(TAG, "esriGeojsonMsg: $esriGeojsonMsg")
        }

        if (enableTabletCommandEndpoint) {
            val isWorn = if (-1 == physSnap.data.heartRateBpm) { false } else { true }
            tabletCommandJsonMsg = if (MainActivity.enablePhysiologicalServices && (MainActivity.enableActRecService || MainActivity.enableDynamicReportingStrategy)) {
                """
                {"id":"$uid","timeutc":"$startTime","staletimeutc":"$staleTime","callsign":"$callsign","role":"$role","color":"$teamValue","phone":"+13014058648","model":"WEAROS","device":"Samsung Galaxy Watch","lat":$lat,"lon":$lon,"hae":$hae,"horizaccuracy":$ceTrim,"vertaccuracy":$leTrim,"heading":$courseTrim,"speed":$speedTrim,"batt":${bat},"biometrics":[{"model":"WEAROS","device":"Samsung Galaxy Watch","isworn":$isWorn,"hr":${bat},"hribi":-1,"hrv":-1,"skintemp":$skt,"ambienttemp":-1,"coretemp":-1,"spo2":-1,"spo2measuretimeutc":"","vo2":-1,"vo2measuretimeutc":"","vo2max":-1,"vo2maxmeasuretimeutc":"","sweatloss":-1,"sweatlossduration":-1,"sweatlossmeasuretimeutc":"","sleepscore":-1,"sleepduration":-1,"deepsleepduration":-1,"sleepscoremeasuretimeutc":"","energyscore":-1,"energyscoremeasuretimeutc":"","steps":${steps},"floors":${dailyMetrics?.floors?.roundToInt()},"calories":${dailyMetrics?.calories?.roundToInt()},"exertion":$exert,"fatmass":-1,"musclemass":-1,"biameasuretimeutc":"","batt":${bat}}],"alerts":[],"resolvedalerts":[]}
                """.trimIndent()
            } else {
                """
                {"id":"$uid","timeutc":"$startTime","staletimeutc":"$staleTime","callsign":"$callsign","role":"$role","color":"$teamValue","phone":"+13014058648","model":"WEAROS","device":"Samsung Galaxy Watch","lat":$lat,"lon":$lon,"hae":$hae,"horizaccuracy":$ceTrim,"vertaccuracy":$leTrim,"heading":$courseTrim,"speed":$speedTrim,"batt":${bat},"biometrics":[{"model":"WEAROS","device":"Samsung Galaxy Watch","isworn":$isWorn,"hr":${bat},"hribi":-1,"hrv":-1,"skintemp":$skt,"ambienttemp":-1,"coretemp":-1,"spo2":-1,"spo2measuretimeutc":"","vo2":-1,"vo2measuretimeutc":"","vo2max":-1,"vo2maxmeasuretimeutc":"","sweatloss":-1,"sweatlossduration":-1,"sweatlossmeasuretimeutc":"","sleepscore":-1,"sleepduration":-1,"deepsleepduration":-1,"sleepscoremeasuretimeutc":"","energyscore":-1,"energyscoremeasuretimeutc":"","steps":-1,"floors":-1,"calories":-1,"exertion":$exert,"fatmass":-1,"musclemass":-1,"biameasuretimeutc":"","batt":${bat}}],"alerts":[],"resolvedalerts":[]}
                """.trimIndent()
            }
            //Log.d(TAG, "tabletCommandJsonMsg: $tabletCommandJsonMsg")
        }


        return cot
    }

    private var gateCtr = 0
    private fun lgGate(msg: String) = Log.d(TAG, "[gate#${++gateCtr}] $msg")

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
                hail.setHeartRate(physioData.heartRateBpm?.toShort() ?: 0)
                hail.setSkinTemp(physioData.skinTempF?.toInt()?.toShort() ?: 0)
                hail.setWeight(physioData.weightKg?.toShort() ?: -1)
                hail.setAge(physioData.ageYears?.toByte() ?: -1)
                if (manualAlertMap.isEmpty()) {
                    hail.setMedevent(false)
                } else {
                    hail.setMedevent(true)
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
    /*
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
    */

    private fun buildChatCotMessage(location: Location, chatMsg: String, dstUid: String, dstChatroom: String): String {
        dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"))
        val calendar: Calendar = Calendar.getInstance()

        // Set the current time
        calendar.setTime(Date())

        // TODO Senni: why emergency in emergencyCallsignDateTimeFormatter and others
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

        // TODO Senni: We should use the LocationSnapshot here or enable below
//        // Ble Beacon
//        if (MainActivity.enableBleBeaconService) {
//            val bleLocationOutput = mapLocationAgainstClosestBleBeacon(startTimeInMs, latActual, lonActual, haeActual, ceActual)
//            latActual = bleLocationOutput.lat; lonActual = bleLocationOutput.lon; haeActual = bleLocationOutput.hae; ceActual = bleLocationOutput.ce
//        }

        val uid = UUID.randomUUID().toString()
        val linkUid = MainActivity.device_uid
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

    /*
    private fun buildEmergencyAlertCotMessage(uid: String, location: Location, emergencyCategory: String, emergencyDesc: String, alertState: AlertState): String {
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
//        if (MainActivity.enableBleBeaconService) {
//            val bleLocationOutput = mapLocationAgainstClosestBleBeacon(startTimeInMs, latActual, lonActual, haeActual, ceActual)
//            latActual = bleLocationOutput.lat; lonActual = bleLocationOutput.lon; haeActual = bleLocationOutput.hae; ceActual = bleLocationOutput.ce
//        }

        val linkUid = MainActivity.device_uid

        if (alertState == AlertState.CANCEL) {
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
*/

    private fun buildEmergencyAlertCotMessage(snapshot: DataRepository.EmergencyEventSnapshot): String {
        val uid        = snapshot.uid
        val alertState = snapshot.alertState
        val loc        = snapshot.location
        val time       = snapshot.time
        val sys        = snapshot.system

        val callsign   = sys.callsign
        val linkUid    = sys.deviceId

        // Location fields from LocationSnapshot
        val latActual = loc?.lat ?: 0.0
        val lonActual = loc?.lon ?: 0.0
        val haeActual = loc?.hae ?: 0.0
        var ceActual  = loc?.ce ?: 9999999.0F
        var leActual  = loc?.le ?: 9999999.0F
        if (ceActual.isNaN()) ceActual = 9999999.0F
        if (leActual.isNaN()) leActual = 9999999.0F

        // Use snapshot time instead of rebuilding Calendar
        val startTime = time.startCot
        val staleTime = time.staleCot

        // If you still want a short HHmmss suffix, you can derive it from time.timeMillis later.
        val emergencyCallsign = callsign

        return if (alertState == DataRepository.AlertState.CANCEL) {
            """
        <?xml version='1.0' encoding='UTF-8' standalone='yes'?>
        <event version='2.0' uid='$uid' type='b-a-o-can' time='$startTime' start='$startTime' stale='$staleTime' how='h-e' access='Undefined'>
        	<point lat='$latActual' lon='$lonActual' hae='$haeActual' ce='$ceActual' le='$leActual' />
        	<detail>
        		<emergency cancel='true'>$callsign</emergency>
        	</detail>
        </event>
        """.trimIndent()
        } else {
            """
        <?xml version='1.0' encoding='UTF-8' standalone='yes'?>
        <event version='2.0' uid='$uid' type='b-a-o' time='$startTime' start='$startTime' stale='$staleTime' how='h-e' access='Undefined'>
        	<point lat='$latActual' lon='$lonActual' hae='$haeActual' ce='$ceActual' le='$leActual' />
        	<detail>
        		<link uid='$linkUid' type='a-f-G-U-C' relation='p-p'/>
        		<contact callsign='$emergencyCallsign&#10;${snapshot.description}'/>
        		<emergency type='${snapshot.description}'>$callsign</emergency>
                <usericon iconsetpath='911 Alert'/>
                <color argb='-1'/>
        	</detail>
        </event>
        """.trimIndent()
        }
    }

    fun rectifiedIntToString(value: Int?): String {
        return if (value == null || value < 0) {
            "N/A"
        } else {
            value.toString()
        }
    }

    private fun escapeXmlText(s: String): String {
        val out = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;") // only strictly needed in attributes, but safe
                '\'' -> out.append("&apos;") // only strictly needed in attributes, but safe
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun finalMarkerTitle(markerTitle: String?): String {
        // Marker with Time no Date.
        val calendar: Calendar = Calendar.getInstance()
        calendar.setTime(Date()) // Set the current time
        val markerCallsignTimeFormatter = SimpleDateFormat("HHmmss'Z'")
        markerCallsignTimeFormatter.timeZone = TimeZone.getTimeZone("UTC")
        val markerCallsignAppendTime = markerCallsignTimeFormatter.format(calendar.time)
        return if (markerTitle.isNullOrEmpty()) {
            "${callsign}_$markerCallsignAppendTime"
        } else {
            escapeXmlText(markerTitle)
        }
    }

    private fun buildMarkerCotMessage(ms: DataRepository.MarkerSnapshot?): String {
        val uid         = ms?.uid
        val markerType  = ms?.markerType?.type
        val markerTitle = ms?.title
        val battery     = ms?.system?.batteryPercent

        val startTime   = ms?.time?.startCot
        val staleTime   = ms?.time?.staleCot
        val loc         = ms?.loc

        val markerTitleCallsignOrCustom = finalMarkerTitle(markerTitle)

        val lat   = loc?.lat
        val lon   = loc?.lon
        val hae   = loc?.hae
        var ce    = loc?.ce ?: 9999999.0F
        var le    = loc?.le ?: 9999999.0F
        if (ce.isNaN()) ce = 9999999.0F
        if (le.isNaN()) le = 9999999.0F

        val linkUid = MainActivity.device_uid
        //Log.d(TAG, "Dropped marker: $markerType, $markerTitleCallsignOrCustom, $callsign")

        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <event version="2.0" uid="$uid" type="$markerType" time="$startTime" start="$startTime" stale="$staleTime" how="h-g-i-g-o" access="Undefined">
            <point lat="$lat" lon="$lon" hae="$hae" ce="$ce" le="$le"/>
            <detail>
                <status readiness="true" battery='$battery'/>
                <precisionlocation altsrc="SRTM1"/>
                <link uid="$linkUid" production_time="$startTime" type="a-f-G-U-C" parent_callsign="$callsign" relation="p-p"/>
                <color argb="-1"/>
                <usericon iconsetpath="$iconSetPath"/>
                <remarks></remarks> 
                <contact callsign="$markerTitleCallsignOrCustom"/>
            </detail>
        </event>
        """.trimIndent()
    }


    /*
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

        var latActual   = location.latitude
        var lonActual   = location.longitude
        var haeActual   = location.altitude
        var ceActual    = location.accuracy
        if (ceActual.isNaN()) ceActual = 9999999.0F
        var leActual : Float = location.verticalAccuracyMeters
        if (leActual.isNaN()) leActual = 9999999.0F
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
*/


    private fun makeMarkerSnapshotStale(snapshot: DataRepository.MarkerSnapshot): DataRepository.MarkerSnapshot {
        // If the staleTime < startTime, TAK assumes the marker is expired and clears it. This is a little hack to do that.
        val clearTime = Utils.getCotEventTimeSnapshot(
            startOffset = Utils.TimeOffset(seconds = -4),
            staleOffset = Utils.TimeOffset(minutes = -3)
        )

        // Inject only the new clearTime.
        val clearedSnapshot = snapshot.copy(
            time = clearTime
        )

        return clearedSnapshot
    }

    private fun buildClearMarkerCotMessage(snapshot: DataRepository.MarkerSnapshot?): String {
        if (snapshot == null) return ""

        // failsafe to ensure the snapshot is stale, in case this function is called without a stale snapshot
        val clearedSnapshot = makeMarkerSnapshotStale(snapshot)

        return buildMarkerCotMessage(clearedSnapshot)
    }

    /*
    private fun buildClearMarkerCotMessage(uid: String, cotInfo: Utils.CotUtils.CotMarkerInfo?): String {
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
    */

    private suspend fun companionAppAvailable(): Boolean {
        val enabled = dataRepository.getEnableCompanionAppService.first()
        val ready = capRepo.companionAppReady.first()
        return enabled && ready
    }

    private suspend fun sitxAvailable(): Boolean {
        val enabled = dataRepository.getEnableSitxService.first()
        val connected = dataRepository.sitxStatus.value == SitxServerStatus.CONNECTED
        //Log.i(TAG, "sitx available: $enabled, $connected")
        return enabled && connected
    }

    private suspend fun handleClearMarkerUid(uid: String) {
        val snapshot = markerDropMap[uid]  // may be null for very old markers
        // TODO: How do we inject clearing via stale time flip?
        if (snapshot == null) return
        val staleSnapshot = makeMarkerSnapshotStale(snapshot)
        val clearXml = buildClearMarkerCotMessage(staleSnapshot)

        var sent = false
        if (companionAppAvailable()) {
            capRepo.enqueueMarkerSnapshotForBle(staleSnapshot)
            sent = true
        }
        if (DittoService.isRunning.get()) {
            EventBus.getDefault().post(DittoDocumentEvent("mapitem", clearXml))
            sent = true
        }
        if (TAKConnectService.isAnyTakConnected) {
            sendDataTAKServers(clearXml, true)
            sent = true
        }
        if (sitxAvailable()) {
            SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(clearXml))
            sent = true
        }
        if (!sent) return

        Log.i(TAG, "Clearing Marker Drop [ $uid ] of type ${snapshot?.markerType?.type}")
        dataRepository.removeMarkerByUid(uid)
        markerDropMap.remove(uid)
    }

    private suspend fun handleClearMarkerAll() {
        val uids = dataRepository.droppedMarkerList.value.map { it.uid }
        uids.forEach { handleClearMarkerUid(it) }
    }

/*
    // Used by the Location Updates EventBus
    private fun sendMarkerCotMessage() {
        serviceScope.launch {
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
                SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(cotMessage))
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
/*
    private fun sendEmergencyAlertCotMessage(emergencyCategory: String, emergencyDesc: String, sendSms: Boolean, alertState: DataRepository.AlertState) {
        serviceScope.launch {
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

            val uid = if (alertState == DataRepository.AlertState.ALERT) {
                // Generate and store a new UID
                val newUid = UUID.randomUUID().toString()
                sosAlertUid = newUid
                newUid
            } else {
                // Use the previously stored UID for canceling
                sosAlertUid
            }

            // build the CoT XML for this alert
            val cotMessage = buildEmergencyAlertCotMessage(uid, loc, emergencyCategory, emergencyDesc, alertState)
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
                SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(cotMessage))
                if (!takGptChatMessage.isEmpty()) {
                    //val rawXml="<?xml version=\"1.0\" encoding=\"UTF-8\"?><event version=\"2.0\" uid=\"GeoChat.TAKBot.Chat\" type=\"b-t-f\" how=\"h-g-i-g-o\" time=\"2025-08-27T18:12:54Z\" start=\"2025-08-27T18:12:54Z\" stale=\"2025-08-28T06:12:54Z\"><point lat=\"0.0\" lon=\"0.0\" hae=\"999999.0\" ce=\"999999.0\" le=\"999999.0\"/><detail><__chat parent=\"RootContactGroup\" messageId=\"3c5eb0a3-31f8-4a32-b6cc-6916eff3cbd1\" senderCallsign=\"takGPT\" chatroom=\"takGPT\" id=\"TAKBOT-12345\"><chatgrp id=\"WEAROS-dce34c83708172e9\" uid1=\"WEAROS-dce34c83708172e9\" uid0=\"TAKBOT-12345\"/></__chat><remarks time=\"2025-08-27T18:12:54Z\" to=\"SP-Dev\">**CONFIDENCE:** HIGH - Patient’s heart rate &gt;150 bpm sustained for 30 seconds is a clinically significant tachycardia warranting immediate assessment and intervention.**CLINICAL REASONING:**1. Sustained heart rate &gt;150 bpm suggests a tachyarrhythmia that may compromise cardiac output.2. Assess for hemodynamic stability, symptoms, and possible causes (hypovolemia, hypoxia, pain, anxiety, or arrhythmia).3. Prioritize restoring effective circulation to prevent shock or cardiac arrest.4. Interventions depend on clinical context; chemical or electrical cardioversion considered for unstable supraventricular or ventricular tachycardia with appropriate diagnosis.5. Follow MARCH priority focused on Circulation due to hemodynamic compromise risk.**PRIORITY:**Circulation is the greatest immediate threat for this scenario due to the risk of hemodynamic instability or progression to cardiac arrest from sustained high-rate tachycardia.**IMMEDIATE ACTIONS:**- Assess airway patency and respiratory effort.- Provide high-flow oxygen via non-rebreather mask or appropriate delivery device.- Establish large-bore IV access.- Perform focused history/physical to identify tachycardia etiology.- Obtain a 12-lead ECG ASAP.- If unstable (hypotension, altered LOC, chest pain, signs of shock), prepare for synchronized cardioversion.- Fluid resuscitation if hypovolemia suspected.- Consider analgesia/anxiolysis if pain or anxiety contributing.**MEDICATIONS:**- Adenosine or other antiarrhythmics only after rhythm diagnosis via ECG and following ACLS protocols.- Vasopressors/inotropes per hemodynamics if indicated.- Specific medication agents and doses depend on arrhythmia type and patient status.**If additional clinical data or diagnostics become available, recommendations can be refined.**</remarks><link relation=\"p-p\" type=\"a-f-G-U-C-I\" uid=\"TAKBOT-12345\"/><_flow-tags_ TAK-Server-7b47b67360f347689e0c580092d1c325=\"2025-08-27T18:12:54Z\"/></detail></event>"
                    sendDataTAKServers(takGptChatMessage, true)
                    SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(cotMessage))
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
*/

    private suspend fun sendEmergencyAlert(alertWithSmsSentState: CustomEmergencyAlertWithAddedState): Boolean {
        val alert = alertWithSmsSentState.alert
        // SMS first (doesn’t depend on location)
        if (MainActivity.enableSmsForAlerts && alert.sendSms && !alertWithSmsSentState.smsSent) {
            SMSManager.sendSMSMessage(numbers = smsNumbersList, description = "${alert.category}: ${alert.description}")
            alertWithSmsSentState.smsSent = true
        }

        val snapshot = dataRepository.snapshotEmergencyEvent(alert)
        val xml = buildEmergencyAlertCotMessage(snapshot)

        // TAK / Ditto / multicast routing unchanged, just using `cotMessage`
        var sent = false
        if (companionAppAvailable()) {
            Log.i(TAG, "Send Marker payload via BLE ...")
            capRepo.enqueueEmergencySnapshotForBle(snapshot)
            sent = true
        }
        if (dataRepository.getEnableMulticast.first()) {
            sendMulticastDatagram(xml)
            sent = true
        }
        if (DittoService.isRunning.get()) {
            EventBus.getDefault().post(DittoDocumentEvent("mapitem", xml))
            sent = true
        }
        if (TAKConnectService.isAnyTakConnected) {
            sendDataTAKServers(xml, true)
            sent = true
        }
        if (sitxAvailable()) {
            //Log.i(TAG, "Sending emergency alert via SitxServiceCommandBus ...")
            SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(xml))
            sent = true
        }

        if (sent) {
            Log.i(TAG, "sendEmergencyAlert: Sent alert: $alertWithSmsSentState")
        } else {
            Log.i(TAG, "sendEmergencyAlert: Could NOT send alert: $alertWithSmsSentState")
        }

        return sent
    }

    fun buildAndSendPhysioLocationCotMsg() {
        // Blocks the current (scheduler) thread until the work is done.
        runBlocking(Dispatchers.IO) {
            val snapshot = dataRepository.snapshotPli()

            val xml = buildPliCot(snapshot)

            if (TAKConnectService.isAnyTakConnected) {
                sendDataTAKServers(xml, false)
            }
            SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(xml))

            if (companionAppAvailable()) {
                capRepo.enqueuePliSnapshotForBle(snapshot)
            }

            // TODO Senni: check for flag??
            // if (dataRepository.getEnableMulticast.first()) { sendMulticastDatagram(xml) }. TODO Senni: What is the diff between
            NetworkUtils.MulticastPublisher.send(xml) // always multicast
        }
    }

//    @Subscribe(threadMode = ThreadMode.ASYNC)
//    public fun onLocationRcvdEvent(event: LocationRcvd) {
//        if (manualSosToBeSent.get() == true) {
//            Log.d(TAG, "onLocationRcvdEvent called with manualSosToBeSent true")
//            val action = if (cancelSosAlert) {
//                DataRepository.AlertState.CANCEL
//            } else {
//                DataRepository.AlertState.ALERT
//            }
//            sendEmergencyAlertCotMessage(manualSosReceivedCategory, manualSosReceivedDescription, manualSosReceivedSendSms, action)
//        }
//        lastLocationUpdateRcvd = System.currentTimeMillis()
//        serviceScope.launch {
//            if (pendingDropQueue.isNotEmpty() && sendMarkerReady()) {
//                // TODO: Need to take care of situation if no TAK server is connected
//                sendPendingMarkerDrops()
//            }
//        }
//        buildAndSendPhysioLocationCotMsg()
//    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onLocationRcvdEvent(event: LocationRcvd) {
        //Log.i(TAG, "onLocationRcvdEvent called")
        lastLocationUpdateRcvd = System.currentTimeMillis()
        if (readyToSendAlertEvents()) {
            // Flush pending Manual emergency (if any)
            if (pendingManualAlertQueue.isNotEmpty()) {
                serviceScope.launch {
                    sendAllManualAlerts(null)
                }
            }
            // Flush pending Physio emergency (if any)
            if (pendingPhysioAndAutoGenAlertQueue.isNotEmpty()) {
                serviceScope.launch {
                    sendAllPhysioAndAutoGenAlerts(null)
                }
            }
        }

        if (pendingMarkerDropQueue.isNotEmpty() && readyToSendMarkerEvents()) {
            serviceScope.launch {
                sendPendingMarkerDrops()
            }
        }

        if (pendingMarkerEditQueue.isNotEmpty() && readyToSendMarkerEvents()) {
            serviceScope.launch {
                sendPendingMarkerEdits()
            }
        }

        buildAndSendPhysioLocationCotMsg()
    }

    private fun enqueueDrop(req: DataRepository.MarkerDropRequest) {
        Log.i(TAG, "===> adding to the marker drop queue ...")
        pendingMarkerDropQueue.addLast(req)
    }

    private fun enqueueEdit(req: DataRepository.MarkerEditRequest) {
        Log.i(TAG, "===> adding marker edit for uid=${req.uid} to queue ...")
        pendingMarkerEditQueue.addLast(req)
    }

    private fun sendPendingMarkerDrops() {
        serviceScope.launch {
            while (pendingMarkerDropQueue.isNotEmpty()) {
                Log.i(TAG, "===> removing ${pendingMarkerDropQueue.first().markerType} from time ${(pendingMarkerDropQueue.first().timeMillis)}...")
                val req = pendingMarkerDropQueue.removeFirst()
                sendMarkerDropNow(req)
            }
        }
    }

    private fun sendPendingMarkerEdits() {
        serviceScope.launch {
            while (pendingMarkerEditQueue.isNotEmpty()) {
                val req = pendingMarkerEditQueue.removeFirst()
                Log.i(TAG, "===> removing marker edit request for uid=${req.uid} from queue ...")
                sendMarkerEditNow(req)
            }
        }
    }

    private suspend fun dispatchMarkerSnapshot(snapshot: DataRepository.MarkerSnapshot?, xml: String): Boolean {
        if (snapshot == null) return false
        var sent = false

        if (companionAppAvailable()) {
            Log.i(TAG, "Send Marker payload via BLE ...")
            capRepo.enqueueMarkerSnapshotForBle(snapshot)
            Log.i(TAG, "$xml")
            sent = true
        }
        if (dataRepository.getEnableMulticast.first()) {
            sendMulticastDatagram(xml)
            sent = true
        }
        if (DittoService.isRunning.get()) {
            EventBus.getDefault().post(DittoDocumentEvent("mapitem", xml))
            sent = true
        }
        if (TAKConnectService.isAnyTakConnected) {
            sendDataTAKServers(xml, true)
            sent = true
        }
        if (sitxAvailable()) {
            SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(xml))
            sent = true
        }

        return sent
    }

    private suspend fun sendMarkerDropNow(req: DataRepository.MarkerDropRequest) {

        val snapshot = dataRepository.snapshotMarker(req)
        if (snapshot == null) {
            Log.w(TAG, "Skipping marker drop send; no location snapshot available")
            return
        }

        Log.i(TAG, "sendDropNow::markerType:: = [ ${snapshot.markerType.type} ]")
        val xml = buildMarkerCotMessage(snapshot)
        val sent = dispatchMarkerSnapshot(snapshot, xml)
        if (!sent) return  // don’t reflect in UI until actually sent/meshed

        markerDropMap[snapshot.uid] = snapshot

        Log.i(TAG, "=== ===> sending marker type ${snapshot.markerType.type} with uid = [ ${snapshot.uid} ] ")

        val markerTitleCallsignOrCustom = finalMarkerTitle(snapshot.title)

        dataRepository.appendDroppedMarker(
            DataRepository.DroppedMarker(uid = snapshot.uid, markerType = snapshot.markerType.type, title = markerTitleCallsignOrCustom, timeDropped = req.timeMillis, location = snapshot.loc)
        )
    }

    private suspend fun sendMarkerEditNow(req: DataRepository.MarkerEditRequest) {
        val snapshot = dataRepository.snapshotMarker(
            DataRepository.MarkerDropRequest(
                markerType = req.markerType,
                title = req.title,
                timeMillis = req.timeMillis,
                uid = req.uid
            )
        )

        if (snapshot == null) {
            Log.w(TAG, "Skipping marker edit send for uid=${req.uid}; no location snapshot available")
            return
        }

        val xml = buildMarkerCotMessage(snapshot)
        val sent = dispatchMarkerSnapshot(snapshot, xml)
        if (!sent) return

        markerDropMap[req.uid] = snapshot
        Log.i(TAG, "=== ===> sent marker edit for uid=[${req.uid}] type=[${snapshot.markerType.type}]")
    }

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

    private suspend fun setValuesFromCustomDeveloperConfig() {
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
        private const val FRESH_GPS_INTERVAL_MILLIS = 3_000L
        private const val COT_DISPATCH_SERVICE_NOTIFICATION_ID = 100
        var GPS_STALE_TIME_INTERVAL_MS = 10_000L
        var OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT = 30
        private const val GPS_STALE_TIME_INTERVAL_MS_JSON_KEY = "gpsStaleTimeInterval"
        private const val OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT_JSON_KEY = "overrideGpsWithBleExitHysteresisCount"
        val isRunning : AtomicBoolean = AtomicBoolean(false)

        // TODO Senni: Reconcile all reporting intervals below
        private val _actualReportingIntervalSecsFlow =
            MutableStateFlow(DEFAULT_CONSTANT_REPORTING_INTERVAL)
        val actualReportingIntervalSecsFlow: StateFlow<Int> =
            _actualReportingIntervalSecsFlow.asStateFlow()

        var actualReportingIntervalSecs: Int = DEFAULT_CONSTANT_REPORTING_INTERVAL
            set(value) {
                if (field == value) return
                field = value
                _actualReportingIntervalSecsFlow.value = value
            }

        private val _desiredReportingIntervalSecsFlow =
            MutableStateFlow(DEFAULT_CONSTANT_REPORTING_INTERVAL)
        val desiredReportingIntervalSecsFlow: StateFlow<Int> =
            _desiredReportingIntervalSecsFlow.asStateFlow()

        var desiredReportingIntervalSecs: Int = DEFAULT_CONSTANT_REPORTING_INTERVAL
            set(value) {
                if (field == value) return
                field = value
                _desiredReportingIntervalSecsFlow.value = value
            }

        lateinit var instance: CotDispatchService
        val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        var esriGeojsonMsg = ""
        var tabletCommandJsonMsg = ""
    }
}
