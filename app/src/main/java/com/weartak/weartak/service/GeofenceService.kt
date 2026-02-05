/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.weartak.weartak.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.weartak.weartak.Utils.isPointInPolygon
import com.weartak.weartak.Utils.setAirplaneMode
import com.weartak.weartak.Utils.startForegroundServiceCompat
import com.weartak.weartak.data.GeofenceRepository
import com.weartak.weartak.data.model.GeofenceCot
import com.weartak.weartak.data.model.GeofenceTrigger
import com.weartak.weartak.data.model.LatLon
import com.weartak.weartak.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean


class GeofenceService: Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val geofenceMutex = Mutex()
    private val geofencesByUid = LinkedHashMap<String, GeofenceCot>()
    private val insideStateByUid = HashMap<String, Boolean>()
    private var latestLatLon: LatLon? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "GeofenceService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            GEOFENCE_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        startLocationCollector()
        startGeofenceCotConsumer(serviceScope) { geofence ->
            handleIncomingGeofenceCot(geofence)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "GeofenceService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "GeofenceService onDestroy()")
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_DETACH)
        serviceScope.cancel()
    }

    private fun shouldTrackEntry(g: GeofenceCot): Boolean {
        if (!g.tracking) return false
        return g.trigger == GeofenceTrigger.Entry || g.trigger == GeofenceTrigger.Both
    }

    private suspend fun evaluateGeofencesForPoint(point: LatLon) {
        val snapshot: List<GeofenceCot> = geofenceMutex.withLock {
            geofencesByUid.values.toList()
        }
        for (g in snapshot) {
            evaluateSingleGeofence(g, point)
        }
    }

    private suspend fun evaluateSingleGeofence(geofence: GeofenceCot, point: LatLon) {
        // If it’s in the map, it *should* already satisfy this, but keeping it safe.
        if (!shouldTrackEntry(geofence)) return

        if (geofence.points.size < 3) return

        val isInsideNow = isPointInPolygon(point, geofence.points)

        val wasInside = geofenceMutex.withLock {
            val prev = insideStateByUid[geofence.uid] ?: false
            insideStateByUid[geofence.uid] = isInsideNow
            prev
        }

        if (!wasInside && isInsideNow) {
            Log.i(TAG, "ENTERED geofence uid=${geofence.uid} callsign=${geofence.callsign}")
            val success = setAirplaneMode("1")
            if (success) dataRepository.setAirplaneOnSuccess(true)
        }
    }

    private suspend fun handleIncomingGeofenceCot(geofence: GeofenceCot) {
        // Dispatch to TAK / ATAK pipeline here
        // e.g., cotDispatchService.send(xml)
        Log.i(TAG, "Received geofence: $geofence")

        // If tracking is false, remove it and return.
        if (!geofence.tracking) {
            geofenceMutex.withLock {
                geofencesByUid.remove(geofence.uid)
                // We can also automatically remove airplane mode but not for now.
                insideStateByUid.remove(geofence.uid)
            }
            Log.i(TAG, "Removed geofence uid=${geofence.uid} because tracking=false")
            return
        }

        // Only keep geofences that are trackable by your rule (Entry or Both).
        if (!shouldTrackEntry(geofence)) {
            geofenceMutex.withLock {
                geofencesByUid.remove(geofence.uid)
                insideStateByUid.remove(geofence.uid)
            }
            Log.i(TAG, "Removed/ignored geofence uid=${geofence.uid} because tracking either is false (tracking=${geofence.tracking}) or trigger is not Entry/Both (trigger=${geofence.trigger})")
            return
        }

        // Add/update store
        geofenceMutex.withLock {
            geofencesByUid[geofence.uid] = geofence
            insideStateByUid.putIfAbsent(geofence.uid, false)
        }

        // Evaluate immediately if we have a latest point
        val point = latestLatLon ?: return
        evaluateSingleGeofence(geofence, point)
    }
    fun startGeofenceCotConsumer(
        scope: CoroutineScope,
        onCotReceived: suspend (GeofenceCot) -> Unit
    ) {
        scope.launch {
            GeofenceRepository.geofenceCotFlow.collect { geofence ->
                onCotReceived(geofence)
            }
        }
    }

    private suspend fun onLocationUpdate(point: LatLon) {
        evaluateGeofencesForPoint(point)
    }

    private fun startLocationCollector() {
        serviceScope.launch {
            dataRepository.latestLocation
                .filterNotNull()
                .collect { loc ->
                    val point = LatLon(lat = loc.latitude, lon = loc.longitude)
                    if (point != latestLatLon) {
                        onLocationUpdate(point) // evaluates all stored geofences
                        latestLatLon = point
                    }
                }
        }
    }

    companion object {
        private val TAG = "WearTAK-" + GeofenceService::class.java.simpleName
        const private val GEOFENCE_SERVICE_NOTIFICATION_ID = 178
        val isRunning : AtomicBoolean = AtomicBoolean(false)
    }
}