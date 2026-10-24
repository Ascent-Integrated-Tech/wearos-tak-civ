package com.aitech.weartak_civ.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualAlertListScreenTest {
    @Test
    fun labelFontShrinksUntilTheWholeLabelFits() {
        val size = chooseAlertFontSize("abcdef", availableWidthPx = 75) { fontSize -> fontSize * 6 }

        assertEquals(12, size)
    }

    @Test
    fun labelFontStopsAtSmallestSupportedSizeWhenTextStillDoesNotFit() {
        val size = chooseAlertFontSize("long custom label", availableWidthPx = 10) { fontSize -> fontSize * 8 }

        assertEquals(8, size)
    }
}