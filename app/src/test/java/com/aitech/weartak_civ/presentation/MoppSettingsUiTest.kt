package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import com.aitech.weartak_civ.data.MoppStatus
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoppSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allLevelsIncludeOneAndOnlyExplicitSavePersists() {
        var saved: MoppStatus? = null
        compose.setContent {
            WearTAKCIVTheme {
                MoppSettingsPage(MoppStatus.NONE, true, rememberNavController()) { saved = it }
            }
        }
        listOf("None", "MOPP 0", "MOPP 1", "MOPP 2", "MOPP 3", "MOPP 4").forEach {
            scrollTo(it)
            compose.onNodeWithText(it).performClick().assertIsOn()
            compose.runOnIdle { assertNull(saved) }
        }
        scrollTo("Save")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(MoppStatus.LEVEL_4, saved) }
        compose.onNodeWithText("MOPP Change Sent").assertDoesNotExist()
    }

    @Test fun discardedCandidateDoesNotBecomeSavedStatus() {
        val visible = mutableStateOf(true)
        var saves = 0
        compose.setContent {
            WearTAKCIVTheme {
                if (visible.value) {
                    MoppSettingsPage(MoppStatus.NONE, true, rememberNavController()) { saves++ }
                }
            }
        }
        scrollTo("MOPP 0")
        compose.onNodeWithText("MOPP 0").performClick()
        compose.onNodeWithContentDescription("Go back to Main Settings Screen").performClick()
        compose.runOnIdle { assertEquals(0, saves); visible.value = false }
        compose.runOnIdle { visible.value = true }
        scrollTo("None")
        compose.onNodeWithText("None").assertIsOn()
    }

    @Test fun disabledPluginKeepsStatusAndSaveFailureIsExplicit() {
        val enabled = mutableStateOf(false)
        compose.setContent {
            WearTAKCIVTheme {
                MoppSettingsPage(MoppStatus.LEVEL_2, enabled.value, rememberNavController()) {
                    throw IOException("disk")
                }
            }
        }
        scrollTo("MOPP 2")
        compose.onNodeWithText("MOPP 2").assertIsOn().assertIsNotEnabled()
        scrollTo("Save")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithText("Save").performClick()
        scrollTo("Could not save PPE status. Try again.")
        compose.onNodeWithText("Could not save PPE status. Try again.").assertIsDisplayed()
    }

    @Test fun dataSyncSplitChipToggleDoesNotNavigateAndNavigationDoesNotToggle() {
        var toggles = 0
        var navigations = 0
        compose.setContent {
            WearTAKCIVTheme {
                WearTAKSplitToggleChip(title = "Data Sync", label = "Paused",
                    checked = false, onCheckedChange = { toggles++ }, onClick = { navigations++ })
            }
        }
        compose.onNodeWithText("Data Sync").performClick()
        compose.runOnIdle { assertEquals(1, navigations); assertEquals(0, toggles) }
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertEquals(1, navigations); assertEquals(1, toggles) }
    }

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }
}
