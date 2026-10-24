package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.cot.ManualAlertTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedCustomAlertsTest {
    @Test
    fun normalizeTrimsAndCollapsesWhitespaceAndRejectsInvalidText() {
        assertEquals("North perimeter", SavedCustomAlerts.normalize("  North   perimeter  "))
        assertNull(SavedCustomAlerts.normalize(" \t "))
        assertNull(SavedCustomAlerts.normalize("North\nperimeter"))
        assertNull(SavedCustomAlerts.normalize("x".repeat(SavedCustomAlerts.MAX_LENGTH + 1)))
        assertEquals("x".repeat(SavedCustomAlerts.MAX_LENGTH), SavedCustomAlerts.normalize("x".repeat(SavedCustomAlerts.MAX_LENGTH)))
    }

    @Test
    fun addRejectsDuplicatesBuiltInsOverlongAndFullList() {
        assertEquals(listOf("North perimeter"), SavedCustomAlerts.add(emptyList(), " North perimeter "))
        assertNull(SavedCustomAlerts.add(listOf("North perimeter"), "north PERIMETER"))
        assertNull(SavedCustomAlerts.add(emptyList(), ManualAlertTypes.options.first().displayName.lowercase()))
        assertNull(SavedCustomAlerts.add(emptyList(), "x".repeat(SavedCustomAlerts.MAX_LENGTH + 1)))
        assertNull(SavedCustomAlerts.add((1..SavedCustomAlerts.MAX_COUNT).map { "Alert $it" }, "Extra"))
    }

    @Test
    fun removeMatchesCaseInsensitively() {
        assertEquals(listOf("Other"), SavedCustomAlerts.remove(listOf("North perimeter", "Other"), "NORTH PERIMETER"))
    }

    @Test
    fun mergeValidatesAllIncomingBeforeAppendingAndSkipsDuplicates() {
        val existing = listOf("North perimeter")
        assertEquals(
            listOf("North perimeter", "South perimeter"),
            SavedCustomAlerts.merge(existing, listOf("north perimeter", "South perimeter", "911 ALERT")),
        )
        assertNull(SavedCustomAlerts.merge(existing, listOf("South perimeter", "\u0001invalid")))
        assertNull(
            SavedCustomAlerts.merge(
                (1 until SavedCustomAlerts.MAX_COUNT).map { "Existing $it" },
                listOf("New one", "New two"),
            ),
        )
    }
}