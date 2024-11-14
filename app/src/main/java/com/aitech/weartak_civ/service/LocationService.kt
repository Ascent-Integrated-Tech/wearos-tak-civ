
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
import android.os.*
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.lang.NullPointerException
import java.util.*
import com.aitech.weartak_civ.R


class LocationService : Service() {

    private var locationThread: HandlerThread? = null
    private var locationManagerThread: HandlerThread? = null
    private var locationCallback: MyLocationCallback = MyLocationCallback()
    private var fusedLocationProviderClient: FusedLocationProviderClient? = null
    private val binder = LocationServiceBinder()
    private var handler: Handler? = null
    private var locationManagerHandler: Handler? = null
    private var currentLocation: Location? = null


    override fun onCreate() {
        Log.d(LOG_TAG, "LocationService onCreate()")

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
            Log.e(LOG_TAG, "Failed to set location ForegroundService", e )
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
            Log.e(LOG_TAG, "Not able to Start the Location Update thread ", e)
        }

    }

    private fun createLocationRequest() {
        val locationRequest = LocationRequest.create()
        locationRequest.interval = 1000
        locationRequest.fastestInterval = 500
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
        Log.d(LOG_TAG, "LocationRequest params: fastestInterval(" + locationRequest.fastestInterval + "), interval(" + locationRequest.interval + "), maxWaitTime(" + locationRequest.maxWaitTime +
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
        Log.d(LOG_TAG, "Fine Location Permission allowed: "+ (PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION)))
        Log.d(LOG_TAG, "Coarse Location Permission allowed: "+ (PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION)))
        locationCallback = MyLocationCallback()
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationProviderClient?.requestLocationUpdates(locationRequest, locationCallback, Looper.myLooper())

    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    private inner class MyLocationCallback : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            if (locationResult != null && locationResult.lastLocation != null) {
                val location = locationResult.lastLocation
                currentLocation = location
                Log.d(LOG_TAG, "Received location: " + location.toString())
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
            Log.e(LOG_TAG, "Not able to Start the LocationManager Update thread ", e)
        }
    }

    private fun createLocationManagerRequest() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        Log.d(LOG_TAG, "Location update LocationManager created")
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
            Log.e(LOG_TAG, "Location update Exception: " + ex.message)
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
        Log.d(LOG_TAG, "Location Service Destroyed")
    }

    private fun destroyLocationThread() {
        if (handler != null && locationThread != null) {
            handler?.looper?.quitSafely()
            locationThread?.quitSafely()
            try {
                locationThread?.join()
                locationThread = null
            } catch (e: InterruptedException) {
                Log.e(LOG_TAG, e.localizedMessage)
            }

        }
    }

    private fun stopLocationProvider() {
        if (null != locationCallback) {
            fusedLocationProviderClient?.removeLocationUpdates(locationCallback!!)
        }
    }

    companion object {
        private val LOG_TAG = LocationService::class.java.simpleName
        private val LOCATION_SERVICE_NOTIFICATION_ID = 1234
    }
}
