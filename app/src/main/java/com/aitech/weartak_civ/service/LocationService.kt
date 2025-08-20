/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Notification.PRIORITY_MAX
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils.runTimerJob
import com.aitech.weartak_civ.Utils.startGlobalTimer
import com.aitech.weartak_civ.Utils.writeToLogFileLocation
import com.aitech.weartak_civ.events.LocationRcvd
import com.aitech.weartak_civ.events.LocationUpdateRequired
import com.aitech.weartak_civ.events.PowerManagerEvent
import com.aitech.weartak_civ.events.PowerManagerEvent.Companion.CHANGED_UPDATE_INTERVAL
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.logFile
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min


class LocationService : Service() {

    private var locationThread: HandlerThread? = null
    private var periodicLocationCallback: PeriodicLocationCallback = PeriodicLocationCallback()
    private var fusedLocationProviderClient: FusedLocationProviderClient? = null
    private val binder = LocationServiceBinder()
    private var handler: Handler? = null

    private lateinit var usingPeriodicGpsCallback : AtomicBoolean
    private lateinit var mPowerManager: PowerManager
    private var mWakeLock: PowerManager.WakeLock? = null
    private var oneShotPrevLocationUpdateTime: Long = 0L // TODO: Only for testing/logging, should be removed in the future

    private val oneShotLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return

