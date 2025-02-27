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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.UPDATE_INTERVAL_CHANGED
import com.aitech.weartak_civ.events.NwConnEvent
import com.aitech.weartak_civ.events.NwConnEvent.Companion.CELLULAR
import com.aitech.weartak_civ.events.NwConnEvent.Companion.WIFI
import com.aitech.weartak_civ.events.PowerManagerEvent
import com.aitech.weartak_civ.events.PowerManagerEvent.Companion.CHANGED_UPDATE_INTERVAL
import com.aitech.weartak_civ.events.WifiSsidRcvd
import com.aitech.weartak_civ.presentation.NetworkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.atomic.AtomicBoolean

// This service acts as the orchestrator for all power optimizations
class PowerManagerService : Service() {
    private var powerManagerHandler: Handler? = null
    private var powerManagerThread: HandlerThread? = null

    override fun onCreate() {
        super.onCreate()
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "PowerManager onStartCommand called w/ isRunning=$isRunning")
        if (!isRunning.getAndSet(true)) {
            startForegroundService()
            startPowerManagerThread()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning.getAndSet(false)) {
            stopForeground(true)
        }
        stopPowerManagerThread()
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        Log.d(TAG, "PowerManager Service destroyed")
    }

    private fun startForegroundService() {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            "PowerManager Service"
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentText("WearTAK-CIV power manager service running")
            .setPriority(Context.BIND_WAIVE_PRIORITY)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        try {
            startForeground(POWERMANAGER_SERVICE_NOTIFICATION_ID, notification)
        } catch (e: NullPointerException) {
            Log.e(TAG, "Failed to set PowerManager ForegroundService", e )
        }
    }

    private fun createNotificationChannel(): String {
        val channelId = "PowerManager_Service"
        val channelName = "PowerManager Foreground Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.GRAY
        chan.importance = NotificationManager.IMPORTANCE_HIGH
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    private fun stopPowerManagerThread() {
        Log.v(TAG, "Stopping the power manager thread")
        powerManagerHandler?.removeCallbacks(initialPowerManagerStartRunnable)
        powerManagerThread?.quitSafely()
        powerManagerThread = null
        powerManagerHandler = null
    }

    private fun startPowerManagerThread() {
        Log.v(TAG, "Starting the power manager thread")
        powerManagerThread = HandlerThread("PowerManagerThread")
        powerManagerThread?.start()
        powerManagerHandler = powerManagerThread?.looper?.let { Handler(it) }

        // Schedule the first run
        powerManagerHandler?.postDelayed(initialPowerManagerStartRunnable, INITIAL_START_DELAY)
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onMessageEvent(messageEvent: MessageEvent) {
        val msg = messageEvent.message
        if (UPDATE_INTERVAL_CHANGED == msg) {
            val updateInterval = messageEvent.addlIntInfo
            Log.d(TAG, "Received MessageEvent UPDATE_INTERVAL_CHANGED w/ update interval ($updateInterval)")
            if (initialStartDelayDone.get()) {
                runBlocking {
                    dynamicUpdates(NetworkManager.isNetworkConnected, isWifiToInt(NetworkManager.isWifi), NetworkManager.connectedWifiSsid,
                        dataRepository.getEnableWifiBatteryOptimization.first(), dataRepository.getEnableAllWifiBatteryOptimization.first(), dataRepository.getWifiSsidList.first())
                }
            }
        } else if (OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED == msg) {
            Log.d(TAG, "Received MessageEvent OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED")
            if (initialStartDelayDone.get()) {
                runBlocking {
                    dynamicUpdates(NetworkManager.isNetworkConnected, isWifiToInt(NetworkManager.isWifi), NetworkManager.connectedWifiSsid,
                        dataRepository.getEnableWifiBatteryOptimization.first(), dataRepository.getEnableAllWifiBatteryOptimization.first(), dataRepository.getWifiSsidList.first())
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onWifiSsidRcvd(wifiSsidRcvd: WifiSsidRcvd) {
        val ssid = wifiSsidRcvd.ssid
        Log.d(TAG, "Received wifiSsidRcvd w/ $ssid")
        if (initialStartDelayDone.get()) {
            runBlocking {
                dynamicUpdates(NetworkManager.isNetworkConnected, isWifiToInt(NetworkManager.isWifi), ssid,
                    dataRepository.getEnableWifiBatteryOptimization.first(), dataRepository.getEnableAllWifiBatteryOptimization.first(), dataRepository.getWifiSsidList.first())
            }
        }
    }

    // This method will be called when a NwConnEvent is posted from NetworkManager
    // The NetworkManager guarantees that a NwConnEvent will be sent only on some change in
    // network (either nw becomes disconnected or again connected OR if connectivity changes from Cellular to Wifi).
    // Hence there is no need to keep nw connected state.
    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onNwConnEvent(nwConnEvent: NwConnEvent) {
        Log.d(TAG, "Received nwConnEvent (${nwConnEvent.isConn}, ${nwConnEvent.transport})")
        if (initialStartDelayDone.get()) {
            runBlocking {
                dynamicUpdates(nwConnEvent.isConn, nwConnEvent.transport, NetworkManager.connectedWifiSsid,
                    dataRepository.getEnableWifiBatteryOptimization.first(), dataRepository.getEnableAllWifiBatteryOptimization.first(), dataRepository.getWifiSsidList.first())
            }
        }
    }

    private fun slowDownCurrReportingInterval(reportingUpdateIntervalSecs: Int) {
        // TODO: Can add better logic below
        if (reportingUpdateIntervalSecs <= 10) {
            CotDispatchService.currReportingIntervalInSecs = CotDispatchService.reportingUpdateIntervalSecs * 6
        } else if (reportingUpdateIntervalSecs > 10 && reportingUpdateIntervalSecs <= 60) {
            // The 60 below is the max time from previous if statement. So 10 * 6.
            // The 10 below is the max reportingUpdateIntervalSecs from previous if statement
            // This guarantees that reportingUpdateIntervalSecs for 11s is greater than that for 10s but now the increase is slower
            CotDispatchService.currReportingIntervalInSecs = 60 + (CotDispatchService.reportingUpdateIntervalSecs - 10) * 4
        } else {
            // The 260 below is the max time from previous else if statement. So 60 + (60 - 10) * 4 = 260
            // The 60 below is the max reportingUpdateIntervalSecs from previous else if statement
            // This guarantees that reportingUpdateIntervalSecs for 61s is greater than that for 60s but now the increase is a constant of 200s more
            CotDispatchService.currReportingIntervalInSecs = 260 + (CotDispatchService.reportingUpdateIntervalSecs - 60)
        }
    }

    // onStart: Location and Heartrate are started with reportingUpdateIntervalSecs, i.e., at fastest rate,
    // and this corresponds to cellular. So if onStart == true and on cellular, DO NOTHING.
    private fun dynamicUpdates(nwConnected: Boolean, nwTransport: Int, connWifiSsid : String?,
                               enableWifiPowerOptim: Boolean, enablePowerOptimOnAllWifiSsids: Boolean,
                               powerOptimWifiSsidList: List<DataRepository.WifiSsidItem>) {
        Log.d(TAG, "dynamicUpdates called w/ $nwConnected, $nwTransport, $connWifiSsid, $enableWifiPowerOptim, $enablePowerOptimOnAllWifiSsids, $powerOptimWifiSsidList")
        if (nwConnected) {
            if (nwTransport == WIFI) {
                if (enableWifiPowerOptim) {
                    if (enablePowerOptimOnAllWifiSsids) {
                        // Slow down the GPS update as we assume that the device is NOT in critical area
                        // TODO: This will have to change this below when we add custom geofence
                        slowDownCurrReportingInterval(CotDispatchService.reportingUpdateIntervalSecs)
                        Log.d(TAG, "WIFI w/ enableWifiPowerOptim true, enablePowerOptimOnAllWifiSsids true: Setting currReportingIntervalInSecs to ${CotDispatchService.currReportingIntervalInSecs}")
                        // Send eventbus message so that LocationService can soft restart
                        EventBus.getDefault().post(PowerManagerEvent(CHANGED_UPDATE_INTERVAL))
                    } else {
                        // Check SSID and slow down if connWifiSsid is in powerOptimWifiSsidList
                        if (powerOptimWifiSsidList.any { it.name == connWifiSsid }) {
                            // connWifiSsid is in powerOptimWifiSsidList
                            slowDownCurrReportingInterval(CotDispatchService.reportingUpdateIntervalSecs)
                            Log.d(TAG, "WIFI w/ enableWifiPowerOptim true, enablePowerOptimOnAllWifiSsids false (connWifiSsid($connWifiSsid) in $powerOptimWifiSsidList): Setting currReportingIntervalInSecs to ${CotDispatchService.currReportingIntervalInSecs}")
                            // Send eventbus message so that LocationService can soft restart
                            EventBus.getDefault().post(PowerManagerEvent(CHANGED_UPDATE_INTERVAL))
                        } else {
                            // Fast updates
                            if (CotDispatchService.currReportingIntervalInSecs != CotDispatchService.reportingUpdateIntervalSecs) {
                                CotDispatchService.currReportingIntervalInSecs = CotDispatchService.reportingUpdateIntervalSecs
                                Log.d(TAG, "WIFI w/ enableWifiPowerOptim true, enablePowerOptimOnAllWifiSsids false (connWifiSsid($connWifiSsid) NOT in $powerOptimWifiSsidList): Setting currReportingIntervalInSecs to ${CotDispatchService.currReportingIntervalInSecs}")
                                // Send eventbus message so that LocationService can soft restart
                                EventBus.getDefault().post(PowerManagerEvent(CHANGED_UPDATE_INTERVAL))
                            }
                        }
                    }
                } else {
                    // Fast updates
                    if (CotDispatchService.currReportingIntervalInSecs != CotDispatchService.reportingUpdateIntervalSecs) {
                        CotDispatchService.currReportingIntervalInSecs = CotDispatchService.reportingUpdateIntervalSecs
                        Log.d(TAG, "WIFI w/ enableWifiPowerOptim false: Setting currReportingIntervalInSecs to ${CotDispatchService.currReportingIntervalInSecs}")
                        // Send eventbus message so that LocationService can soft restart
                        EventBus.getDefault().post(PowerManagerEvent(CHANGED_UPDATE_INTERVAL))
                    }
                }
            } else { // It will be CELLULAR
                // Fast GPS update
                if (CotDispatchService.currReportingIntervalInSecs != CotDispatchService.reportingUpdateIntervalSecs) {
                    CotDispatchService.currReportingIntervalInSecs = CotDispatchService.reportingUpdateIntervalSecs
                    Log.d(TAG, "CELLULAR: Setting currReportingIntervalInSecs to ${CotDispatchService.currReportingIntervalInSecs}")
                    // Send eventbus message so that LocationService can soft restart
                    EventBus.getDefault().post(PowerManagerEvent(CHANGED_UPDATE_INTERVAL))
                }
            }
        } else {
            // Not connected. Slow down the GPS update
            slowDownCurrReportingInterval(CotDispatchService.reportingUpdateIntervalSecs)
            Log.d(TAG, "Disconnected: Setting currReportingIntervalInSecs to ${CotDispatchService.currReportingIntervalInSecs}")
            // Send eventbus message so that LocationService can soft restart
            EventBus.getDefault().post(PowerManagerEvent(CHANGED_UPDATE_INTERVAL))
        }
    }

    private fun isWifiToInt(isWifi: Boolean) : Int {
        return if (isWifi) { WIFI } else { CELLULAR }
    }

    private val initialPowerManagerStartRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "Starting PowerManager Functionality")
            runBlocking {
                dynamicUpdates(NetworkManager.isNetworkConnected, isWifiToInt(NetworkManager.isWifi), NetworkManager.connectedWifiSsid,
                    dataRepository.getEnableWifiBatteryOptimization.first(), dataRepository.getEnableAllWifiBatteryOptimization.first(), dataRepository.getWifiSsidList.first())
                initialStartDelayDone.set(true)
            }
        }
    }

    companion object {
        private val TAG = "WearTAK-" + PowerManagerService::class.java.simpleName
        private val POWERMANAGER_SERVICE_NOTIFICATION_ID = 555
        private const val INITIAL_START_DELAY = 5_000L // 5 seconds
        val isRunning = AtomicBoolean(false)
        // We provide a initial delay of INITIAL_START_DELAY so that other services can come up
        // and we are not concerned with the order of service start. So till this flag is set to
        // true, we will ignore NwConnEvents in the function onNwConnEvent
        var initialStartDelayDone = AtomicBoolean(false)
        var isWifiOpt : Boolean = true
        var isAllWifiOpt : Boolean = true

    }
}