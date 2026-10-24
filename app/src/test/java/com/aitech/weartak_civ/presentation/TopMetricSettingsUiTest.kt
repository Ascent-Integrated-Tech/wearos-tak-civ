package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopMetricSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun alertThresholdNavigationAndSwitchAreIndependentEvenWhenOff() = verifyAlertControls()

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun largeRoundPreservesIndependentTargets() = verifyAlertControls()

    private fun verifyAlertControls() {
        var enabled = false
        var navigation = 0
        compose.setContent {
            WearTAKCIVTheme { MetricAlertControls(enabled, { enabled = it }, { navigation++ }) }
        }
        compose.onNodeWithContentDescription("Change alert thresholds").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, navigation); assertFalse(enabled) }
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertEquals(1, navigation); assertTrue(enabled) }
    }

    @Test fun selectedMetricRowRemainsRadioSelectableAndShowsDisabled() {
        var selections = 0
        compose.setContent {
            WearTAKCIVTheme {
                Column {
                    TopMetricChoice("Heart Rate", "Disabled", true) { selections++ }
                    TopMetricChoice("Lat/Long", "No fix", false) { selections++ }
                }
            }
        }
        compose.onNodeWithText("Heart Rate").assertIsSelected()
        compose.onNodeWithText("Disabled").assertIsDisplayed()
        compose.onNodeWithText("Lat/Long").performClick()
        compose.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun locationStatusIconOpensExistingReportingRatePageWithoutTogglingLocation() {
        val app = RuntimeEnvironment.getApplication()
        compose.setContent { WearTAKCIVTheme { ReportingRateLocationIcon(false) } }
        compose.onNodeWithContentDescription("Open reporting-rate settings").performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(WearTAKSettingsActivity::class.java.name, intent.component!!.className)
        assertEquals("reporting_strategy", settingsShortcutRoute(intent))
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals("physiological_alerts", settingsShortcutRoute(settingsShortcutIntent(app, "physiological_alerts")))
    }
}
