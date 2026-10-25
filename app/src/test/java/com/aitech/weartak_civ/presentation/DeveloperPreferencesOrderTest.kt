package com.aitech.weartak_civ.presentation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DeveloperPreferencesOrderTest {
    @Test fun diagnosticsAndActionsSortWithoutLosingTheirAssociations() {
        val entries = listOf(
            DeveloperEntry(DeveloperEntryId.STAY_AWAKE, "Stay awake"),
            DeveloperEntry(DeveloperEntryId.VERSION, "Version", "5.8"),
            DeveloperEntry(DeveloperEntryId.LOCATION, "Location Diagnostics"),
            DeveloperEntry(DeveloperEntryId.DIAGNOSTICS, "Diagnostics"),
            DeveloperEntry(DeveloperEntryId.BUILD, "Build", "release")
        )
        val sorted = sortedDeveloperEntries(entries, Locale.ENGLISH)
        assertEquals(listOf("Build", "Diagnostics", "Location Diagnostics", "Stay awake", "Version"),
            sorted.map { it.label })
        assertEquals(entries.toSet(), sorted.toSet())
        assertEquals(DeveloperEntryId.DIAGNOSTICS, sorted[1].id)
        assertEquals("release", sorted.first().value)
    }

    @Test fun localizedLabelsDetermineOrderRatherThanEnglishOrEnumPosition() {
        val entries = listOf(
            DeveloperEntry(DeveloperEntryId.DIAGNOSTICS, "\u00c4"),
            DeveloperEntry(DeveloperEntryId.STAY_AWAKE, "Z"),
            DeveloperEntry(DeveloperEntryId.BUILD, "A")
        )
        assertEquals(listOf("A", "Z", "\u00c4"),
            sortedDeveloperEntries(entries, Locale.forLanguageTag("sv")).map { it.label })
        assertEquals(listOf("A", "\u00c4", "Z"),
            sortedDeveloperEntries(entries, Locale.GERMAN).map { it.label })
    }
}
