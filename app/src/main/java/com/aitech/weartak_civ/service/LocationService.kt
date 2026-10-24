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
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.Utils.runTimerJob
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.startGlobalTimer
import com.aitech.weartak_civ.data.LocationInput
import com.aitech.weartak_civ.data.LocationInputStatus
import com.aitech.weartak_civ.events.InteractionLocationRcvd
import com.aitech.weartak_civ.events.InteractionPliRefresh
import com.aitech.weartak_civ.events.LocationRcvd
import com.aitech.weartak_civ.events.LocationUpdateRequired
import com.aitech.weartak_civ.events.PowerManagerEvent
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.atomic.AtomicBoolean

class LocationService : Service() {
    private enum class Acquisition { PERIODIC, ONE_SHOT, INTERACTION }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var locationManager: LocationManager? = null
    private var currentInput: LocationInput? = null
    private var generation = 0L
    private val listeners = mutableMapOf<Acquisition, LocationListener>()
    private val requestVersions = mutableMapOf<Acquisition, Long>()
    private val timeouts = mutableMapOf<Acquisition, Runnable>()
    private val interactionGate = InteractionPliRefreshGate()
    private var periodic = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false
    private val binder = LocationServiceBinder()

    private val providerChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) restartLocationUpdates()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        _instance = this
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        startForegroundServiceCompat(notificationId = LOCATION_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW)
        ContextCompat.registerReceiver(this, providerChanges,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        EventBus.getDefault().register(this)
        scope.launch {
            dataRepository.getLocationInput.distinctUntilChanged().collect { input ->
                currentInput = input
                restartLocationUpdates()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    private fun permitted(input: LocationInput): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || (input == LocationInput.NETWORK &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED)
    }

    private fun inputAvailable(): Boolean {
        val input = currentInput
        val manager = locationManager
        val status = try {
            when {
                input == null || manager == null -> LocationInputStatus.UNAVAILABLE
                !permitted(input) -> LocationInputStatus.PERMISSION_REQUIRED
                input.provider !in manager.allProviders -> LocationInputStatus.UNAVAILABLE
                !manager.isProviderEnabled(input.provider) -> LocationInputStatus.DISABLED
                else -> LocationInputStatus.ACQUIRING
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Selected input permission revoked during availability check", error)
            LocationInputStatus.PERMISSION_REQUIRED
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Selected input removed during availability check", error)
            LocationInputStatus.UNAVAILABLE
        }
        dataRepository.locationInputStatus.value = status
        if (status != LocationInputStatus.ACQUIRING)
            Log.i(TAG, "Selected location input ${input?.provider} is $status; no fallback")
        return status == LocationInputStatus.ACQUIRING
    }

    private fun cancelRequest(kind: Acquisition) {
        requestVersions[kind] = (requestVersions[kind] ?: 0L) + 1L
        timeouts.remove(kind)?.let(handler::removeCallbacks)
        listeners.remove(kind)?.let { locationManager?.removeUpdates(it) }
        if (kind == Acquisition.INTERACTION) interactionGate.cancel()
        if (kind == Acquisition.ONE_SHOT) {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
        }
    }

    private fun cancelRequests() {
        generation++
        Acquisition.entries.forEach(::cancelRequest)
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestUpdates(kind: Acquisition, intervalMillis: Long = 0) {
        val requestGeneration = generation
        val requestVersion = requestVersions[kind] ?: 0L
        val input = currentInput
        val enabled = dataRepository.getEnableLocationService.first()
        if (!isRunning.get() || requestGeneration != generation || currentInput != input ||
            requestVersion != (requestVersions[kind] ?: 0L)) return
        if (!enabled) {
            cancelRequest(kind)
            dataRepository.locationInputStatus.value = LocationInputStatus.OFF
            return
        }
        if (!inputAvailable()) return
        if (input == null) return
        val manager = locationManager ?: return
        val requestedNanos = SystemClock.elapsedRealtimeNanos()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (listeners[kind] !== this || requestGeneration != generation ||
                    currentInput != input || !permitted(input) || location.provider != input.provider) return
                val nowNanos = SystemClock.elapsedRealtimeNanos()
                if (location.elapsedRealtimeNanos < requestedNanos ||
                    location.elapsedRealtimeNanos > nowNanos || location.time <= 0 ||
                    !location.altitude.isFinite() ||
                    !InteractionPliRefreshGate.usable(location.latitude, location.longitude,
                        location.accuracy, location.hasAccuracy())) {
                    Log.i(TAG, "Ignoring unusable or pre-request ${input.provider} fix")
                    return
                }
                val reasons = if (kind == Acquisition.INTERACTION) {
                    interactionGate.consume(location.elapsedRealtimeNanos, nowNanos, true) ?: return
                } else null
                if (kind != Acquisition.PERIODIC) cancelRequest(kind)
                scope.launch {
                    if (!dataRepository.getEnableLocationService.first() ||
                        requestGeneration != generation || currentInput != input || !permitted(input)) return@launch
                    dataRepository.storeLatestLocation(location)
                    if (!dataRepository.isCurrentLocationInput(location) || requestGeneration != generation) return@launch
                    (application as? com.aitech.weartak_civ.WearTakApplication)
                        ?.locationDiagnostics?.observeExistingFix(location)
                    dataRepository.locationInputStatus.value = LocationInputStatus.READY
                    if (reasons != null) EventBus.getDefault().post(InteractionLocationRcvd(location, reasons))
                    else EventBus.getDefault().post(LocationRcvd())
                }
            }

            override fun onProviderDisabled(provider: String) {
                if (provider == input.provider && requestGeneration == generation) restartLocationUpdates()
            }
            override fun onProviderEnabled(provider: String) {
                if (provider == input.provider && requestGeneration == generation) restartLocationUpdates()
            }
            @Deprecated("Required on older Android versions")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listeners[kind] = listener
        if (kind != Acquisition.PERIODIC) {
            val timeout = Runnable {
                Log.i(TAG, "$kind ${input.provider} request timed out without a fresh usable fix")
                cancelRequest(kind)
                dataRepository.locationInputStatus.value = LocationInputStatus.REQUEST_FAILED
            }
            timeouts[kind] = timeout
            handler.postDelayed(timeout, if (kind == Acquisition.INTERACTION)
                InteractionPliRefreshGate.TIMEOUT_MILLIS else 6_000L)
        }
        try {
            manager.requestLocationUpdates(input.provider, intervalMillis, 0f, listener, Looper.getMainLooper())
        } catch (error: SecurityException) {
            Log.w(TAG, "Selected input permission revoked: ${input.provider}", error)
            cancelRequest(kind)
            dataRepository.locationInputStatus.value = LocationInputStatus.PERMISSION_REQUIRED
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Selected input unavailable: ${input.provider}", error)
            cancelRequest(kind)
            dataRepository.locationInputStatus.value = LocationInputStatus.UNAVAILABLE
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onInteractionPliRefresh(event: InteractionPliRefresh) {
        if (!isRunning.get()) return
        scope.launch {
            if (!inputAvailable()) {
                cancelRequest(Acquisition.INTERACTION)
                return@launch
            }
            if (!interactionGate.request(event.reason, SystemClock.elapsedRealtimeNanos())) return@launch
            requestUpdates(Acquisition.INTERACTION)
        }
    }

    private fun restartLocationUpdates() {
        cancelRequests()
        if (!isRunning.get()) return
        val restartGeneration = generation
        scope.launch {
            if (!dataRepository.getEnableLocationService.first()) {
                dataRepository.locationInputStatus.value = LocationInputStatus.OFF
                return@launch
            }
            if (restartGeneration != generation) return@launch
            periodic = CotDispatchService.actualReportingIntervalSecs < MAX_PERIODIC_GPS_UPDATE_INTERVAL
            if (periodic) requestUpdates(Acquisition.PERIODIC,
                CotDispatchService.actualReportingIntervalSecs * 1_000L)
            else {
                requestUpdates(Acquisition.ONE_SHOT)
                runTimerJob(CotDispatchService.actualReportingIntervalSecs * 1_000L)
            }
        }
    }

    fun acquireWakelockAndRequestLocation() {
        handler.post {
            scope.launch {
                if (!isRunning.get() || periodic || !dataRepository.getEnableLocationService.first()) return@launch
                cancelRequest(Acquisition.ONE_SHOT)
                startGlobalTimer(CotDispatchService.actualReportingIntervalSecs * 1_000L)
                if (!inputAvailable()) return@launch
                val power = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "id:gps").apply { acquire(6_000) }
                requestUpdates(Acquisition.ONE_SHOT)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onLocationUpdateRequiredEvent(event: LocationUpdateRequired) = restartLocationUpdates()

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPowerManagerEvent(event: PowerManagerEvent) {
        if (event.eventType == PowerManagerEvent.CHANGED_UPDATE_INTERVAL) restartLocationUpdates()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    inner class LocationServiceBinder : Binder() {
        val service: LocationService get() = this@LocationService
    }

    override fun onDestroy() {
        isRunning.set(false)
        cancelRequests()
        scope.cancel()
        if (receiverRegistered) unregisterReceiver(providerChanges)
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this)
        dataRepository.locationInputStatus.value = LocationInputStatus.OFF
        locationManager = null
        _instance = null
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WearTAK-LocationService"
        private const val LOCATION_SERVICE_NOTIFICATION_ID = 1234
        private const val MAX_PERIODIC_GPS_UPDATE_INTERVAL = 10
        var lat: Double? = null
        var lon: Double? = null
        var hae: Double? = null
        var ce: Float? = null
        var le: Float? = null
        val isRunning = AtomicBoolean(false)
        @Volatile private var _instance: LocationService? = null
        fun getInstance(): LocationService? = _instance
    }
}
