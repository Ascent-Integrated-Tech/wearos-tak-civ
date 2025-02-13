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
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.events.LocationRcvd
import com.aitech.weartak_civ.events.NwConnEvent
import com.aitech.weartak_civ.events.NwConnEvent.Companion.CELLULAR
import com.aitech.weartak_civ.events.NwConnEvent.Companion.WIFI
import com.aitech.weartak_civ.events.PowerManagerEvent
import com.aitech.weartak_civ.events.PowerManagerEvent.Companion.CHANGED_UPDATE_INTERVAL
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.NetworkManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.text.SimpleDateFormat
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


class LocationService : Service() {

    private var locationThread: HandlerThread? = null
    private var locationCallback: MyLocationCallback = MyLocationCallback()
    private var fusedLocationProviderClient: FusedLocationProviderClient? = null
    private val binder = LocationServiceBinder()
    private var handler: Handler? = null
    private var currentLocation: Location? = null

    override fun onCreate() {
        Log.d(TAG, "LocationService onCreate()")
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        initializeLocation()
        isRunning = true
        return START_NOT_STICKY
    }

    private fun initializeLocation() {
        //Start Foreground Service
        startForegroundService()
        //Initialize a new handler thread to start the location updates
        startLocationUpdates(Priority.PRIORITY_HIGH_ACCURACY, CotDispatchService.currReportingIntervalInSecs * 1000L, CotDispatchService.currReportingIntervalInSecs * 1000L)
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
                .setPriority(Context.BIND_WAIVE_PRIORITY)
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

    private fun startLocationUpdates(priority: Int, interval: Long, fastestInterval: Long) {
        locationThread = HandlerThread("Location Thread")
        try {
            locationThread?.start()
            //handler = Handler(locationThread?.looper)
            handler = locationThread?.looper?.let { Handler(it) }
            if ((handler != null) && (handler?.looper?.thread?.isAlive ?: false)) {
                handler?.post { this.createLocationRequest(priority, interval, fastestInterval)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Not able to Start the Location Update thread ", e)
        }

    }

    private fun createLocationRequest(priority: Int, interval: Long, fastestInterval: Long) {
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

        Log.d(TAG, "LocationRequest params: priority(${locationRequest.priority}), interval(${locationRequest.intervalMillis}), maxUpdateDelay(${locationRequest.maxUpdateDelayMillis}), " +
                "minUpdateInteval(${locationRequest.minUpdateIntervalMillis}), smallestDisplacement(" + locationRequest.smallestDisplacement + "), expirationTime(" + locationRequest.expirationTime + ")")
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
        locationCallback = MyLocationCallback()
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        //val simpleLocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TimeUnit.SECONDS.toMillis(CotDispatchService.currReportingIntervalInSecs.toLong())).build()
        fusedLocationProviderClient?.requestLocationUpdates(locationRequest, locationCallback, Looper.myLooper())
    }

    private fun printLocationRequest(locReq : LocationRequest) {
        Log.d(TAG, "locReq: $locReq, ${locReq.priority}, ${locReq.durationMillis}, ${locReq.maxUpdates}, ${locReq.intervalMillis}, ${locReq.maxUpdateDelayMillis}, ${locReq.minUpdateIntervalMillis}, ${locReq.minUpdateDistanceMeters}")
    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    private inner class MyLocationCallback : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            CoroutineScope(Dispatchers.IO).launch {
                dataRepository.storeLatestLocation(location)    // TODO: Also check if we need to run storeLatestLocation within a coroutine (lines 263-264) - I don't think we need to - this will save us some context switching cpu cycles.
                //Log.d(TAG, "Received location update: $location")
                EventBus.getDefault().post(LocationRcvd())
            }
        }
    }


    inner class LocationServiceBinder : Binder() {
        val service: LocationService
            get() = this@LocationService
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onPowerManagerEvent(powerManagerEvent: PowerManagerEvent) {
        //Log.d(TAG, "Received PowerManagerEvent")
        if (CHANGED_UPDATE_INTERVAL == powerManagerEvent.eventType) {
            // We know that CotDispatchService.currReportingIntervalInSecs has been set already
            // Stop and start Location Updates
            Log.d(TAG, "Received PowerManagerEvent CHANGED_UPDATE_INTERVAL. Stopping and starting location updates")
            stopLocationProvider()
            destroyLocationThread()
            startLocationUpdates(Priority.PRIORITY_HIGH_ACCURACY, CotDispatchService.currReportingIntervalInSecs * 1000L, CotDispatchService.currReportingIntervalInSecs * 1000L)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopLocationProvider()
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

    private fun stopLocationProvider() {
        if (null != locationCallback) {
            if (null != fusedLocationProviderClient) {
                fusedLocationProviderClient?.removeLocationUpdates(locationCallback)
            }
        }
        fusedLocationProviderClient = null
    }

    companion object {
        private val TAG = "WearTAK-" + LocationService::class.java.simpleName
        private val LOCATION_SERVICE_NOTIFICATION_ID = 1234
        // TODO: remove lat, lon, hae, ce, le after removing/refactoring PassiveDataService.kt
        var lat : Double? = null
        var lon : Double? = null
        var hae : Double? = null
        var ce : Float? = null
        var le : Float? = null
        //var locationUpdateIntervalSecs = 2
        //var locationUpdateFastestIntervalSecs = 1
        var isRunning : Boolean = false
    }
}