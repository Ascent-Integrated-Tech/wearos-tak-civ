package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.data.TopDisplayMetric
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

    @Test fun selectedMetricRowsKeepNameAndLiveValueInlineAndRemainRadioSelectable() {
        var selections = 0
        compose.setContent {
            WearTAKCIVTheme {
                Column {
                    TopMetricChoice("Heart Rate", "Disabled", true) { selections++ }
                    TopMetricChoice("Lat/Long", "No fix", false) { selections++ }
                }
            }
        }
        compose.onNodeWithText("Heart Rate Disabled").assertIsSelected()
        compose.onNodeWithText("Disabled").assertDoesNotExist()
        compose.onNodeWithText("Off-Wrist").assertDoesNotExist()
        compose.onNodeWithText("No fix").assertDoesNotExist()
        compose.onNodeWithText("Lat/Long No fix").performClick()
        compose.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun fullPrecisionCoordinatesFitNarrowReadoutOnOneLine() {
        val values = listOf("-79.12345/-179.98765", "60XWU1234598765",
            "Lat/Long -79.12345/-179.98765", "MGRS 60XWU1234598765", "Exertion 45%",
            "Heart Rate Off-Wrist", "Skin Temp Disabled")
        compose.setContent {
            WearTAKCIVTheme {
                Column {
                    values.forEach { value ->
                        Box(Modifier.width(96.dp)) { FittedTopMetricText(value) }
                    }
                }
            }
        }
        values.forEach { value ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(value).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertFalse(layouts.single().didOverflowWidth)
            assertFalse(layouts.single().isLineEllipsized(0))
        }
    }

    @Test fun locationStatusIconOpensExistingReportingRatePageWithoutTogglingLocation() {
        val app = RuntimeEnvironment.getApplication()
        compose.setContent { WearTAKCIVTheme { ReportingRateLocationIcon(false) } }
        compose.onNodeWithContentDescription("Open Reporting").performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(WearTAKSettingsActivity::class.java.name, intent.component!!.className)
        assertEquals("reporting_strategy", settingsShortcutRoute(intent))
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals("physiological_alerts", settingsShortcutRoute(settingsShortcutIntent(app, "physiological_alerts")))
    }

    @Test fun onlyNonAlertCoordinateMetricsOpenReporting() {
        val app = RuntimeEnvironment.getApplication()
        for (metric in TopDisplayMetric.entries) {
            for (alert in listOf(false, true)) {
                val intent = homeMetricIntent(app, metric, alert)
                if (!alert && metric in listOf(TopDisplayMetric.MGRS, TopDisplayMetric.LAT_LONG)) {
                    assertEquals(WearTAKSettingsActivity::class.java.name, intent.component!!.className)
                    assertEquals("reporting_strategy", settingsShortcutRoute(intent))
                } else {
                    assertEquals(PhysioAlertsActivity::class.java.name, intent.component!!.className)
                    assertNull(settingsShortcutRoute(intent))
                }
            }
        }
    }
}
