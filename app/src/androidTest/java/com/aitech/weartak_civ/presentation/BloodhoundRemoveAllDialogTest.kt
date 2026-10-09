package com.aitech.weartak_civ.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BloodhoundRemoveAllDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun removeAllAndCancelAreSeparateActions() {
        var removals = 0
        var cancellations = 0
        compose.setContent {
            WearTAKCIVTheme {
                BloodhoundRemoveAllDialog(
                    shouldShowDialog = remember { mutableStateOf(true) },
                    onRemoveAll = { removals++ },
                    onCancel = { cancellations++ }
                )
            }
        }
        compose.onNodeWithText("Remove All").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(0, removals)
            assertEquals(1, cancellations)
        }
        compose.onNodeWithText("Remove All").performClick()
        compose.runOnIdle { assertEquals(1, removals) }
    }

    @Test
    fun disabledRemovalStillAllowsCancel() {
        var cancellations = 0
        compose.setContent {
            WearTAKCIVTheme {
                BloodhoundRemoveAllDialog(
                    shouldShowDialog = remember { mutableStateOf(true) },
                    onRemoveAll = { error("Disabled removal must not run") },
                    onCancel = { cancellations++ },
                    enabled = false
                )
            }
        }
        compose.onNodeWithText("Remove All").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancellations) }
    }
}
