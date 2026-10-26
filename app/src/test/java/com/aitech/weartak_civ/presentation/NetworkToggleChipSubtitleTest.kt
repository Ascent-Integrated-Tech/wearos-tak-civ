package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkToggleChipSubtitleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rowStatusDefaultsToSubtitleAndCanBeAccessibilityOnly() {
        assertEquals("1/6", NetworkConnectivityRow("a", "A", "1/6", true, {}).statusDescription)
        val row = NetworkConnectivityRow("b", "B", "", true, {}, statusDescription = "Enabled")
        assertEquals("", row.subtitle)
        assertEquals("Enabled", row.statusDescription)
    }

    @Test fun emptySubtitleShowsNoSecondaryTextButKeepsStatusForAccessibility() {
        compose.setContent {
            WearTAKToggleChip(true, {}, title = "TAK SA Multicast", description = "", statusDescription = "Enabled")
        }
        compose.onNodeWithText("TAK SA Multicast").assertIsDisplayed()
        compose.onNodeWithText("Enabled").assertDoesNotExist()
        val node = compose.onNode(hasText("TAK SA Multicast")).fetchSemanticsNode()
        assertEquals("Enabled", node.config[SemanticsProperties.StateDescription])
    }

    @Test fun serverCountIsShownAsOnlySubtitle() {
        compose.setContent {
            WearTAKToggleChip(true, {}, title = "TAK Servers", description = "1/6", statusDescription = "Connected · 1/6 Servers")
        }
        compose.onNodeWithText("1/6").assertIsDisplayed()
    }
}
