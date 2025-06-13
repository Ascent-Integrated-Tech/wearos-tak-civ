/*
 * Copyright (C) 2024-2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.LocationManager.FUSED_PROVIDER
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.LocationService
import android.location.LocationListener
import android.os.Looper
import com.aitech.weartak_civ.Utils.writeToLogFileLocation
import com.aitech.weartak_civ.events.LocationRcvd
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
import java.text.SimpleDateFormat
import java.util.Locale.getDefault


class AlarmReceiver : BroadcastReceiver() {
    companion object {
        private val TAG = "WearTAK-AlarmReceiver"
        private var prevLocationUpdateTime: Long = 0L
    }

    private val mLocationListener : LocationListener = MyLocationListener()
    private var mPowerManager: PowerManager? = null
    private var mWakeLock: PowerManager.WakeLock? = null
    private var mContext: Context? = null
    private lateinit var fusedLocationClient : FusedLocationProviderClient

    public override fun onReceive(context: Context, intent: Intent?) {
        if (null == mContext) {
            mContext = context.applicationContext
        }
        Log.d(TAG, "onReceive broadcast")

        // START THE WAKE LOCK TO WAKE UP THE DEVICE
        if (null == mPowerManager) {
            mPowerManager = context.applicationContext.getSystemService(POWER_SERVICE) as PowerManager?
        }

        if (mWakeLock?.isHeld() ?: false) {
            mWakeLock?.release()
        }
        mWakeLock = null

        mWakeLock = mPowerManager?.newWakeLock(
            PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.PARTIAL_WAKE_LOCK,
            "id:wakeupscreen"
        )

        mWakeLock?.acquire()
        Log.d(TAG, "Acquired wakelock")


        /*
        // START GPS Code
        val criteria: Criteria = Criteria()
        criteria.setAccuracy(Criteria.ACCURACY_FINE)
        criteria.setPowerRequirement(Criteria.POWER_HIGH)
        criteria.setAltitudeRequired(true)
        criteria.setBearingRequired(true)

        val locationManager: LocationManager = context.getApplicationContext()
            .getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val provider: String = locationManager.getBestProvider(criteria, true) ?: FUSED_PROVIDER

         */
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(mainActivity)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0).apply {
            setMaxUpdates(1)
            setMinUpdateIntervalMillis(0)
            setWaitForAccurateLocation(true)
        }.build()

        if (ActivityCompat.checkSelfPermission(
                MainActivity.mainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) !== PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                MainActivity.mainActivity,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) !== PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        /*
        Log.d(TAG, "Going to request location updates with provider=$provider")
        locationManager.requestLocationUpdates(provider, 0L, 0f, mLocationListener)


        // AFTER 2000 milliseconds remove the wakelock and remove the location listener.  If you do not need to remove other processes you can shut down the wake lock after a certain interval using a parameter in the acquire method.
        Handler().postDelayed(object : java.lang.Runnable {
            override fun run() {
                Log.d(TAG, "Going to remove location updates")
                locationManager.removeUpdates(mLocationListener)

                if (mWakeLock?.isHeld() ?: false) {
                    mWakeLock?.release()
                }
                mWakeLock = null
            }
        }, 6000)
         */

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        // RESTART THE TIMER (ALARM)
        Log.d(TAG, "Going to call startGlobalTimer")
        LocationService.getInstance().startGlobalTimer()
    }

    val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return

            CoroutineScope(Dispatchers.IO).launch {
                val currTime = System.currentTimeMillis()
                dataRepository.storeLatestLocation(location)    // TODO: Also check if we need to run storeLatestLocation within a coroutine (lines 263-264) - I don't think we need to - this will save us some context switching cpu cycles.
                val logStr = "onLocationChanged (prev: ${(currTime-prevLocationUpdateTime)}): $location"
                Log.d(TAG, logStr)
                writeToLogFileLocation(logFile, logStr)
                prevLocationUpdateTime = currTime
                EventBus.getDefault().post(LocationRcvd())
            }

            fusedLocationClient.removeLocationUpdates(this)
        }
    }

    // GPS Location listener
    inner class MyLocationListener : LocationListener {
        override fun onLocationChanged(@NonNull location: Location) {
            val latitude: Double = location.latitude
            val longitude: Double = location.longitude

            /*
            //addDataToDB(latitude.toString(), longitude.toString())
            val currTime = System.currentTimeMillis()
            val logStr = "onLocationChanged (prev: ${(currTime-prevLocationUpdateTime)}): $location"
            prevLocationUpdateTime = currTime
            Log.d(TAG, logStr)
            writeToLogFileLocation(logFile, logStr)

             */

            CoroutineScope(Dispatchers.IO).launch {
                val currTime = System.currentTimeMillis()
                dataRepository.storeLatestLocation(location)    // TODO: Also check if we need to run storeLatestLocation within a coroutine (lines 263-264) - I don't think we need to - this will save us some context switching cpu cycles.
                val logStr = "onLocationChanged (prev: ${(currTime-prevLocationUpdateTime)}): $location"
                Log.d(TAG, logStr)
                writeToLogFileLocation(logFile, logStr)
                prevLocationUpdateTime = currTime
                EventBus.getDefault().post(LocationRcvd())
            }
        }

        fun addDataToDB(entryLatitude: String?, entryLongitude: String?) {
            val date: Long? = System.currentTimeMillis()
            val dateFormat: SimpleDateFormat = SimpleDateFormat("MM-dd-yyyy HH:mm:ss", getDefault())
            val entryDate: String? = dateFormat.format(date)

            Log.d(TAG, entryDate.toString() + ": (" + entryLatitude + ", " + entryLongitude + ")"
            )
        }
    }

}