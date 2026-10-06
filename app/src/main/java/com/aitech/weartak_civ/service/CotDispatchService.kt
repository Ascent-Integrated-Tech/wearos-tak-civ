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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.DEFAULT_CONSTANT_REPORTING_INTERVAL
import com.aitech.weartak_civ.Utils.getValueFromJson
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DataRepository.PhysioData
import com.aitech.weartak_civ.data.DataRepository.SitxServerStatus
import com.aitech.weartak_civ.cot.ChatMessage
import com.aitech.weartak_civ.cot.CotCompanionPayload
import com.aitech.weartak_civ.cot.CotDetail
import com.aitech.weartak_civ.cot.CotEndpointAvailability
import com.aitech.weartak_civ.cot.CotEndpointDispatcher
import com.aitech.weartak_civ.cot.CotEventTimeFactory
import com.aitech.weartak_civ.cot.CotMessageBuilder
import com.aitech.weartak_civ.cot.CotMessageKind
import com.aitech.weartak_civ.cot.CotOutboundMessage
import com.aitech.weartak_civ.cot.CotTimeOffset
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.aitech.weartak_civ.events.LocationRcvd
import com.aitech.weartak_civ.events.LocationUpdateRequired
import com.aitech.weartak_civ.events.MessageEvent.Companion.CALLSIGN_TEAM_ROLE_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED
import com.aitech.weartak_civ.manager.SMSManager
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.bhRepo
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableEsriEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableTabletCommandEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBatdokCot
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.smsNumbersList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.presentation.requestBloodhoundComplicationUpdate
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
    private val markerDropMap = java.util.concurrent.ConcurrentHashMap<String, DataRepository.DroppedMarker>()
    private val pendingMarkerCotList = mutableListOf<DataRepository.DroppedMarker>()

    private val pendingChatMsgQueue = ArrayDeque<ChatMessage>()

    private var combatId: Int = 0

    private var sentBatteryWarnChatMsg = false
    private var sentBatteryAlertChatMsg = false

    private val capRepo by lazy {
        try {
            (applicationContext as WearTakApplication).capRepo
        } catch (_: Throwable) {
            CompanionAppRepository(applicationContext)
        }
    }

    private val endpointDispatcher by lazy {
        CotEndpointDispatcher(
            sendTak = ::sendDataTAKServers,
            sendSitx = { xml -> SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(xml)) },
            sendDitto = { collection, xml -> EventBus.getDefault().post(DittoDocumentEvent(collection, xml)) },
            sendMulticast = ::sendMulticastDatagram,
            sendPliMulticast = NetworkUtils.MulticastPublisher::send,
            sendCompanion = { payload ->
                when (payload) {
                    is CotCompanionPayload.Pli -> capRepo.enqueuePliSnapshotForBle(payload.snapshot)
                    is CotCompanionPayload.Chat -> capRepo.enqueueChatSnapshotForBle(payload.snapshot)
                    is CotCompanionPayload.Marker -> capRepo.enqueueMarkerSnapshotForBle(payload.snapshot)
                    is CotCompanionPayload.Emergency -> capRepo.enqueueEmergencySnapshotForBle(payload.snapshot)
                }
            },
        )
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
        dataRepository = MainActivity.dataRepository

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

        collectHailFlows()

        collectReportingIntervalSecs()
        startReportingIntervalRepoBridge()

        startMarkerPipelines()

        startManualAlertPipelines()

        startPhysioAndAutoGenAlertPipelines()

        startSendChatMessageCollector()

        startAnyEndpointConnectedCollector()
    }

    private fun startAnyEndpointConnectedCollector() {
        serviceScope.launch {
            dataRepository.anyEndpointConnected.collect { connected ->
                Log.i(TAG, "Any endpoint connected: $connected")
                if (connected) {
                    EventBus.getDefault().post(LocationUpdateRequired())
                    sendPendingChatMessages()
                    sendPendingManualAlerts()
                    sendPendingPhysioAndAutoGenAlerts()
                    sendPendingMarkerCots()
                }
            }
        }
    }

    private fun startReportingIntervalRepoBridge() {
        serviceScope.launch {
            actualReportingIntervalSecsFlow
                .collect { secs ->
                    dataRepository.setAlmightyReportingInterval(secs)
                }
        }
    }

    // The companion uses this interval as its BLE connection heartbeat.
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

                    val endpointsToSendTo = (TAKConnectService.isAnyTakConnected || (dataRepository.sitxStatus.value == DataRepository.SitxServerStatus.CONNECTED) || dataRepository.multicastPublisherRunning.value || companionAppAvailable())

                    if (endpointsToSendTo) {
                        if ((batt <= MainActivity.battWarnPercent)) {
                            Log.i(TAG, "Battery is at or below the WARN threshold: $batt <= ${MainActivity.battWarnPercent}")

                            if (batt > MainActivity.battAlertPercent) {
                                Log.i(TAG, "Battery is at or below the ALERT threshold: $batt <= ${MainActivity.battWarnPercent}")
                                if (!sentBatteryWarnChatMsg) {
                                    val msgText =
                                        "Watch Battery Warning: ${MainActivity.callsign} battery less than " +
                                                "${MainActivity.battWarnPercent}% (actual=$batt%)"
                                    val chatSnap = dataRepository.snapshotChatEvent(
                                        roomUid = "All Chat Rooms",
                                        roomTitle = "All Chat Rooms",
                                        message = msgText
                                    )

                                    val chatCot = CotMessageBuilder.chat(chatSnap, callsign, MainActivity.device_uid)
                                    Log.d(TAG, "Sending chat msg to 'All Chat Rooms' via snapshot: $chatCot")
                                    sendDataTAKServers(chatCot, true)
                                    if (companionAppAvailable()) {
                                        capRepo.enqueueChatSnapshotForBle(chatSnap)
                                    }
                                    if (dataRepository.multicastPublisherRunning.value) {
                                        sendMulticastDatagram(chatCot)
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

                                    val chatCot = CotMessageBuilder.chat(chatSnap, callsign, MainActivity.device_uid)
                                    Log.d(TAG, "Sending chat msg to 'All Chat Rooms' via snapshot: $chatCot")
                                    sendDataTAKServers(chatCot, true)
                                    if (companionAppAvailable()) {
                                        capRepo.enqueueChatSnapshotForBle(chatSnap)
                                    }
                                    if (dataRepository.multicastPublisherRunning.value) {
                                        sendMulticastDatagram(chatCot)
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
                    val sendToTak = TAKConnectService.isAnyTakConnected || (dataRepository.sitxStatus.value == DataRepository.SitxServerStatus.CONNECTED)
                    val sendToCompApp = companionAppAvailable()
                    val sendOverMulticast = dataRepository.multicastPublisherRunning.value
                    val endpointsToSendTo = sendToTak || sendToCompApp || sendOverMulticast


                    if (endpointsToSendTo && enabled) {
                        val msg = "Debug mode changed to { $enabled } on Watch: $callsign with uid: $deviceId"
                        val chatSnap = dataRepository.snapshotChatEvent(
                            roomUid = "All Chat Rooms",
                            roomTitle = "All Chat Rooms",
                            message = msg
                        )

                        if (sendToTak || sendOverMulticast) {
                            val chatCot = CotMessageBuilder.chat(chatSnap, callsign, MainActivity.device_uid)
                            if (sendToTak) {
                                sendDataTAKServers(chatCot, true)
                                SitxServiceCommandBus.trySend(SitxServiceCommand.SendCot(chatCot))
                            }
                            if (sendOverMulticast) {
                                sendMulticastDatagram(chatCot)
                            }
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
        // Alerts may send without a fix, but an existing fix must be fresh.
        val send = if (null == loc) true else (System.currentTimeMillis() - lastLocationUpdateRcvd) < FRESH_GPS_INTERVAL_MILLIS
        return (dataRepository.anyEndpointConnected.value && send)
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
                    if (dataRepository.anyEndpointConnected.value) {
                        EventBus.getDefault().post(LocationUpdateRequired())
                    }
                    enqueueManualAlertAndUpdateUI(manualAlertWithState)
                }
            }
        }
    }

    private suspend fun sendPendingManualAlerts(): Boolean {
        var sentAll = true
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
        if (readyToSendAlertEvents()) {
            sendAllManualAlerts(manualAlertClearWithState)
        } else {
            if (dataRepository.anyEndpointConnected.value) {
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
        Log.i(TAG, "Starting physioAndAutoGen alert pipelines")
        startAddPhysioAndAutoGenAlertCollector()
        startClearPhysioAndAutoGenAlertCollector()
    }

    private fun startAddPhysioAndAutoGenAlertCollector() {
        Log.i(TAG, "Subscribing to addPhysioAndAutoGenAlert flow")
        serviceScope.launch {
            dataRepository.addPhysioAndAutoGenAlert.collect { req ->
                Log.i(TAG, "Received req to add physio and auto generated alert: $req")
                Log.i(
                    TAG,
                    "Alert routing state before dispatch: ready=${readyToSendAlertEvents()} " +
                        "tak=${TAKConnectService.isAnyTakConnected} ditto=${DittoService.isRunning.get()} " +
                        "multicast=${dataRepository.multicastPublisherRunning.value} sitx=${sitxAvailable()} " +
                        "endpoints=${dataRepository.anyEndpointConnected.value}",
                )
                val physioAndAutoGenAlert = DataRepository.CustomEmergencyAlert(uuid = req.uuid, alertState = DataRepository.AlertState.ALERT, category = req.category, description = req.description, priority = req.priority, sendSms = req.sendSms, timeMillis = req.timeMillis, enqueuedToBeSent = false)
                val physioAndAutoGenAlertAlertWithState = CustomEmergencyAlertWithAddedState(smsSent = false, alert = physioAndAutoGenAlert)
                physioAndAutoGenAlertMap[physioAndAutoGenAlert.uuid] = physioAndAutoGenAlert
                if (readyToSendAlertEvents()) {
                    Log.i(TAG, "Alert is ready to send now: uid=${physioAndAutoGenAlert.uuid}")
                    sendAllPhysioAndAutoGenAlerts(physioAndAutoGenAlertAlertWithState)
                } else {
                    if (dataRepository.anyEndpointConnected.value) {
                        Log.i(TAG, "Alert not ready; requesting location refresh before send uid=${physioAndAutoGenAlert.uuid}")
                        EventBus.getDefault().post(LocationUpdateRequired())
                    }
                    Log.i(TAG, "Alert queued locally until routing becomes available uid=${physioAndAutoGenAlert.uuid}")
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
        Log.i(TAG, "Subscribing to clearPhysioAndAutoGenAlert flow")
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
        if (readyToSendAlertEvents()) {
            sendAllPhysioAndAutoGenAlerts(physioAndAutoGenAlertClearWithState)
        } else {
            if (dataRepository.anyEndpointConnected.value) {
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
                if (dataRepository.anyEndpointConnected.value) {
                    sendAllChatMessages(req)
                } else {
                    val chatMsg = ChatMessage(message = req.message, uid = req.dstUid, callsign = req.dstChatroom, timeReceived = req.timeMillis)
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

    private suspend fun sendChatMessage(chatMsg: ChatMessage): Boolean {
        val chatSnap = dataRepository.snapshotChatEvent(roomUid = chatMsg.uid, roomTitle = chatMsg.callsign, message = chatMsg.message)
        val chatCotXml = CotMessageBuilder.chat(chatSnap, callsign, MainActivity.device_uid)
        val sent = dispatch(
            CotOutboundMessage(chatCotXml, CotMessageKind.CHAT, CotCompanionPayload.Chat(chatSnap)),
        )

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
            val chatMsg = ChatMessage(message = chatReq.message, uid = chatReq.dstUid, callsign = chatReq.dstChatroom, timeReceived = chatReq.timeMillis)
            if (!sendChatMessage(chatMsg)) {
                pendingChatMsgQueue.addLast(chatMsg)
                Log.i(TAG, "pendingChatMsgQueue=${pendingChatMsgQueue.size}")
            }
        }
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
                val waitingForUpdatedLocation = if ((null != req.lat) && (null != req.lon)) false else !isLocationFresh()
                val droppedMarker = DataRepository.DroppedMarker(cotType = DataRepository.MarkerCotType.DROP, uid = req.uid.ifEmpty{ UUID.randomUUID().toString() }, markerType = req.markerType,
                    title = finalMarkerTitle(req.title), remark = req.remark, timeDropped = req.timeMillis,
                    location = dataRepository.snapshotMarkerLocation(req.uid, req.lat, req.lon, req.timeMillis), enqueuedToBeSent = true, waitingForUpdatedLocation = waitingForUpdatedLocation)

                enqueueMarkerDrop(droppedMarker)
                markerDropMap[droppedMarker.uid] = droppedMarker
                dataRepository.appendDroppedMarker(droppedMarker)

                    if (waitingForUpdatedLocation) {
                    Log.i(TAG, "Drop lat(${req.lat}) and lon(${req.lon}) is null or location is stale. En-queued marker drop...")
                    EventBus.getDefault().post(LocationUpdateRequired())
                } else {
                    Log.i(TAG, "Drop lat(${req.lat}) and lon(${req.lon}) NOT null or location is fresh. Trying to send marker drop request now if possible...")
                    if (dataRepository.anyEndpointConnected.value) {
                        sendPendingMarkerCots()
                    }
                }
            }
        }
    }

    private fun startMarkerEditCollector() {
        serviceScope.launch {
            dataRepository.markerEditRequests.collect { req ->
                Log.i(TAG, "Received marker edit request for uid=${req.uid}")
                val markerDrop = markerDropMap[req.uid]
                if (null == markerDrop) {
                    Log.w(TAG, "Cannot find marker drop for uid=${req.uid}. Ignoring this...")
                } else if (markerDrop.cotType == DataRepository.MarkerCotType.CLEAR) {
                    Log.w(TAG, "Cannot edit a marker that has already scheduled to be cleared. Ignoring this...")
                } else {
                    val waitingForUpdatedLocation = req.updateLocation || markerDrop.waitingForUpdatedLocation
                    val editedMarkerDrop = markerDrop.copy(cotType = DataRepository.MarkerCotType.EDIT, markerType = req.markerType, title = finalMarkerTitle(req.title),
                        remark = req.remark, timeDropped = req.timeMillis, location = markerDrop.location,
                        enqueuedToBeSent = true, waitingForUpdatedLocation = waitingForUpdatedLocation)
                    enqueueMarkerEdit(editedMarkerDrop)
                    markerDropMap[req.uid] = editedMarkerDrop
                    if (waitingForUpdatedLocation) {
                        Log.i(TAG, "Marker Edit updateLocation(=${req.updateLocation}) is true or previously waitingForUpdatedLocation(=${markerDrop.waitingForUpdatedLocation}) is true. En-queued marker edit...")
                        EventBus.getDefault().post(LocationUpdateRequired())
                    } else {
                        Log.i(TAG, "Marker edit waitingForUpdatedLocation is false. Trying to send edit marker request now if possible...")
                        if (dataRepository.anyEndpointConnected.value) {
                            sendPendingMarkerCots()
                        }
                    }
                }
            }
        }
    }

    private fun readyToSendMarkerEvents(): Boolean {
        return (dataRepository.anyEndpointConnected.value && isLocationFresh())
    }

    private fun isLocationFresh(): Boolean {
        val loc = dataRepository.latestLocation.value
        val fresh = (System.currentTimeMillis() - lastLocationUpdateRcvd) < FRESH_GPS_INTERVAL_MILLIS
        return (null != loc) && fresh
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
                Log.v(TAG, "[$index] cot=${marker.cotType} (locUpdate=${marker.waitingForUpdatedLocation}, q=${marker.enqueuedToBeSent}) : ${marker.timeDropped}, uid=${marker.uid}, type=${marker.markerType}, time=${marker.timeDropped}, title=${marker.title}, remark=${marker.remark}, loc=(${marker.location?.lat ?: "null"}, ${marker.location?.lon ?: "null"})")
            }
        }
    }

    private fun startMarkerClearCollector() {
        serviceScope.launch {
            dataRepository.clearMarkerRequests.collect { req ->
                Log.i(TAG, "Received req to clear marker: $req")
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
                // Fused location can update more slowly indoors, so dispatch when the reporting deadline lapses.
                buildAndSendPhysioLocationCotMsg()
            }
            cotDispatchHandler?.postDelayed(this, actualReportingIntervalSecs * 1000L)
        }
    }

    data class BleLocationOutput(
        val lat: Double,
        val lon: Double,
        val hae: Double,
        val ce: Float // Horizontal Error
    )

    private fun buildPliCot(snapshot: DataRepository.PliSnapshot): String {
        val compatibilityPayloads = LegacyPliPayloadBuilder.build(
            snapshot,
            LegacyPliPayloadBuilder.Options(
                callsign = callsign,
                role = role,
                team = teamValue,
                includeActivityMetrics = MainActivity.enablePhysiologicalServices &&
                    (MainActivity.enableActRecService || MainActivity.enableDynamicReportingStrategy),
                buildEsri = enableEsriEndpoint,
                buildTabletCommand = enableTabletCommandEndpoint,
            ),
        )
        compatibilityPayloads.esriGeoJson?.let {
            esriGeojsonMsg = it
            Log.d(TAG, "esriGeojsonMsg: $it")
        }
        compatibilityPayloads.tabletCommandJson?.let { tabletCommandJsonMsg = it }

        val hailDetail = buildHailDetail(snapshot.physioData.data)
        val xml = CotMessageBuilder.pli(
            snapshot,
            CotMessageBuilder.PliOptions(callsign, role, teamValue, enableBatdokCot, hailDetail),
        )
        Log.e("WEARTAK_COT_XML", "XML=$xml")
        return xml
    }

    private var gateCtr = 0
    private fun lgGate(msg: String) = Log.d(TAG, "[gate#${++gateCtr}] $msg")

    private fun collectHailFlows() {
        serviceScope.launch {
            dataRepository.hailLicenseInfoFlow.first()?.let { HailService.hailLicenseInfo = it }
        }

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
        val licenseValid = HailService.isHailLicenseValid()
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
                    CotDetail(HailDetail.HAIL_DETAIL)
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




    private fun escapeXmlText(s: String): String {
        val out = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun finalMarkerTitle(markerTitle: String?): String {
        val calendar: Calendar = Calendar.getInstance()
        calendar.setTime(Date())
        val markerCallsignTimeFormatter = SimpleDateFormat("HHmmss'Z'")
        markerCallsignTimeFormatter.timeZone = TimeZone.getTimeZone("UTC")
        val markerCallsignAppendTime = markerCallsignTimeFormatter.format(calendar.time)
        return if (markerTitle.isNullOrEmpty()) {
            "${callsign}_$markerCallsignAppendTime"
        } else {
            escapeXmlText(markerTitle)
        }
    }

    private fun makeMarkerSnapshotStale(snapshot: DataRepository.MarkerSnapshot): DataRepository.MarkerSnapshot {
        // TAK clears a marker when stale precedes start.
        val clearTime = CotEventTimeFactory.now(
            startOffset = CotTimeOffset(seconds = -4),
            staleOffset = CotTimeOffset(minutes = -3)
        )

        val clearedSnapshot = snapshot.copy(
            time = clearTime
        )

        return clearedSnapshot
    }

    private suspend fun companionAppAvailable(): Boolean {
        val enabled = dataRepository.getEnableCompanionAppService.first()
        val ready = capRepo.companionAppReady.first()
        return enabled && ready
    }

    private suspend fun sitxAvailable(): Boolean {
        val enabled = dataRepository.getEnableSitxService.first()
        val connected = dataRepository.sitxStatus.value == SitxServerStatus.CONNECTED
        return enabled && connected
    }

    private suspend fun dispatch(message: CotOutboundMessage): Boolean =
        endpointDispatcher.dispatch(
            message,
            CotEndpointAvailability(
                tak = TAKConnectService.isAnyTakConnected,
                sitx = sitxAvailable(),
                ditto = DittoService.isRunning.get(),
                multicast = dataRepository.multicastPublisherRunning.value,
                companion = companionAppAvailable(),
            ),
        )

    private suspend fun handleClearMarkerUid(uid: String) {
        val markerDrop = markerDropMap[uid]
        if (null == markerDrop) {
            Log.w(TAG, "Cannot obtain marker drop for uid=$uid. Ignoring...")
            return
        }
        val editedMarkerDrop =
            markerDrop.copy(cotType = DataRepository.MarkerCotType.CLEAR, enqueuedToBeSent = true, waitingForUpdatedLocation = false)
        val updatedList = pendingMarkerCotList.map { item ->
            if ((item.cotType != DataRepository.MarkerCotType.CLEAR) && (item.uid == uid) && item.waitingForUpdatedLocation) {
                Log.i(TAG, "Changing Marker Status for [$uid] of cotType ${item.cotType} with waitingForUpdatedLocation set to false")
                item.copy(enqueuedToBeSent = true, waitingForUpdatedLocation = false)
            } else {
                item
            }
        }.toMutableList()
        pendingMarkerCotList.clear()
        pendingMarkerCotList.addAll(updatedList)
        enqueueMarkerClear(editedMarkerDrop)
        markerDropMap[uid] = editedMarkerDrop
        if (bhRepo.removeTarget(uid).isNotEmpty()) {
            requestBloodhoundComplicationUpdate(this)
        }
        dataRepository.changeDroppedMarkerCotTypeStatusByUid(uid = uid, cotType = DataRepository.MarkerCotType.CLEAR, enqueuedToBeSent = true, waitingForUpdatedLocation = false)

        if (dataRepository.anyEndpointConnected.value) {
            sendPendingMarkerCots()
        }
    }

    private suspend fun sendMarkerClearNow(snapshot: DataRepository.MarkerSnapshot): Boolean {
        val staleSnapshot = makeMarkerSnapshotStale(snapshot)
        val clearXml = CotMessageBuilder.marker(
            staleSnapshot,
            finalMarkerTitle(staleSnapshot.title),
            iconSetPath,
        )

        val sent = dispatchMarkerSnapshot(staleSnapshot, clearXml, CotMessageKind.MARKER_CLEAR)
        Log.i(TAG, "sendMarkerClearNow with result $sent")
        return sent
    }

    private suspend fun handleClearMarkerAll() {
        val uids = dataRepository.droppedMarkerList.value.map { it.uid }
        uids.forEach { handleClearMarkerUid(it) }
    }


    private fun sendMulticastDatagram(cotXml: String) {
        // Non-PLI multicast remains license-gated for compatibility.
        if (isLicenseValid()) {
            Log.i(TAG, "Trying to multicast data to the wifi network! ")
            NetworkUtils.MulticastPublisher.send(cotXml)
        }
    }

    private suspend fun sendEmergencyAlert(alertWithSmsSentState: CustomEmergencyAlertWithAddedState): Boolean {
        val alert = alertWithSmsSentState.alert
        Log.i(
            TAG,
            "sendEmergencyAlert start uid=${alert.uuid} state=${alert.alertState} category=${alert.category} " +
                "priority=${alert.priority} tak=${TAKConnectService.isAnyTakConnected} " +
                "ditto=${DittoService.isRunning.get()} multicast=${dataRepository.multicastPublisherRunning.value} " +
                "sitx=${sitxAvailable()} companion=${companionAppAvailable()}",
        )
        if (MainActivity.enableSmsForAlerts && alert.sendSms && !alertWithSmsSentState.smsSent) {
            SMSManager.sendSMSMessage(numbers = smsNumbersList, description = "${alert.category}: ${alert.description}")
            alertWithSmsSentState.smsSent = true
        }

        val snapshot = dataRepository.snapshotEmergencyEvent(alert)
        val xml = CotMessageBuilder.emergency(snapshot)

        val sent = dispatch(
            CotOutboundMessage(xml, CotMessageKind.EMERGENCY, CotCompanionPayload.Emergency(snapshot)),
        )

        if (sent) {
            Log.i(
                TAG,
                "sendEmergencyAlert complete uid=${alert.uuid} sent=true tak=${TAKConnectService.isAnyTakConnected} " +
                    "ditto=${DittoService.isRunning.get()} multicast=${dataRepository.multicastPublisherRunning.value} " +
                    "sitx=${sitxAvailable()} companion=${companionAppAvailable()}",
            )
        } else {
            Log.i(
                TAG,
                "sendEmergencyAlert complete uid=${alert.uuid} sent=false reason=no_route_available " +
                    "tak=${TAKConnectService.isAnyTakConnected} ditto=${DittoService.isRunning.get()} " +
                    "multicast=${dataRepository.multicastPublisherRunning.value} sitx=${sitxAvailable()} " +
                    "companion=${companionAppAvailable()}",
            )
        }

        return sent
    }

    fun buildAndSendPhysioLocationCotMsg() {
        // Preserve synchronous dispatch for scheduler and location-triggered calls.
        runBlocking(Dispatchers.IO) {
            val snapshot = dataRepository.snapshotPli()
            val loc = snapshot.loc

            val xml = buildPliCot(snapshot)

            Log.i(
                TAG,
                "Outbound PLI built: uid=${snapshot.systemData.deviceId} " +
                    "lat=${loc?.lat ?: 0.0} lon=${loc?.lon ?: 0.0} " +
                    "hae=${loc?.hae ?: 0.0} takConnected=${TAKConnectService.isAnyTakConnected} " +
                    "sitxAvailable=${sitxAvailable()} companionAvailable=${companionAppAvailable()}"
            )

            dispatch(CotOutboundMessage(xml, CotMessageKind.PLI, CotCompanionPayload.Pli(snapshot)))
        }
    }


    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onLocationRcvdEvent(event: LocationRcvd) {
        Log.i(TAG, "onLocationRcvdEvent: received location trigger for outbound PLI")
        lastLocationUpdateRcvd = System.currentTimeMillis()
        if (readyToSendAlertEvents()) {
            if (pendingManualAlertQueue.isNotEmpty()) {
                serviceScope.launch {
                    sendAllManualAlerts(null)
                }
            }
            if (pendingPhysioAndAutoGenAlertQueue.isNotEmpty()) {
                serviceScope.launch {
                    sendAllPhysioAndAutoGenAlerts(null)
                }
            }
        }

        if (pendingMarkerCotList.isNotEmpty()) {
            serviceScope.launch {
                sendPendingMarkerCots()
            }
        }

        buildAndSendPhysioLocationCotMsg()
    }

    private fun enqueueMarkerDrop(marker: DataRepository.DroppedMarker) {
        Log.i(TAG, "===> Adding to marker cot list: Marker Drop for uid=${marker.uid}")
        pendingMarkerCotList.add(marker.copy(cotType = DataRepository.MarkerCotType.DROP))
    }

    private fun enqueueMarkerEdit(req: DataRepository.DroppedMarker) {
        Log.i(TAG, "===> Adding to marker cot list: Marker Edit for uid=${req.uid}")
        pendingMarkerCotList.add(req)
    }

    private fun enqueueMarkerClear(req: DataRepository.DroppedMarker) {
        Log.i(TAG, "===> Adding to marker cot list: Clear Marker for uid=${req.uid}")
        pendingMarkerCotList.add(req)
    }

    private suspend fun sendPendingMarkerCots(): Boolean {
        var sentAllReadyToSend = true
        val queueSize = pendingMarkerCotList.size
        Log.i(TAG, "pendingMarkerDropQueue.size=$queueSize")
        val newPendingMarkerDropList = mutableListOf<DataRepository.DroppedMarker>()
        val isLocationFresh = isLocationFresh()
        for ((i, item) in pendingMarkerCotList.withIndex()) {
            val cotType = item.cotType
            var marker = item
            Log.i(TAG, "sendPendingMarkerCots: Looking at element $i ($cotType): time ${marker.timeDropped}, uid=${marker.uid} type=${marker.markerType}, title=${marker.title}, remark=${marker.remark}, loc=(${marker.location?.lat ?: "null"}, ${marker.location?.lon ?: "null"})")

            if (marker.waitingForUpdatedLocation) {
                if (isLocationFresh) {
                    Log.i(TAG, "sendPendingMarkerCots: waitingForUpdatedLocation is true but location is fresh. Updating...")
                    marker = marker.copy(location = dataRepository.snapshotLocation(), waitingForUpdatedLocation = false)
                    dataRepository.changeDroppedMarkerLocationByUid(marker.uid, marker.location)
                } else {
                    Log.i(TAG, "sendPendingMarkerCots: waitingForUpdatedLocation is true and location is stale. Skipping...")
                    newPendingMarkerDropList.add(item)
                }
            }
            // The location refresh above may have made this marker sendable.
            if (!marker.waitingForUpdatedLocation) {
                if (cotType == DataRepository.MarkerCotType.CLEAR) {
                    if (sendMarkerClearNow(dataRepository.snapshotMarker(marker))) {
                        dataRepository.removeMarkerByUid(marker.uid)
                        markerDropMap.remove(marker.uid)
                    } else {
                        markerDropMap[marker.uid] = marker.copy(enqueuedToBeSent = true)
                        dataRepository.changeDroppedMarkerCotTypeStatusByUid(marker.uid, cotType = DataRepository.MarkerCotType.CLEAR,
                            enqueuedToBeSent = true, waitingForUpdatedLocation = false)
                        sentAllReadyToSend = false
                        newPendingMarkerDropList.add(item)
                    }
                } else {
                    if (sendMarkerDropNow(marker)) {
                        Log.i(TAG, "sendPendingMarkerCots: sendMarkerDropNow returned true. Removing....")
                        markerDropMap[marker.uid] = marker.copy(enqueuedToBeSent = false)
                        dataRepository.changeDroppedMarkerCotTypeStatusByUid(marker.uid,
                            cotType = marker.cotType,
                            enqueuedToBeSent = false,
                            waitingForUpdatedLocation = false
                        )
                    } else {
                        Log.i(TAG, "sendPendingMarkerCots: sendMarkerDropNow returned false. Skipping....")
                        markerDropMap[marker.uid] = marker.copy(enqueuedToBeSent = true)
                        dataRepository.changeDroppedMarkerCotTypeStatusByUid(marker.uid,
                            cotType = marker.cotType,
                            enqueuedToBeSent = true,
                            waitingForUpdatedLocation = false
                        )
                        sentAllReadyToSend = false
                        newPendingMarkerDropList.add(item)
                    }
                }
            }
        }
        pendingMarkerCotList.clear()
        pendingMarkerCotList.addAll(newPendingMarkerDropList)
        return sentAllReadyToSend
    }

    private suspend fun dispatchMarkerSnapshot(
        snapshot: DataRepository.MarkerSnapshot?,
        xml: String,
        kind: CotMessageKind = CotMessageKind.MARKER,
    ): Boolean {
        if (snapshot == null) return false
        val sent = dispatch(
            CotOutboundMessage(xml, kind, CotCompanionPayload.Marker(snapshot)),
        )
        Log.i(TAG, "dispatchMarkerSnapshot: sent=$sent")
        return sent
    }

    private suspend fun sendMarkerDropNow(marker: DataRepository.DroppedMarker): Boolean {
        val editedMarker = if (marker.location == null) {
            Log.w(TAG, "sendMarkerDropNow called with null location; Setting to current location or lat=0, lon=0")
            val locationSnapshot = if (null != dataRepository.latestLocation.value) {
                dataRepository.snapshotLocation()
            } else {
                DataRepository.LocationSnapshot(timeMillis = System.currentTimeMillis(),
                    lat = 0.0, lon = 0.0, hae = 0.0, ce = 0.0f, le = 0.0f, course = 0.0f, speed = 0.0f)
            }
            marker.copy(location = locationSnapshot)
        } else {
            marker
        }
        Log.i(TAG, "sendMarkerDropNow::markerType:: = [ ${marker.markerType.type} ]")
        val snapshot = dataRepository.snapshotMarker(marker)
        val xml = CotMessageBuilder.marker(snapshot, finalMarkerTitle(snapshot.title), iconSetPath)
        return dispatchMarkerSnapshot(snapshot, xml)
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onMessageEvent(event: MessageEvent) {
        val msg = event.message
        if (CALLSIGN_TEAM_ROLE_CHANGED == msg) {
            Log.d(TAG, "onMessageEvent w/ msg($msg)")
            buildAndSendPhysioLocationCotMsg()
        } else if (CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED == msg) {
            Log.d(TAG, "onMessageEvent w/ msg($msg)")
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
        // Alerts and markers reuse a fix only within this window.
        private const val FRESH_GPS_INTERVAL_MILLIS = 3_000L
        private const val COT_DISPATCH_SERVICE_NOTIFICATION_ID = 100
        var GPS_STALE_TIME_INTERVAL_MS = 10_000L
        var OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT = 30
        private const val GPS_STALE_TIME_INTERVAL_MS_JSON_KEY = "gpsStaleTimeInterval"
        private const val OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT_JSON_KEY = "overrideGpsWithBleExitHysteresisCount"
        val isRunning : AtomicBoolean = AtomicBoolean(false)

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