            CoroutineScope(Dispatchers.IO).launch {
                val currTime = System.currentTimeMillis()
                dataRepository.storeLatestLocation(location)
                val logStr = "oneShotLocationCallback onLocationResult (prev: ${(currTime-oneShotPrevLocationUpdateTime)}): $location"
                Log.d(TAG, logStr)
                writeToLogFileLocation(logFile, logStr)
                oneShotPrevLocationUpdateTime = currTime
                EventBus.getDefault().post(LocationRcvd())
            }
            fusedLocationProviderClient?.removeLocationUpdates(this)
            if (mWakeLock?.isHeld() ?: false) {
                Log.d(TAG, "oneShotLocationCallback onLocationResult: releasing wakelock")
                mWakeLock?.release()
            }
            mWakeLock = null
        }
    }

    private val oneShotLocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0).apply {
        setMaxUpdates(1)
        setMinUpdateIntervalMillis(0)
        setMaxUpdateAgeMillis(0) // force fresh GPS
        setIntervalMillis(1_000)
        setMaxUpdateDelayMillis(1_000)
        setWaitForAccurateLocation(true)
    }.build()

    override fun onCreate() {
        Log.d(TAG, "LocationService onCreate()")
        _instance = this
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if(!isRunning.getAndSet(true)) {
            startForegroundService() //Start Foreground Service
            initializeLocation()
        }
        return START_NOT_STICKY
    }

    private fun initializeLocation() {
        mPowerManager = applicationContext.getSystemService(POWER_SERVICE) as PowerManager
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        if ((ActivityCompat.checkSelfPermission(mainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) &&
            (ActivityCompat.checkSelfPermission(mainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            Log.e(TAG, "LocationService does not have one of ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION permission. Nothing to do. Returning...")
            return
        }

        if (CotDispatchService.currReportingIntervalInSecs < MAX_PERIODIC_GPS_UPDATE_INTERVAL) {
            usingPeriodicGpsCallback = AtomicBoolean(true)
            Log.d(TAG, "initializeLocation: GPS updates are periodic (${CotDispatchService.currReportingIntervalInSecs} s) using Location request/callback")
            //Initialize a new handler thread to start the location updates
            startPeriodicLocationUpdates(Priority.PRIORITY_HIGH_ACCURACY, CotDispatchService.currReportingIntervalInSecs * 1000L, CotDispatchService.currReportingIntervalInSecs * 1000L)
        } else {
            usingPeriodicGpsCallback = AtomicBoolean(false)
            Log.d(TAG, "initializeLocation: GPS updates are periodic (${CotDispatchService.currReportingIntervalInSecs} s) via alarm")
            fusedLocationProviderClient?.requestLocationUpdates(oneShotLocationRequest, oneShotLocationCallback, Looper.getMainLooper())
            runTimerJob(CotDispatchService.currReportingIntervalInSecs * 1000L)
        }
    }

    @SuppressLint("MissingPermission") // We have already checked this
    fun acquireWakelockAndRequestLocation() {
        // Start wake lock to wakeup the device
        if (mWakeLock?.isHeld() ?: false) {
            Log.d(TAG, "acquireWakelockAndRequestLocation: mWakelock is still held")
            mWakeLock?.release()
        }
        mWakeLock = null

        if (usingPeriodicGpsCallback.get()) return

        //mWakeLock = mPowerManager?.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.PARTIAL_WAKE_LOCK, "id:wakeupscreen")
        mWakeLock = mPowerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "id:gps")
        mWakeLock?.acquire(6_000) // We wait at least 6 seconds for indoor GPS

        Log.d(TAG, "Acquired wakelock and now requesting location")
        fusedLocationProviderClient?.requestLocationUpdates(oneShotLocationRequest, oneShotLocationCallback, Looper.getMainLooper())
        startGlobalTimer(CotDispatchService.currReportingIntervalInSecs * 1000L)
    }

    private fun startForegroundService() {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            "Location Service"
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
                .setSmallIcon(R.drawable.splash_icon)
                .setContentText("WearTAK-CIV location service running")
                .setPriority(PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        try {
            startForeground(LOCATION_SERVICE_NOTIFICATION_ID, notification)
        } catch (e: NullPointerException) {
            Log.e(TAG, "Failed to set location ForegroundService", e )
        }
    }

    private fun createNotificationChannel(): String {
        val channelId = "Location_Service"
        val channelName = "Location Foreground Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.GRAY
        chan.importance = NotificationManager.IMPORTANCE_HIGH
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    private fun startPeriodicLocationUpdates(priority: Int, interval: Long, fastestInterval: Long) {
        locationThread = HandlerThread("Location Thread")
        try {
            locationThread?.start()
            //handler = Handler(locationThread?.looper)
            handler = locationThread?.looper?.let { Handler(it) }
            if ((handler != null) && (handler?.looper?.thread?.isAlive ?: false)) {
                handler?.post { this.createPeriodicLocationRequest(priority, interval, fastestInterval)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Not able to Start the Location Update thread ", e)
        }

    }

    private fun createPeriodicLocationRequest(priority: Int, interval: Long, fastestInterval: Long) {
        val locationRequest = LocationRequest.create()
        locationRequest.interval = interval
        locationRequest.fastestInterval = fastestInterval
        //locationRequest.maxWaitTime = 1000L
        //locationRequest.smallestDisplacement = 0.5f
        locationRequest.priority = priority
        /*
        if (getBatteryPercentage(this) < 25.0) {
            locationRequest.priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        } else {
            locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }PRIORITY_HIGH_ACCURACY
         */

        val logStr = "LocationRequest params: priority(${locationRequest.priority}), interval(${locationRequest.intervalMillis}), maxUpdateDelay(${locationRequest.maxUpdateDelayMillis}), " +
                "minUpdateInteval(${locationRequest.minUpdateIntervalMillis}), smallestDisplacement(" + locationRequest.smallestDisplacement + "), expirationTime(" + locationRequest.expirationTime + ")"
        Log.d(TAG, logStr)
        writeToLogFileLocation(logFile, logStr)
        printLocationRequest(locationRequest)
        /*
        val builder = LocationSettingsRequest.Builder()
        builder.addLocationRequest(locationRequest)
        val request = builder.build()
        val settingsClient = LocationServices.getSettingsClient(this)
        settingsClient.checkLocationSettings(request)
        if (applicationContext != null && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(applicationContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION)
        }
         */
        Log.d(TAG, "Fine Location Permission allowed: "+ (PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION)))
        Log.d(TAG, "Coarse Location Permission allowed: "+ (PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION)))
        periodicLocationCallback = PeriodicLocationCallback()
        //val simpleLocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TimeUnit.SECONDS.toMillis(CotDispatchService.currReportingIntervalInSecs.toLong())).build()
        fusedLocationProviderClient?.requestLocationUpdates(locationRequest, periodicLocationCallback, Looper.myLooper())
    }

    private fun printLocationRequest(locReq : LocationRequest) {
        val logStr = "locReq: $locReq, prio=${locReq.priority}, durationMillis=${locReq.durationMillis}, fastestInterval=${locReq.fastestInterval}, " +
                "interval=${locReq.interval}, expirationTime=${locReq.expirationTime}, isPassive=${locReq.isPassive}, isBatched=${locReq.isBatched}, maxUpdates=${locReq.maxUpdates}, " +
                "intervalMillis=${locReq.intervalMillis}, maxUpdateDelayMillis=${locReq.maxUpdateDelayMillis}, minUpdateIntervalMillis=${locReq.minUpdateIntervalMillis}, " +
                "minUpdateDistanceMeters=${locReq.minUpdateDistanceMeters}, granularity=${locReq.granularity}, isFastestIntervalExplicitlySet=${locReq.isFastestIntervalExplicitlySet}, " +
                "isWaitForAccurateLocation=${locReq.isWaitForAccurateLocation}, maxUpdateAgeMillis=${locReq.maxUpdateAgeMillis}, maxWaitTime=${locReq.maxWaitTime}, " +
                "numUpdates=${locReq.numUpdates}, smallestDisplacement=${locReq.smallestDisplacement}"
        Log.d(TAG, logStr)
        writeToLogFileLocation(logFile, logStr)
    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    private inner class PeriodicLocationCallback : LocationCallback() {
        var prevLocationUpdateTime : Long = 0L // TODO: Only for testing/logging, should be removed in the future

        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            CoroutineScope(Dispatchers.IO).launch {
                dataRepository.storeLatestLocation(location)
                //Log.d(TAG, "Received location update: $location")
                //writeToLogFileLocation(logFile, "Received location update: $location")
                val currTime = System.currentTimeMillis()
                val logStr = "periodicLocationCallback: Received location update (prev: ${(currTime-prevLocationUpdateTime)}): $location"
                Log.d(TAG, logStr)
                writeToLogFileLocation(logFile, logStr)
                prevLocationUpdateTime = currTime

                EventBus.getDefault().post(LocationRcvd())
            }
        }
    }

    inner class LocationServiceBinder : Binder() {
        val service: LocationService
            get() = this@LocationService
    }

    private fun restartGpsUpdates() {
        if (usingPeriodicGpsCallback.get()) {
            stopPeriodicLocationUpdates()
            destroyLocationThread()
        } // No need to do anything if !usingPeriodicGpsCallback as we check this variable in acquireWakelockAndRequestLocation

        if (CotDispatchService.currReportingIntervalInSecs < MAX_PERIODIC_GPS_UPDATE_INTERVAL) {
            usingPeriodicGpsCallback.set(true)
            Log.d(TAG, "restartGpsUpdates: GPS updates are periodic (${CotDispatchService.currReportingIntervalInSecs} s) using Location request/callback\")")
            startPeriodicLocationUpdates(Priority.PRIORITY_HIGH_ACCURACY, CotDispatchService.currReportingIntervalInSecs * 1000L, CotDispatchService.currReportingIntervalInSecs * 1000L)
        } else {
            usingPeriodicGpsCallback.set(false)
            Log.d(TAG, "restartGpsUpdates: GPS updates are periodic (${CotDispatchService.currReportingIntervalInSecs} s) via alarm")
            if ((ActivityCompat.checkSelfPermission(mainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) &&
                (ActivityCompat.checkSelfPermission(mainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
                return
            }
            Log.d(TAG, "Received onLocationUpdateRequiredEvent: Requesting one shot location")
            fusedLocationProviderClient?.requestLocationUpdates(oneShotLocationRequest, oneShotLocationCallback, Looper.getMainLooper())
            runTimerJob(CotDispatchService.currReportingIntervalInSecs * 1000L)
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onLocationUpdateRequiredEvent(locationUpdateRequired: LocationUpdateRequired) {
        restartGpsUpdates()
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onPowerManagerEvent(powerManagerEvent: PowerManagerEvent) {
        if (CHANGED_UPDATE_INTERVAL == powerManagerEvent.eventType) {
            // We know that CotDispatchService.currReportingIntervalInSecs has been set (and changed) already
            // Stop and start Location Updates
            Log.d(TAG, "Received PowerManagerEvent CHANGED_UPDATE_INTERVAL. Stopping and starting location updates")
            restartGpsUpdates()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        stopPeriodicLocationUpdates()
        fusedLocationProviderClient = null
        destroyLocationThread()
        stopForeground(true)
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        Log.d(TAG, "Location Service Destroyed")
    }

    private fun destroyLocationThread() {
        if (null != handler) {
            handler?.looper?.quitSafely()
            handler = null
        }
        if (null != locationThread) {
            locationThread?.quitSafely()
            try {
                locationThread?.join()
                locationThread = null
            } catch (e: InterruptedException) {
                Log.e(TAG, "ERROR in destroyLocationThread: ", e)
            }
            locationThread = null
        }
    }

    private fun stopPeriodicLocationUpdates() {
        if (null != periodicLocationCallback) {
            if (null != fusedLocationProviderClient) {
                fusedLocationProviderClient?.removeLocationUpdates(periodicLocationCallback)
            }
        }
    }

    companion object {
        private val TAG = "WearTAK-" + LocationService::class.java.simpleName
        private val LOCATION_SERVICE_NOTIFICATION_ID = 1234
        private val MAX_PERIODIC_GPS_UPDATE_INTERVAL : Int = 10
        // TODO: remove lat, lon, hae, ce, le after removing/refactoring PassiveDataService.kt
        var lat : Double? = null
        var lon : Double? = null
        var hae : Double? = null
        var ce : Float? = null
        var le : Float? = null
        //var locationUpdateIntervalSecs = 2
        //var locationUpdateFastestIntervalSecs = 1
        val isRunning : AtomicBoolean = AtomicBoolean(false)
        @Volatile private var _instance: LocationService? = null
        fun getInstance(): LocationService? = _instance
    }
}
