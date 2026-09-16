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
import com.aitech.weartak_civ.data.model.GeofenceTrigger
import com.aitech.weartak_civ.data.model.LatLon
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GeofenceRepositorySourceTest {
    @Test
    fun clearingNewestSourceRevealsTheRemainingGeofenceCopy() = runTest {
        try {
            GeofenceRepository.sendGeofenceCot(geofence(SERVER_A, "Alpha", 100L))
            GeofenceRepository.sendGeofenceCot(geofence(SERVER_B, "Bravo", 200L))

            assertEquals("Bravo", GeofenceRepository.geofenceCotFlow.value.single().callsign)

            GeofenceRepository.clearSource(SERVER_B)

            assertEquals("Alpha", GeofenceRepository.geofenceCotFlow.value.single().callsign)
        } finally {
            GeofenceRepository.clearSource(SERVER_A)
            GeofenceRepository.clearSource(SERVER_B)
        }
    }

    private fun geofence(serverId: String, callsign: String, receivedAt: Long) = GeofenceCot(
        uid = "same-uid",
        type = "u-d-r",
        callsign = callsign,
        points = listOf(LatLon(41.0, -87.0), LatLon(41.1, -87.0), LatLon(41.1, -87.1)),
        tracking = true,
        trigger = GeofenceTrigger.Entry,
        sourceServerId = serverId,
        receivedAt = receivedAt
    )

    private companion object {
        const val SERVER_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SERVER_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
