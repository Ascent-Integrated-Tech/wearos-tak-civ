package com.aitech.weartak_civ.cot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualAlertTypesTest {
    @Test
    fun manualOptionsIncludeExactLabelsInAlphabeticalOrder() {
        assertEquals(
            listOf(
                "911 Alert",
                "Gate Runner",
                "Geofence Breached",
                "Gunshot",
                "In Contact",
                "Injury",
                "Ring The Bell",
                "UAS",
                "Vehicle",
            ),
            ManualAlertTypes.options.map(ManualAlertOption::displayName),
        )
        assertTrue(ManualAlertTypes.options.map(ManualAlertOption::displayName).toSet().size == 9)
    }
}
