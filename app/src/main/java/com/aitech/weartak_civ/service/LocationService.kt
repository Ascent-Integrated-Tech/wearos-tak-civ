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
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.service.BatteryService.Companion.batteryPercentage
import com.aitech.weartak_civ.service.TAKConnectService.Companion.sendDataTAKServer
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
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit


class LocationService : Service() {

    private var locationThread: HandlerThread? = null
    private var locationManagerThread: HandlerThread? = null
    private var locationCallback: MyLocationCallback = MyLocationCallback()
    private var fusedLocationProviderClient: FusedLocationProviderClient? = null
    private val binder = LocationServiceBinder()
    private var handler: Handler? = null
    private var locationManagerHandler: Handler? = null
    private var currentLocation: Location? = null
    // TODO: move this to main / use main
    private val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private var heartrate: Int = -1;

    override fun onCreate() {
        Log.d(TAG, "LocationService onCreate()")
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        initializeLocation()
        return START_NOT_STICKY
    }

    private fun initializeLocation() {
        //Start Foreground Service
        startForegroundService()
        //Initialize a new handler thread to start the location updates
        startLocationUpdates()
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
        val channelName = "Location Background Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.GRAY
        chan.importance = NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    private fun startLocationUpdates() {
        locationThread = HandlerThread("Location Thread")
        try {
            locationThread?.start()
            //handler = Handler(locationThread?.looper)
            handler = locationThread?.looper?.let { Handler(it) }
            if (handler!!.looper.thread.isAlive) {
                handler!!.post { this.createLocationRequest() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Not able to Start the Location Update thread ", e)
        }

    }

    private fun createLocationRequest() {
        val locationRequest = LocationRequest.create()
        locationRequest.interval = 2000
        locationRequest.fastestInterval = 2000
        //locationRequest.maxWaitTime = 1000L
        //locationRequest.smallestDisplacement = 0.5f
        locationRequest.priority = Priority.PRIORITY_HIGH_ACCURACY
        /*
        if (getBatteryPercentage(this) < 25.0) {
            locationRequest.priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        } else {
            locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
         */
        Log.d(TAG, "LocationRequest params: fastestInterval(" + locationRequest.fastestInterval + "), interval(" + locationRequest.interval + "), maxWaitTime(" + locationRequest.maxWaitTime +
                "), priority(" + locationRequest.priority + "), smallestDisplacement(" + locationRequest.smallestDisplacement + "), expirationTime(" + locationRequest.expirationTime + ")")
        val builder = LocationSettingsRequest.Builder()
        builder.addLocationRequest(locationRequest)
        val request = builder.build()
        val settingsClient = LocationServices.getSettingsClient(this)
        settingsClient.checkLocationSettings(request)
        if (applicationContext != null && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(applicationContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        Log.d(TAG, "Fine Location Permission allowed: "+ (PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION)))
        Log.d(TAG, "Coarse Location Permission allowed: "+ (PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION)))
        locationCallback = MyLocationCallback()
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        val simpleLocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TimeUnit.SECONDS.toMillis(2)).build()
        fusedLocationProviderClient?.requestLocationUpdates(simpleLocationRequest, locationCallback, Looper.myLooper())

    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    fun sendLocation(
        uid: String,
        callsign: String,
        role: String,
        teamColor: String,
        location: LocationResult
    ) {
        dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        val locDateTime = Date()
        lat = location.lastLocation?.latitude
        lon = location.lastLocation?.longitude
        hae = location.lastLocation?.altitude
        ce = location.lastLocation?.accuracy
        le = location.lastLocation?.verticalAccuracyMeters
        val speed = location.lastLocation?.speed
        val bearing = location.lastLocation?.bearing
        //Log.d(LOG_TAG, "sendLocation called with location ${location.toString()}")
        //Log.d(LOG_TAG, "sendLocation w/ lat: ($lat); lon: ($lon); horizontalAccuracy: ($ce); alt: ($hae); verticalAccuracy: ($le); speed: ($speed); bearing: ($bearing)")
        if ((null != lat) && (null != lon)) {
            // Parse and manipulate timestamps
            dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
            val locTimeStr = dateTimeFormatter.format(locDateTime)

            // Create a Calendar object and set it to the given date
            val calendar: Calendar = Calendar.getInstance()
            calendar.setTime(locDateTime)

            // Add 20 seconds to the calendar
            calendar.add(Calendar.SECOND, 20)

            val locDateTimeFuture = calendar.time
            val locFutureTimeStr = dateTimeFormatter.format(locDateTimeFuture)
            //Log.d(LOG_TAG, "Current location time string: $locTimeStr")

            // Construct CoT message
            val haeActual = hae ?: "0.0"
            val ceActual = ce ?: "9999999.0"
            val leActual = le ?: "9999999.0"
            val speedActual = speed ?: "0.0"
            val bearingActual = bearing ?: "0.0"

            CoroutineScope(Dispatchers.Default).launch {
                dataRepository.latestHeartRate.collect { hr -> heartrate = hr }
            }
            val cotMsg: String = LOC_PART1 + MainActivity.device_uid +
                    LOC_PART2 + locTimeStr + "' start='" + locTimeStr + "' stale='" + locFutureTimeStr +
                    LOC_PART3 + lat + "' lon='" + lon + "' hae='" + java.lang.String.format(
                Locale.ENGLISH,
                "%.2f",
                haeActual
            ) +
                    "' ce='" + java.lang.String.format(
                Locale.ENGLISH,
                "%.2f",
                ceActual
            ) + "' le='" + java.lang.String.format(Locale.ENGLISH, "%.2f", leActual) +
                    LOC_PART4 + callsign + LOC_PART5 + uid + LOC_PART6 + role + "' name='" + teamColor +
                    LOC_PART7 + batteryPercentage + "'/>" + DETAILS_META_KEY_HEART_RATE + heartrate + LOC_PART_8 + bearingActual + "' speed='" + speedActual + LOC_PART_9
            //Log.d(TAG, "Sending location COT message: $cotMsg")
            sendDataTAKServer(cotMsg)
        }
    }

    private inner class MyLocationCallback : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            if (locationResult != null && locationResult.lastLocation != null) {
                val location = locationResult.lastLocation
                currentLocation = location
                //Log.d(TAG, "Received location: " + location.toString())
                CoroutineScope(Dispatchers.IO).launch {
                    sendLocation(
                        MainActivity.device_uid,
                        callsign,
                        "Team Member",
                        "Orange",
                        locationResult
                    )
                }
            }
        }
    }

    private fun startLocationManagerUpdates() {
        locationManagerThread = HandlerThread("LocationManager Thread")
        try {
            locationManagerThread?.start()
            //locationManagerHandler = Handler(locationManagerThread?.looper)
            locationManagerHandler = locationManagerThread?.looper?.let { Handler(it) }
            if (locationManagerHandler!!.looper.thread.isAlive) {
                locationManagerHandler!!.post { this.createLocationManagerRequest() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Not able to Start the LocationManager Update thread ", e)
        }
    }

    private fun createLocationManagerRequest() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        Log.d(TAG, "Location update LocationManager created")
        // Define a listener that responds to location updates
        val locationListener = MyLocationListener()
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locationListener)
        } catch (ex: Exception) {
            Log.e(TAG, "Location update Exception: " + ex.message)
        }
    }

