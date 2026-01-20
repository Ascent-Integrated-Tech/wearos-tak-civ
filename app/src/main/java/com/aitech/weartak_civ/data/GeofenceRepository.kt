/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.data.model.GeofenceCot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow


object GeofenceRepository {
    // Buffered so producer doesn't block on brief bursts
    private val geofenceCotChannel = Channel<GeofenceCot>(capacity = Channel.BUFFERED)

    val geofenceCotFlow: Flow<GeofenceCot> = geofenceCotChannel.receiveAsFlow()

    fun trySendGeofenceCot(geofence: GeofenceCot): Boolean {
        return geofenceCotChannel.trySend(geofence).isSuccess
    }

    suspend fun sendGeofenceCot(geofence: GeofenceCot) {
        geofenceCotChannel.send(geofence)
    }
}