package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository.LocationSnapshot
import org.junit.Assert.*
import org.junit.Test

class EmergencyLocationPolicyTest {
    private val fix = LocationSnapshot(1000, 41.0, -87.0, 100.0, 4f, 5f, null, null)

    @Test fun freshAndOldFixesKeepTheirCoordinatesAndOriginalTimestamp() {
        assertEquals(fix, EmergencyLocationPolicy.forDispatch(fix, 4000))
        val old = EmergencyLocationPolicy.forDispatch(fix, 61_000)!!
        assertEquals(fix.timeMillis, old.timeMillis)
        assertEquals(41.0, old.lat, 0.0)
        assertEquals(289f, old.ce!!, 0f)
        assertNotNull(EmergencyLocationPolicy.forDispatch(fix, 86_400_000))
    }

    @Test fun missingInvalidFixesAndAccuracyAreNotClaimedAsFreshOrPrecise() {
        assertNull(EmergencyLocationPolicy.forDispatch(null, 1000))
        assertNull(EmergencyLocationPolicy.forDispatch(fix.copy(lat = Double.NaN), 1000))
        assertNull(EmergencyLocationPolicy.forDispatch(fix.copy(timeMillis = 0), 1000))
        assertEquals(9_999_999f, EmergencyLocationPolicy.forDispatch(fix.copy(ce = null), 1000)!!.ce!!, 0f)
        assertEquals(9_999_999f, EmergencyLocationPolicy.forDispatch(fix.copy(ce = Float.NaN), 1000)!!.ce!!, 0f)
        assertNotNull(EmergencyLocationPolicy.forDispatch(fix.copy(lat = 0.0, lon = 0.0), 1000))
    }
}
