/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.data

import android.util.Log
import com.aitech.weartak_civ.data.model.GeofenceCot
import kotlinx.coroutines.flow.StateFlow


object GeofenceRepository {
    private const val NON_TAK_SOURCE = "non-tak:geofence"
    private val geofenceStore = SourceAwareCotStore(
        sourceIdOf = { geofence: GeofenceCot -> geofence.sourceServerId ?: NON_TAK_SOURCE },
        cotUidOf = GeofenceCot::uid,
        eventTimeOf = GeofenceCot::eventTime,
        receivedAtOf = GeofenceCot::receivedAt
    )

    val geofenceCotFlow: StateFlow<List<GeofenceCot>> = geofenceStore.visibleItems

    fun trySendGeofenceCot(geofence: GeofenceCot): Boolean {
        applyGeofence(geofence)
        return true
    }

    suspend fun sendGeofenceCot(geofence: GeofenceCot) {
        applyGeofence(geofence)
    }

    fun clearSource(serverId: String): List<GeofenceCot> = geofenceStore.removeSource(serverId)

    private fun applyGeofence(geofence: GeofenceCot) {
        val sourceId = geofence.sourceServerId ?: NON_TAK_SOURCE
        if (geofence.tracking) {
            val result = geofenceStore.upsertWithResult(geofence)
            Log.d(
                TAG,
                "Map state geofence upsert uid=${geofence.uid} sourceServerId=${geofence.sourceServerId} " +
                    "eventTime=${geofence.eventTime} receivedAt=${geofence.receivedAt} " +
                    "applied=${result.applied} visibleSourceId=${geofenceStore.visibleKey(geofence.uid)?.sourceId} " +
                    "channelOwnership=unavailable"
            )
        } else {
            val removed = geofenceStore.removeIfNotOlder(
                key = SourceCotKey(sourceId, geofence.uid),
                eventTime = geofence.eventTime,
                receivedAt = geofence.receivedAt
            )
            Log.d(
                TAG,
                "Map state geofence stop uid=${geofence.uid} sourceServerId=${geofence.sourceServerId} " +
                    "eventTime=${geofence.eventTime} receivedAt=${geofence.receivedAt} removed=${removed != null} " +
                    "visibleSourceId=${geofenceStore.visibleKey(geofence.uid)?.sourceId} channelOwnership=unavailable"
            )
        }
    }

    private const val TAG = "WearTAK-GeofenceRepository"
}
