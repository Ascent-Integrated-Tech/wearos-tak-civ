/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EdgeScoutRepositoryTest {

    @Test
    fun mergeEdgeScoutObservation_preservesExistingFieldsWhenUpdateIsPartial() {
        val firstSeen = 1_000L
        val existing = EdgeScoutDroneSnapshot(
            uasId = "18:65:6A:00:58:5C",
            firstSeenAtMs = firstSeen,
            lastSeenAtMs = firstSeen,
            rssi = -81,
            recvMethod = 16,
            serialNumber = "18656A22620",
            uasType = 2,
            uasLat = 40.0678131,
            uasLon = -74.5392077,
            opLat = 40.0678197,
            opLon = -74.5391308,
            lastRawJson = """{"uasId":"18:65:6A:00:58:5C"}""",
        )

        val update = EdgeScoutRidObservation(
            uasId = "18:65:6A:00:58:5C",
            rssi = -73,
            opId = "",
        )

        val merged = mergeEdgeScoutObservation(
            existing = existing,
            observation = update,
            rawJson = """{"uasId":"18:65:6A:00:58:5C","opId":""}""",
            seenAtMs = 2_000L,
        )

        assertEquals(firstSeen, merged.firstSeenAtMs)
        assertEquals(2_000L, merged.lastSeenAtMs)
        assertEquals(-73, merged.rssi)
        assertEquals(16, merged.recvMethod)
        assertEquals("18656A22620", merged.serialNumber)
        assertEquals(2, merged.uasType)
        assertEquals(40.0678131, merged.uasLat!!, 0.0)
        assertEquals(-74.5392077, merged.uasLon!!, 0.0)
        assertEquals(40.0678197, merged.opLat!!, 0.0)
        assertEquals(-74.5391308, merged.opLon!!, 0.0)
        assertEquals("", merged.opId)
    }

    @Test
    fun mergeEdgeScoutObservation_createsNewSnapshotFromFirstObservation() {
        val observation = EdgeScoutRidObservation(
            uasId = "60:60:1F:C9:B4:07",
            rssi = -54,
            recvMethod = 1,
            remoteId = "RID-1581F5YHX256Q002RRTD",
            serialNumber = "1581F5YHX256Q002RRTD",
            uasType = 2,
            uasLat = 40.0677958,
            uasLon = -74.5391027,
            opLat = 40.0678159,
            opLon = -74.5388251,
        )

        val merged = mergeEdgeScoutObservation(
            existing = null,
            observation = observation,
            rawJson = """{"uasId":"60:60:1F:C9:B4:07"}""",
            seenAtMs = 3_000L,
        )

        assertEquals("60:60:1F:C9:B4:07", merged.uasId)
        assertEquals(3_000L, merged.firstSeenAtMs)
        assertEquals(3_000L, merged.lastSeenAtMs)
        assertEquals(-54, merged.rssi)
        assertEquals("RID-1581F5YHX256Q002RRTD", merged.remoteId)
        assertEquals("1581F5YHX256Q002RRTD", merged.serialNumber)
        assertEquals(2, merged.uasType)
        assertEquals(40.0677958, merged.uasLat!!, 0.0)
        assertEquals(-74.5391027, merged.uasLon!!, 0.0)
        assertEquals(40.0678159, merged.opLat!!, 0.0)
        assertEquals(-74.5388251, merged.opLon!!, 0.0)
        assertNull(merged.opId)
    }
}
