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
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import com.aitech.weartak_civ.Utils.startGlobalTimer
import com.aitech.weartak_civ.data.LocationInput
import com.aitech.weartak_civ.data.LocationInputStatus
import com.aitech.weartak_civ.events.InteractionLocationRcvd
import com.aitech.weartak_civ.events.InteractionPliRefresh
import com.aitech.weartak_civ.events.LocationRcvd
import com.aitech.weartak_civ.events.LocationUpdateRequired
import com.aitech.weartak_civ.events.PowerManagerEvent
import com.aitech.weartak_civ.events.PliRefreshReason
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val fixConsumers = mutableMapOf<Acquisition, (Location) -> FixCompletion?>()
    private val fixCompletionMutex = Mutex()
    private var lastCompletedFix: Location? = null
    private var lastNormalFix: Location? = null
    private val requestVersions = mutableMapOf<Acquisition, Long>()
    private val timeouts = mutableMapOf<Acquisition, Runnable>()
    private val interactionGate = InteractionPliRefreshGate()
    private var periodic = false
    private var periodicLowPower = false
    private var dynamicReporting = false
    private var acquisitionIntervalMillis = 0L
    private var observationBaseIntervalMillis = 0L
    private val acquisitionPolicy = LocationAcquisitionPolicy()
    private var activity = ActivityRecognized.UNKNOWN
    private var observationPowerPolicy = false to false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockStartedMs = 0L
    private var receiverRegistered = false
    private val binder = LocationServiceBinder()

    private data class FixCompletion(
        val kind: Acquisition,
        val generation: Long,
        val input: LocationInput,
        val diagnosticContext: Map<DiagnosticMetric, Long>,
        val durationMillis: Long,
        val reasons: Set<PliRefreshReason>?,
        val cached: Boolean,
    )

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
        startAcquisitionActivityCollector()
    }

    private fun startAcquisitionActivityCollector() {
        scope.launch {
            combine(dataRepository.latestActivityRecognized, dataRepository.getEnableActRecService) {
                recognized, enabled ->
                if (enabled && ContextCompat.checkSelfPermission(this@LocationService,
                        Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
                    recognized else ActivityRecognized.UNKNOWN
            }.distinctUntilChanged().collect {
                activity = it
                if (LocationAcquisitionPolicy.moving(it)) resetAcquisitionBackoff()
                refreshPeriodicProfile()
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
        fixConsumers.remove(kind)
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
        lastCompletedFix = null
        lastNormalFix = null
        Acquisition.entries.forEach(::cancelRequest)
    }

    private fun completeFix(received: Location) {
        val location = Location(received)
        val completions = fixConsumers.values.toList().mapNotNull { it(location) }
        val first = completions.firstOrNull() ?: return
        scope.launch {
            fixCompletionMutex.withLock {
                if (!dataRepository.getEnableLocationService.first() ||
                    first.generation != generation || currentInput != first.input || !permitted(first.input)) return@withLock
                val newFix = lastCompletedFix != location
                if (newFix) dataRepository.storeLatestLocation(location)
                if (!dataRepository.isCurrentLocationInput(location) || first.generation != generation ||
                    currentInput != first.input || !permitted(first.input)) return@withLock
                if (newFix) {
                    lastCompletedFix = Location(location)
                    (application as? com.aitech.weartak_civ.WearTakApplication)
                        ?.locationDiagnostics?.observeExistingFix(location)
                }
                dataRepository.locationInputStatus.value = LocationInputStatus.READY
                val wasBackedOff = acquisitionPolicy.backoffLevel > 0
                acquisitionPolicy.reset()
                completions.forEach { completion ->
                    AppDiagnostics.record(DiagnosticEvent.LOCATION_ACCEPTED, completion.diagnosticContext +
                        (DiagnosticMetric.DURATION_MS to completion.durationMillis))
                }
                val normal = completions.any { it.kind != Acquisition.INTERACTION } && lastNormalFix != location
                if (normal) lastNormalFix = lastCompletedFix
                if (normal || completions.any { it.kind == Acquisition.ONE_SHOT }) {
                    if (dynamicReporting) scheduleNextDynamicObservation()
                    else if (wasBackedOff) scheduleConstantObservation()
                }
                if (first.generation != generation || currentInput != first.input ||
                    !dataRepository.isCurrentLocationInput(location)) return@withLock
                if (newFix || normal) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    val expected = nextObservationDeadlineNanos.value ?: (now +
                        PowerManagerService.locationObservationIntervalSeconds(dynamicReporting) * 1_000_000_000L)
                    val expiry = maxOf(now, expected) + ACQUISITION_TIMEOUT_MILLIS * 1_000_000L
                    // A cached observation must not grant an old speed a new full validity window.
                    val cachedExpiry = location.elapsedRealtimeNanos +
                        (PowerManagerService.locationObservationIntervalSeconds(dynamicReporting) * 1_000L +
                            ACQUISITION_TIMEOUT_MILLIS) * 1_000_000L
                    speedObservation.value = SpeedObservation(location,
                        if (completions.any { it.cached })
                            minOf(expiry, cachedExpiry) else expiry)
                    refreshPeriodicProfile()
                }
                if (normal) EventBus.getDefault().post(LocationRcvd())
                completions.firstOrNull { it.reasons != null }?.reasons?.let { reasons ->
                    EventBus.getDefault().post(InteractionLocationRcvd(location, reasons))
                }
            }
        }
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
        val lowPower = input == LocationInput.FUSED && kind != Acquisition.INTERACTION &&
            stationary()
        if (kind == Acquisition.PERIODIC) periodicLowPower = lowPower
        val priority = if (lowPower) Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_HIGH_ACCURACY
        val maxAgeMillis = if (lowPower) LocationAcquisitionPolicy.STATIONARY_MAX_AGE_MILLIS else 0L
        val deadlineNanos = when (kind) {
            Acquisition.PERIODIC -> Long.MAX_VALUE
            Acquisition.ONE_SHOT -> requestedNanos + ACQUISITION_TIMEOUT_MILLIS * 1_000_000L
            Acquisition.INTERACTION -> requestedNanos + InteractionPliRefreshGate.TIMEOUT_MILLIS * 1_000_000L
        }
        val diagnosticContext = mapOf(
            DiagnosticMetric.SOURCE to input.ordinal.toLong(),
            DiagnosticMetric.KIND to kind.ordinal.toLong(),
            DiagnosticMetric.BACKOFF_LEVEL to acquisitionPolicy.backoffLevel.toLong(),
            DiagnosticMetric.PRIORITY to priority.toLong(),
            DiagnosticMetric.MAX_AGE_MS to maxAgeMillis
        )
        AppDiagnostics.record(DiagnosticEvent.LOCATION_REQUEST, diagnosticContext +
            (DiagnosticMetric.INTERVAL_SECONDS to intervalMillis / 1_000L))
        fun acceptLocation(location: Location, active: Boolean): FixCompletion? {
            if (!active || requestGeneration != generation ||
                currentInput != input || !permitted(input) || location.provider != input.provider) return null
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            if (nowNanos > deadlineNanos || !LocationAcquisitionPolicy.freshEnough(
                    location.elapsedRealtimeNanos, requestedNanos, nowNanos, maxAgeMillis) || location.time <= 0 ||
                (location.elapsedRealtimeNanos < requestedNanos && !dataRepository.isCurrentLocationInput(location)) ||
                !location.altitude.isFinite() ||
                !InteractionPliRefreshGate.usable(location.latitude, location.longitude,
                    location.accuracy, location.hasAccuracy())) {
                Log.i(TAG, "Ignoring unusable or pre-request ${input.provider} fix")
                AppDiagnostics.record(DiagnosticEvent.LOCATION_REJECTED, diagnosticContext)
                return null
            }
            val reasons = if (kind == Acquisition.INTERACTION) {
                interactionGate.consume(location.elapsedRealtimeNanos, nowNanos, true) ?: return null
            } else null
            if (kind != Acquisition.PERIODIC) cancelRequest(kind)
            return FixCompletion(kind, requestGeneration, input, diagnosticContext,
                (nowNanos - requestedNanos) / 1_000_000L, reasons, location.elapsedRealtimeNanos < requestedNanos)
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (listeners[kind] === this) completeFix(location)
            }

            override fun onProviderDisabled(provider: String) {
                if (listeners[kind] === this && provider == input.provider &&
                    requestGeneration == generation) restartLocationUpdates()
            }
            override fun onProviderEnabled(provider: String) {
                if (listeners[kind] === this && provider == input.provider &&
                    requestGeneration == generation) restartLocationUpdates()
            }
            @Deprecated("Required on older Android versions")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val fusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (fusedCallbacks[kind] === this) result.lastLocation?.let(::completeFix)
            }
        }
        fixConsumers[kind] = { location ->
            acceptLocation(location, if (input == LocationInput.FUSED) fusedCallbacks[kind] === fusedCallback
                else listeners[kind] === listener)
        }
        if (input == LocationInput.FUSED) fusedCallbacks[kind] = fusedCallback
        else listeners[kind] = listener
        if (kind != Acquisition.PERIODIC) {
            val timeout = Runnable {
                if (!isRunning.get() || requestGeneration != generation ||
                    requestVersion != (requestVersions[kind] ?: 0L)) return@Runnable
                cancelRequest(kind)
                val completedVersion = requestVersions[kind]
                scope.launch {
                    val serviceEnabled = dataRepository.getEnableLocationService.first()
                    if (!isRunning.get() || requestGeneration != generation ||
                        completedVersion != requestVersions[kind]) return@launch
                    if (!serviceEnabled || !permitted(input)) {
                        dataRepository.locationInputStatus.value = if (serviceEnabled)
                            LocationInputStatus.PERMISSION_REQUIRED else LocationInputStatus.OFF
                        return@launch
                    }
                    Log.i(TAG, "$kind ${input.provider} request timed out without a usable fix")
                    AppDiagnostics.record(DiagnosticEvent.LOCATION_TIMEOUT, diagnosticContext +
                        (DiagnosticMetric.DURATION_MS to (SystemClock.elapsedRealtimeNanos() - requestedNanos) / 1_000_000L))
                    dataRepository.locationInputStatus.value = LocationInputStatus.REQUEST_FAILED
                    if (kind == Acquisition.ONE_SHOT) observationFailed()
                }
            }
            timeouts[kind] = timeout
            handler.postDelayed(timeout, if (kind == Acquisition.INTERACTION)
                InteractionPliRefreshGate.TIMEOUT_MILLIS else ACQUISITION_TIMEOUT_MILLIS)
        }
        try {
            if (input == LocationInput.FUSED) {
                val client = checkNotNull(fusedClient) { "Fused client missing after availability check" }
                val request = LocationRequest.Builder(priority,
                    intervalMillis.coerceAtLeast(1_000L))
                    .setMinUpdateIntervalMillis(0L)
                    .setMaxUpdateAgeMillis(maxAgeMillis)
                    .setWaitForAccurateLocation(!lowPower)
                    .apply {
                        if (kind != Acquisition.PERIODIC) setDurationMillis(
                            if (kind == Acquisition.INTERACTION) InteractionPliRefreshGate.TIMEOUT_MILLIS else ACQUISITION_TIMEOUT_MILLIS)
                    }
                    .build()
                client.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                    .addOnFailureListener { error ->
                        scope.launch {
                            val serviceEnabled = dataRepository.getEnableLocationService.first()
                            if (!isRunning.get() || fusedCallbacks[kind] !== fusedCallback ||
                                requestGeneration != generation) return@launch
                            Log.w(TAG, "Fused location request failed; no fallback", error)
                            AppDiagnostics.record(DiagnosticEvent.LOCATION_FAILURE, diagnosticContext)
                            cancelRequest(kind)
                            dataRepository.locationInputStatus.value = when {
                                !serviceEnabled -> LocationInputStatus.OFF
                                error is SecurityException || !permitted(input) -> LocationInputStatus.PERMISSION_REQUIRED
                                else -> LocationInputStatus.REQUEST_FAILED
                            }
                            if (serviceEnabled && kind == Acquisition.ONE_SHOT &&
                                error !is SecurityException && permitted(input)) observationFailed()
                        }
                    }
                    .addOnCanceledListener {
                        if (fusedCallbacks[kind] !== fusedCallback || requestGeneration != generation) return@addOnCanceledListener
                        Log.i(TAG, "$kind fused request cancelled")
                        cancelRequest(kind)
                        if (dynamicReporting && kind == Acquisition.ONE_SHOT)
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
            if (kind == Acquisition.ONE_SHOT) observationFailed()
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onInteractionPliRefresh(event: InteractionPliRefresh) {
        if (!isRunning.get()) return
        scope.launch {
            resetAcquisitionBackoff()
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
        acquisitionPolicy.reset()
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
            observationBaseIntervalMillis = acquisitionIntervalMillis
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

    private fun setObservationDeadline(deadlineNanos: Long) {
        nextObservationDeadlineNanos.value = deadlineNanos
        val observation = speedObservation.value ?: return
        val expiry = deadlineNanos + ACQUISITION_TIMEOUT_MILLIS * 1_000_000L
        if (expiry < observation.validUntilElapsedNanos)
            speedObservation.value = observation.copy(validUntilElapsedNanos = expiry)
    }

    private suspend fun scheduleNextDynamicObservation(
        anchorNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ) {
        val scheduleGeneration = generation
        if (!isRunning.get() || !dynamicReporting || !dataRepository.getEnableLocationService.first()) return
        if (scheduleGeneration != generation) return
        observationBaseIntervalMillis = PowerManagerService.locationObservationIntervalSeconds(true) * 1_000L
        val intervalMillis = acquisitionPolicy.intervalMillis(observationBaseIntervalMillis)
        AppDiagnostics.record(DiagnosticEvent.OBSERVATION_SCHEDULED,
            mapOf(DiagnosticMetric.INTERVAL_SECONDS to intervalMillis / 1_000L,
                DiagnosticMetric.BACKOFF_LEVEL to acquisitionPolicy.backoffLevel.toLong()))
        val deadline = anchorNanos + intervalMillis * 1_000_000L
        setObservationDeadline(deadline)
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
            val remainingMillis = (deadline - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
            if (remainingMillis > 0) runTimerJob(remainingMillis)
            else acquireWakelockAndRequestLocation()
        }
    }

    private fun stationary(): Boolean {
        val speed = speedObservation.value?.let { observation ->
            val location = observation.location
            SpeedReportingPolicy.validSpeed(location.speed.toDouble().takeIf { location.hasSpeed() },
                location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos(),
                dataRepository.isCurrentLocationInput(location), observation.validUntilElapsedNanos)
        }
        val permittedActivity = if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED) activity else ActivityRecognized.UNKNOWN
        return LocationAcquisitionPolicy.stationary(speed, permittedActivity)
    }

    private suspend fun refreshPeriodicProfile() {
        if (!periodic || currentInput != LocationInput.FUSED || periodicLowPower == stationary()) return
        cancelRequest(Acquisition.PERIODIC)
        requestUpdates(Acquisition.PERIODIC, acquisitionIntervalMillis)
    }

    private fun scheduleConstantObservation() {
        val interval = acquisitionPolicy.intervalMillis(
            PowerManagerService.locationObservationIntervalSeconds(false) * 1_000L)
        AppDiagnostics.record(DiagnosticEvent.OBSERVATION_SCHEDULED, mapOf(
            DiagnosticMetric.INTERVAL_SECONDS to interval / 1_000L,
            DiagnosticMetric.BACKOFF_LEVEL to acquisitionPolicy.backoffLevel.toLong()))
        runTimerJob(interval)
    }

    private fun observationFailed() {
        acquisitionPolicy.failed()
        val failedGeneration = generation
        scope.launch {
            if (!isRunning.get() || !dataRepository.getEnableLocationService.first()) return@launch
            if (failedGeneration != generation) return@launch
            if (dynamicReporting) scheduleNextDynamicObservation() else scheduleConstantObservation()
        }
    }

    private suspend fun resetAcquisitionBackoff() {
        val resetGeneration = generation
        val backedOff = acquisitionPolicy.backoffLevel > 0
        acquisitionPolicy.reset()
        if (!backedOff || !isRunning.get() || !dataRepository.getEnableLocationService.first()) return
        if (resetGeneration != generation) return
        if (listeners.containsKey(Acquisition.ONE_SHOT) || fusedCallbacks.containsKey(Acquisition.ONE_SHOT)) return
        if (dynamicReporting) scheduleNextDynamicObservation() else scheduleConstantObservation()
    }

    fun acquireWakelockAndRequestLocation() {
        handler.post {
            val requestGeneration = generation
            val requestVersion = requestVersions[Acquisition.ONE_SHOT] ?: 0L
            if (listeners.containsKey(Acquisition.ONE_SHOT) || fusedCallbacks.containsKey(Acquisition.ONE_SHOT)) {
                AppDiagnostics.record(DiagnosticEvent.LOCATION_COALESCED)
                return@post
            }
            scope.launch {
                val enabled = dataRepository.getEnableLocationService.first()
                if (!isRunning.get() || periodic || !enabled || requestGeneration != generation) return@launch
                if (requestVersion != (requestVersions[Acquisition.ONE_SHOT] ?: 0L) ||
                    listeners.containsKey(Acquisition.ONE_SHOT) || fusedCallbacks.containsKey(Acquisition.ONE_SHOT)) {
                    AppDiagnostics.record(DiagnosticEvent.LOCATION_COALESCED)
                    return@launch
                }
                cancelRequest(Acquisition.ONE_SHOT)
                if (!dynamicReporting) startGlobalTimer(acquisitionPolicy.intervalMillis(
                    PowerManagerService.locationObservationIntervalSeconds(false) * 1_000L))
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
            if (!dynamicReporting) {
                restartLocationUpdates()
                return
            }
            val intervalMillis = PowerManagerService.locationObservationIntervalSeconds(true) * 1_000L
            if (observationPowerPolicy == policy && observationBaseIntervalMillis == intervalMillis) return
            observationPowerPolicy = policy
            observationBaseIntervalMillis = intervalMillis
            acquisitionPolicy.reset()
            // Keep native pending requests and their deadlines; their completion uses the new profile.
            if (listeners.containsKey(Acquisition.ONE_SHOT) || fusedCallbacks.containsKey(Acquisition.ONE_SHOT)) return
            val anchor = nextObservationDeadlineNanos.value?.minus(acquisitionIntervalMillis * 1_000_000L)
                ?: SystemClock.elapsedRealtimeNanos()
            scope.launch { scheduleNextDynamicObservation(anchor) }
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
        stopForegroundServiceCompat()
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
