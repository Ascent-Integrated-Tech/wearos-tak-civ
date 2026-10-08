package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.cot.ManualAlertTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class TextSelectionTest {
    @Test
    fun everyManualPresetSubmitsItsExactTextWithoutAppendingDraftText() {
        ManualAlertTypes.options.forEach { option ->
            val submissions = mutableListOf<String>()
            val selected = selectTextOption("unfinished custom text", option.displayName) {
                submissions.add(it)
            }

            assertEquals(option.displayName, selected)
            assertEquals(listOf(option.displayName), submissions)
        }
    }

    @Test
    fun textPickerWithoutImmediateCallbackStillAppendsWithoutSubmitting() {
        assertEquals("Hello Roger", selectTextOption("Hello", "Roger", null))
        assertEquals(" Roger", selectTextOption("", "Roger", null))
    }

    @Test
    fun chatPresetSubmitsOnlyTheSelectedReply() {
        val submissions = mutableListOf<String>()
        val selected = selectTextOption("unfinished draft", "Roger") {
            submissions.add(it)
        }

        assertEquals("Roger", selected)
        assertEquals(listOf("Roger"), submissions)
    }
}
