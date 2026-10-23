package com.aitech.weartak_civ.presentation

import android.app.Application
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
                composable("add") { CommandCallsignEntryPage(nav, listOf("ZULU"), writes::add) }
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
}
