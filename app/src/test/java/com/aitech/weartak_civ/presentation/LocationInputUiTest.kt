package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.aitech.weartak_civ.data.LocationInput
import com.aitech.weartak_civ.data.LocationInputStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w300dp-h300dp-round-340dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocationInputUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun at480Font10() = verifySelector(480, 1f)
    @Test fun at480Font11() = verifySelector(480, 1.1f)
    @Test fun at480Font13() = verifySelector(480, 1.3f)
    @Test fun at498Font10() = verifySelector(498, 1f)
    @Test fun at498Font11() = verifySelector(498, 1.1f)
    @Test fun at498Font13() = verifySelector(498, 1.3f)

    private fun verifySelector(pixels: Int, fontScale: Float) {
        val density = 340f / 160
        val selected = mutableStateOf(LocationInput.INTERNAL_GNSS)
        val nav = mockk<NavHostController>(relaxed = true)
        var pops = 0
        every { nav.popBackStack() } answers { pops++; true }
        var fail = true
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                Box(Modifier.requiredSize((pixels / density).dp)) {
                    LocationInputSettingsPage(selected.value, LocationInputStatus.UNAVAILABLE, false, nav) {
                        if (fail) false else { selected.value = it; true }
                    }
                }
            }
        }
        fun reveal(text: String) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("$text width at $pixels/$fontScale", layouts.single().didOverflowWidth)
            assertFalse("$text height at $pixels/$fontScale", layouts.single().didOverflowHeight)
        }
        reveal("Location Input")
        reveal("Selected input unavailable on this watch")
        reveal("Internal GNSS")
        compose.onNodeWithText("Internal GNSS").assertIsSelected()
        reveal("LTE/WiFi Derived")
        compose.onNodeWithText("LTE/WiFi Derived").assertIsNotSelected().performClick()
        reveal("Could not save Location Input. Try again.")
        assertEquals(0, pops)
        compose.runOnIdle { fail = false }
        reveal("LTE/WiFi Derived")
        compose.onNodeWithText("LTE/WiFi Derived").performClick()
        compose.waitForIdle()
        assertEquals(LocationInput.NETWORK, selected.value)
        assertEquals(1, pops)
    }
}