    inner class MyLocationListener: android.location.LocationListener {
        override fun onLocationChanged(location: Location) {
            // Called when a new location is found by the network location provider.
            if (location != null) {
                val latitude = location!!.latitude.toString()
                val longitude = location!!.longitude.toString()
                val timestamp = location!!.time.toString()
                val altitude = location!!.altitude.toString()
                val accuracy = location!!.accuracy.toString()
                var verticalAccuracy = "-1"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracy = location!!.verticalAccuracyMeters.toString()
                }
            }
        }

        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    inner class LocationServiceBinder : Binder() {
        val service: LocationService
            get() = this@LocationService
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationProvider()
        destroyLocationThread()
        stopForeground(true)
        Log.d(TAG, "Location Service Destroyed")
    }

    private fun destroyLocationThread() {
        if (handler != null && locationThread != null) {
            handler?.looper?.quitSafely()
            locationThread?.quitSafely()
            try {
                locationThread?.join()
                locationThread = null
            } catch (e: InterruptedException) {
                Log.e(TAG, "ERROR in destroyLocationThread: ", e)
            }

        }
    }

    private fun stopLocationProvider() {
        if (null != locationCallback) {
            fusedLocationProviderClient?.removeLocationUpdates(locationCallback!!)
        }
    }

    companion object {
        private val TAG = "WearTAK-" + LocationService::class.java.simpleName
        private val LOCATION_SERVICE_NOTIFICATION_ID = 1234
        var lat : Double? = null
        var lon : Double? = null
        var hae : Double? = null
        var ce : Float? = null
        var le : Float? = null
        val LOC_PART1 = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='"
        val LOC_PART2 = "' type='a-f-G-U-C' time='"
        const val LOC_PART3 = "' how='m-g' access='Undefined'><point lat='"
        const val LOC_PART4 = "' /><detail><contact endpoint='*:-1:stcp' phone='11234567890' callsign='"
        const val LOC_PART5 = "'/><uid Droid='"
        const val LOC_PART6 = "'/><__group role='"
        const val LOC_PART7 = "'/><status battery='"
        const val LOC_PART_8 = "'/><track course='"
        const val LOC_PART_9 = "'/></detail></event>"
        const val DETAILS_META_KEY_HEART_RATE: String = "<HEART_RATE_COT_KEY com.aitech.atak.biometrics.heartrate.HeartRate='"

    }
}
