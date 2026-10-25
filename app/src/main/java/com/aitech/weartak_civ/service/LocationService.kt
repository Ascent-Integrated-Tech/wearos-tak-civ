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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
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
    private var fusedClient: FusedLocationProviderClient? = null
    private var currentInput: LocationInput? = null
    private var generation = 0L
    private val listeners = mutableMapOf<Acquisition, LocationListener>()
    private val fusedCallbacks = mutableMapOf<Acquisition, LocationCallback>()
    private val requestVersions = mutableMapOf<Acquisition, Long>()
    private val timeouts = mutableMapOf<Acquisition, Runnable>()
    private val interactionGate = InteractionPliRefreshGate()
    private var periodic = false
    private var dynamicReporting = false
    private var acquisitionIntervalMillis = 0L
    private var observationPowerPolicy = false to false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockStartedMs = 0L
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
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundServiceCompat(notificationId = LOCATION_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW)
        ContextCompat.registerReceiver(this, providerChanges,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        EventBus.getDefault().register(this)
        scope.launch {
            combine(dataRepository.getLocationInput, dataRepository.getEnableDynamicReportingStrategy) {
                input, dynamic -> input to dynamic
            }.distinctUntilChanged().collect { (input, dynamic) ->
                currentInput = input
                dynamicReporting = dynamic
                restartLocationUpdates()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    private fun permitted(input: LocationInput): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || (input != LocationInput.INTERNAL_GNSS &&
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
                input == LocationInput.FUSED && fusedClient == null -> LocationInputStatus.UNAVAILABLE
                input == LocationInput.FUSED && !manager.isLocationEnabled -> LocationInputStatus.DISABLED
                input == LocationInput.FUSED -> LocationInputStatus.ACQUIRING
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
        val listener = listeners.remove(kind)
        val callback = fusedCallbacks.remove(kind)
        if (listener != null || callback != null) AppDiagnostics.record(DiagnosticEvent.LOCATION_STOPPED,
            mapOf(DiagnosticMetric.KIND to kind.ordinal.toLong()))
        try {
            listener?.let { locationManager?.removeUpdates(it) }
            callback?.let {
                fusedClient?.removeLocationUpdates(it)?.addOnFailureListener { error ->
                    Log.w(TAG, "Could not remove $kind fused request; late callbacks remain rejected", error)
                }
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Location permission revoked during $kind cleanup; late callbacks remain rejected", error)
        }
        if (kind == Acquisition.INTERACTION) interactionGate.cancel()
        if (kind == Acquisition.ONE_SHOT) {
            wakeLock?.let { lock ->
                val held = lock.isHeld
                if (held) lock.release()
                AppDiagnostics.record(DiagnosticEvent.WAKE_LOCK, mapOf(
                    DiagnosticMetric.ACTIVE to 0L, DiagnosticMetric.RESULT to if (held) 1L else 0L,
                    DiagnosticMetric.DURATION_MS to (SystemClock.elapsedRealtime() - wakeLockStartedMs)
                        .coerceIn(0, ACQUISITION_TIMEOUT_MILLIS)))
            }
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
        val diagnosticContext = mapOf(
            DiagnosticMetric.SOURCE to input.ordinal.toLong(),
            DiagnosticMetric.KIND to kind.ordinal.toLong()
        )
        AppDiagnostics.record(DiagnosticEvent.LOCATION_REQUEST, diagnosticContext +
            (DiagnosticMetric.INTERVAL_SECONDS to intervalMillis / 1_000L))
        fun acceptLocation(location: Location, active: Boolean) {
            if (!active || requestGeneration != generation ||
                currentInput != input || !permitted(input) || location.provider != input.provider) return
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            if (location.elapsedRealtimeNanos < requestedNanos ||
                location.elapsedRealtimeNanos > nowNanos || location.time <= 0 ||
                !location.altitude.isFinite() ||
                !InteractionPliRefreshGate.usable(location.latitude, location.longitude,
                    location.accuracy, location.hasAccuracy())) {
                Log.i(TAG, "Ignoring unusable or pre-request ${input.provider} fix")
                AppDiagnostics.record(DiagnosticEvent.LOCATION_REJECTED, diagnosticContext)
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
                AppDiagnostics.record(DiagnosticEvent.LOCATION_ACCEPTED, diagnosticContext +
                    (DiagnosticMetric.DURATION_MS to (nowNanos - requestedNanos) / 1_000_000L))
                if (dynamicReporting && kind != Acquisition.INTERACTION) scheduleNextDynamicObservation()
                val now = SystemClock.elapsedRealtimeNanos()
                val expected = nextObservationDeadlineNanos.value ?: (now +
                    PowerManagerService.locationObservationIntervalSeconds(dynamicReporting) * 1_000_000_000L)
                speedObservation.value = SpeedObservation(
                    location, maxOf(now, expected) + ACQUISITION_TIMEOUT_MILLIS * 1_000_000L,
                )
                if (reasons != null) EventBus.getDefault().post(InteractionLocationRcvd(location, reasons))
                else EventBus.getDefault().post(LocationRcvd())
            }
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                acceptLocation(location, listeners[kind] === this)
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
        val fusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { acceptLocation(it, fusedCallbacks[kind] === this) }
            }
        }
        if (input == LocationInput.FUSED) fusedCallbacks[kind] = fusedCallback
        else listeners[kind] = listener
        if (kind != Acquisition.PERIODIC) {
            val timeout = Runnable {
                Log.i(TAG, "$kind ${input.provider} request timed out without a fresh usable fix")
                AppDiagnostics.record(DiagnosticEvent.LOCATION_TIMEOUT, diagnosticContext +
                    (DiagnosticMetric.DURATION_MS to (SystemClock.elapsedRealtimeNanos() - requestedNanos) / 1_000_000L))
                cancelRequest(kind)
                dataRepository.locationInputStatus.value = LocationInputStatus.REQUEST_FAILED
                if (dynamicReporting && kind == Acquisition.ONE_SHOT)
                    scope.launch { scheduleNextDynamicObservation() }
            }
            timeouts[kind] = timeout
            handler.postDelayed(timeout, if (kind == Acquisition.INTERACTION)
                InteractionPliRefreshGate.TIMEOUT_MILLIS else ACQUISITION_TIMEOUT_MILLIS)
        }
        try {
            if (input == LocationInput.FUSED) {
                val client = checkNotNull(fusedClient) { "Fused client missing after availability check" }
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,
                    intervalMillis.coerceAtLeast(1_000L))
                    .setMinUpdateIntervalMillis(0L)
                    .setMaxUpdateAgeMillis(0L)
                    .setWaitForAccurateLocation(true)
                    .apply {
                        if (kind != Acquisition.PERIODIC) setDurationMillis(
                            if (kind == Acquisition.INTERACTION) InteractionPliRefreshGate.TIMEOUT_MILLIS else ACQUISITION_TIMEOUT_MILLIS)
                    }
                    .build()
                client.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                    .addOnFailureListener { error ->
                        if (fusedCallbacks[kind] !== fusedCallback || requestGeneration != generation) return@addOnFailureListener
                        Log.w(TAG, "Fused location request failed; no fallback", error)
                        AppDiagnostics.record(DiagnosticEvent.LOCATION_FAILURE, diagnosticContext)
                        cancelRequest(kind)
                        dataRepository.locationInputStatus.value = if (error is SecurityException)
                            LocationInputStatus.PERMISSION_REQUIRED else LocationInputStatus.REQUEST_FAILED
                        if (dynamicReporting && kind == Acquisition.ONE_SHOT && error !is SecurityException)
                            scope.launch { scheduleNextDynamicObservation() }
                    }
            } else {
                manager.requestLocationUpdates(input.provider, intervalMillis, 0f, listener, Looper.getMainLooper())
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Selected input permission revoked: ${input.provider}", error)
            AppDiagnostics.record(DiagnosticEvent.LOCATION_FAILURE, diagnosticContext +
                (DiagnosticMetric.RESULT to 1L))
            cancelRequest(kind)
            dataRepository.locationInputStatus.value = LocationInputStatus.PERMISSION_REQUIRED
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Selected input unavailable: ${input.provider}", error)
            AppDiagnostics.record(DiagnosticEvent.LOCATION_FAILURE, diagnosticContext +
                (DiagnosticMetric.RESULT to 2L))
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
            if (!interactionGate.request(event.reason, SystemClock.elapsedRealtimeNanos())) {
                AppDiagnostics.record(DiagnosticEvent.LOCATION_COALESCED)
                return@launch
            }
            requestUpdates(Acquisition.INTERACTION)
        }
    }

    private fun restartLocationUpdates() {
        cancelRequests()
        nextObservationDeadlineNanos.value = null
        speedObservation.value = null
        observationPowerPolicy = PowerManagerService.observationPowerThrottled to
            PowerManagerService.observationDockThrottled
        if (!isRunning.get()) return
        val restartGeneration = generation
        scope.launch {
            if (!dataRepository.getEnableLocationService.first()) {
                dataRepository.locationInputStatus.value = LocationInputStatus.OFF
                return@launch
            }
            if (restartGeneration != generation) return@launch
            val interval = PowerManagerService.locationObservationIntervalSeconds(dynamicReporting)
            acquisitionIntervalMillis = interval * 1_000L
            periodic = interval < MAX_PERIODIC_GPS_UPDATE_INTERVAL
            if (dynamicReporting)
                nextObservationDeadlineNanos.value = SystemClock.elapsedRealtimeNanos() +
                    acquisitionIntervalMillis * 1_000_000L
            if (periodic) requestUpdates(Acquisition.PERIODIC,
                acquisitionIntervalMillis)
            else {
                requestUpdates(Acquisition.ONE_SHOT)
                if (!dynamicReporting) runTimerJob(acquisitionIntervalMillis)
            }
        }
    }

    private suspend fun scheduleNextDynamicObservation() {
        if (!isRunning.get() || !dynamicReporting || !dataRepository.getEnableLocationService.first()) return
        val intervalMillis = PowerManagerService.locationObservationIntervalSeconds(true) * 1_000L
        AppDiagnostics.record(DiagnosticEvent.OBSERVATION_SCHEDULED,
            mapOf(DiagnosticMetric.INTERVAL_SECONDS to intervalMillis / 1_000L))
        nextObservationDeadlineNanos.value = SystemClock.elapsedRealtimeNanos() + intervalMillis * 1_000_000L
        if (intervalMillis < MAX_PERIODIC_GPS_UPDATE_INTERVAL * 1_000L) {
            if (!periodic || intervalMillis != acquisitionIntervalMillis) {
                // Change a subscription only after its observation completed, never on speed expiry.
                cancelRequest(Acquisition.PERIODIC)
                periodic = true
                acquisitionIntervalMillis = intervalMillis
                requestUpdates(Acquisition.PERIODIC, intervalMillis)
            }
        } else {
            cancelRequest(Acquisition.PERIODIC)
            periodic = false
            acquisitionIntervalMillis = intervalMillis
            runTimerJob(intervalMillis)
        }
    }

    fun acquireWakelockAndRequestLocation() {
        handler.post {
            scope.launch {
                if (!isRunning.get() || periodic || !dataRepository.getEnableLocationService.first()) return@launch
                cancelRequest(Acquisition.ONE_SHOT)
                if (!dynamicReporting) startGlobalTimer(CotDispatchService.actualReportingIntervalSecs * 1_000L)
                if (!inputAvailable()) return@launch
                val power = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "id:gps").apply { acquire(6_000) }
                wakeLockStartedMs = SystemClock.elapsedRealtime()
                AppDiagnostics.record(DiagnosticEvent.WAKE_LOCK, mapOf(
                    DiagnosticMetric.ACTIVE to 1L, DiagnosticMetric.DURATION_MS to 6_000L))
                requestUpdates(Acquisition.ONE_SHOT)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onLocationUpdateRequiredEvent(event: LocationUpdateRequired) = restartLocationUpdates()

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPowerManagerEvent(event: PowerManagerEvent) {
        if (event.eventType == PowerManagerEvent.CHANGED_UPDATE_INTERVAL) {
            val policy = PowerManagerService.observationPowerThrottled to PowerManagerService.observationDockThrottled
            // Speed/Unknown changes affect the next acquisition cycle, not an in-flight request.
            if (!dynamicReporting || observationPowerPolicy != policy) restartLocationUpdates()
        }
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
        nextObservationDeadlineNanos.value = null
        speedObservation.value = null
        locationManager = null
        fusedClient = null
        _instance = null
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        const val ACQUISITION_TIMEOUT_MILLIS = 6_000L
        data class SpeedObservation(val location: Location, val validUntilElapsedNanos: Long)
        val speedObservation = MutableStateFlow<SpeedObservation?>(null)
        val nextObservationDeadlineNanos = MutableStateFlow<Long?>(null)
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
