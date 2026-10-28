package com.aitech.weartak_civ.presentation

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommandCallsignEntryUiTest {
    @get:Rule val compose = createComposeRule()
    private val writes = mutableListOf<List<String>>()
    private lateinit var nav: NavHostController

    private fun showEntry() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "allowed") {
                composable("allowed") {
                    WearTAKTitleChip(
                        title = "Add", route = "add", navController = nav
                    )
                }
                composable("add") { CommandCallsignEntryPage(nav, listOf("ZULU"), onSave = writes::add) }
            }
        }
        compose.onNodeWithText("Add").performClick()
    }

    @Test fun smallRoundKeyboardDoneAddsAndReturnsWithoutAnAddDialog() = keyboardDoneAdds()

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun largerRoundKeyboardDoneAddsAndReturnsWithoutAnAddDialog() = keyboardDoneAdds()

    private fun keyboardDoneAdds() {
        showEntry()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput(" alpha-1 ")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil { writes.isNotEmpty() }
        assertEquals(listOf(listOf("ALPHA-1", "ZULU")), writes)
        compose.onNodeWithText("Add").assertIsDisplayed()
    }

    @Test fun confirmButtonUsesTheSameValidationAndCancelNeverWrites() {
        showEntry()
        for (value in listOf("", " ", "BAD!", "A".repeat(33), " zulu ")) {
            compose.onNode(hasSetTextAction()).performTextReplacement(value)
            compose.onNodeWithContentDescription("Confirm icon").performClick()
            compose.waitForIdle()
            assertTrue(writes.isEmpty())
            assertEquals("add", nav.currentDestination?.route)
            assertNotNull(ShadowToast.getTextOfLatestToast())
        }
        assertEquals("That callsign is already allowed", ShadowToast.getTextOfLatestToast())
        compose.onNode(hasSetTextAction()).performTextReplacement("BRAVO")
        compose.onNodeWithContentDescription("Close icon").performClick()
        compose.onNodeWithText("Add").assertIsDisplayed()
        assertTrue(writes.isEmpty())
        compose.onNodeWithText("Add").performClick()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText,
            androidx.compose.ui.text.AnnotatedString("")
        ))
        compose.onNode(hasSetTextAction()).performTextInput("B".repeat(32))
        compose.onNodeWithContentDescription("Confirm icon").performClick()
        assertEquals(listOf(listOf("B".repeat(32), "ZULU")), writes)
    }

    @Test fun invalidKeyboardDoneDoesNotSaveOrNavigateAway() {
        showEntry()
        compose.onNode(hasSetTextAction()).performTextInput("BAD!")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        assertTrue(writes.isEmpty())
        assertEquals("add", nav.currentDestination?.route)
        compose.onNode(hasSetTextAction()).performTextReplacement(" zulu ")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        assertTrue(writes.isEmpty())
        assertEquals("That callsign is already allowed", ShadowToast.getTextOfLatestToast())
    }

    private fun showEditableEntry(callsign: String = "Alpha One") {
        compose.setContent {
            nav = rememberNavController()
            val callsigns = listOf(callsign, "ZULU")
            NavHost(nav, startDestination = "allowed") {
                composable("allowed") {
                    Column {
                        CommandCallsignRow(
                            callsign,
                            onEdit = { nav.navigate("edit/${Uri.encode(callsign)}") },
                            onRemove = { writes.add(listOf("ZULU")) }
                        )
                    }
                }
                composable("edit/{callsign}") { entry ->
                    CommandCallsignEntryPage(
                        nav, callsigns,
                        originalCallsign = requireNotNull(entry.arguments?.getString("callsign")),
                        onSave = writes::add
                    )
                }
            }
        }
    }

    @Test fun tappingExistingCallsignFocusesPrefilledEditorAndDoneReplacesOnlyThatEntry() {
        showEditableEntry()
        compose.onNodeWithText("Alpha One").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused().assertTextContains("Alpha One")
        compose.onNode(hasSetTextAction()).performTextReplacement(" bravo-2 ")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithText("Alpha One").assertIsDisplayed()
        assertEquals(listOf(listOf("BRAVO-2", "ZULU")), writes)
    }

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun editingRejectsInvalidAndOtherDuplicateButAcceptsOwnNameWithoutChangingStoredCase() {
        showEditableEntry()
        compose.onNodeWithText("Alpha One").performClick()
        for (value in listOf("", "BAD!", "A".repeat(33), " zulu ")) {
            compose.onNode(hasSetTextAction()).performTextReplacement(value)
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitForIdle()
            assertTrue(writes.isEmpty())
            assertEquals("edit/{callsign}", nav.currentDestination?.route)
        }
        assertEquals("That callsign is already allowed", ShadowToast.getTextOfLatestToast())
        compose.onNode(hasSetTextAction()).performTextReplacement(" alpha one ")
        compose.onNodeWithContentDescription("Confirm icon").performClick()
        assertEquals(listOf(listOf("Alpha One", "ZULU")), writes)
    }

    @Test fun cancelAndBackDiscardEditsAndReopeningRestoresOriginalValue() {
        showEditableEntry()
        compose.onNodeWithText("Alpha One").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("BRAVO")
        compose.onNodeWithContentDescription("Close icon").performClick()
        assertTrue(writes.isEmpty())
        compose.onNodeWithText("Alpha One").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Alpha One")
        compose.onNode(hasSetTextAction()).performTextReplacement("CHARLIE")
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("Alpha One").assertIsDisplayed()
        assertTrue(writes.isEmpty())
    }

    @Test fun deleteControlRemovesWithoutOpeningEditor() {
        showEditableEntry()
        compose.onNodeWithContentDescription("Remove Alpha One").performClick()
        assertEquals(listOf(listOf("ZULU")), writes)
        assertEquals("allowed", nav.currentDestination?.route)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun legacyCallsignWithRouteCharactersOpensWithoutChangingItsValue() {
        val legacy = "ALPHA/ONE%?#"
        showEditableEntry(legacy)
        compose.onNodeWithText(legacy).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains(legacy)
        compose.onNodeWithContentDescription("Close icon").performClick()
        assertTrue(writes.isEmpty())
    }
}
