package com.aitech.weartak_civ.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedChatPresetsTest {
    private val builtIns = listOf("On my way", "Copy")

    @Test
    fun addNormalizesAndAppends() {
        assertEquals(listOf("Rally at gate"), SavedChatPresets.add(emptyList(), "  Rally   at gate ", builtIns))
        assertEquals(listOf("A", "B"), SavedChatPresets.add(listOf("A"), "B", builtIns))
    }

    @Test
    fun addRejectsDuplicatesBuiltInsInvalidAndFullList() {
        assertNull(SavedChatPresets.add(listOf("Rally at gate"), "RALLY AT GATE", builtIns))
        assertNull(SavedChatPresets.add(emptyList(), "copy", builtIns))
        assertNull(SavedChatPresets.add(emptyList(), "   ", builtIns))
        assertNull(SavedChatPresets.add(emptyList(), "x".repeat(SavedChatPresets.MAX_LENGTH + 1), builtIns))
        assertNull(SavedChatPresets.add((1..SavedChatPresets.MAX_COUNT).map { "Msg $it" }, "Extra", builtIns))
    }

    @Test
    fun removeMatchesCaseInsensitively() {
        assertEquals(listOf("Other"), SavedChatPresets.remove(listOf("Rally at gate", "Other"), " rally AT gate "))
    }
}
