package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetRowsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun trashIsSeparateFromSelectionAndOnlyAppearsForCustomRows() {
        val selected = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        compose.setContent {
            WearTAKCIVTheme {
                Column {
                    ManualAlertActionRow("Built in", { selected += "Built in" })
                    ManualAlertActionRow("Custom", { selected += "Custom" },
                        onDelete = { deleted += "Custom" }, allowLongLabel = true)
                }
            }
        }
        compose.onNodeWithContentDescription("Delete Built in").assertDoesNotExist()
        compose.onNodeWithContentDescription("Delete Custom").performClick()
        compose.runOnIdle {
            assertEquals(listOf("Custom"), deleted)
            assertEquals(emptyList<String>(), selected)
        }
        compose.onNodeWithText("Custom").performClick()
        compose.runOnIdle { assertEquals(listOf("Custom"), selected) }
    }

    @Test fun chatTrashOpensConfirmationAndDoesNotSend() {
        val selected = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        compose.setContent {
            WearTAKCIVTheme {
                ChatPresetRows(listOf("Bravo"), listOf("alpha"), selected::add,
                    onAdd = { true }, onDelete = { deleted += it })
            }
        }
        val alpha = compose.onNodeWithText("alpha").fetchSemanticsNode().boundsInRoot
        val bravo = compose.onNodeWithText("Bravo").fetchSemanticsNode().boundsInRoot
        assertTrue(alpha.top < bravo.top)
        compose.onNodeWithContentDescription("Delete alpha").performClick()
        compose.onNodeWithText("Delete saved message?").assertExists()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle {
            assertEquals(listOf("alpha"), deleted)
            assertEquals(emptyList<String>(), selected)
        }
    }

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun largerRoundScreenKeepsLongCustomTextAndTrashIndependentlyAccessible() {
        val text = "Check the north perimeter and report to the command post"
        var selected = false
        var deleted = false
        compose.setContent {
            WearTAKCIVTheme {
                ManualAlertActionRow(text, { selected = true },
                    onDelete = { deleted = true }, allowLongLabel = true)
            }
        }
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete $text").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(deleted)
            assertEquals(false, selected)
        }
    }

    @Test fun alertTrashOpensConfirmationWithoutSendingOrLongPress() {
        val selected = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        compose.setContent {
            WearTAKCIVTheme {
                ManualAlertListScreen(listOf("Bravo"), listOf("alpha"), selected::add,
                    onAddAlert = { true }, onDeleteAlert = { deleted += it }, onCancel = {})
            }
        }
        compose.onNodeWithText("Hold a saved alert", substring = true).assertDoesNotExist()
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasContentDescription("Delete alpha"))
        compose.onNodeWithContentDescription("Delete alpha").performClick()
        compose.onNodeWithText("Delete saved alert?").assertExists()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Delete"))
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle {
            assertEquals(listOf("alpha"), deleted)
            assertEquals(emptyList<String>(), selected)
        }
    }
}
