package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.TopDisplayMetric
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w300dp-h300dp-round-340dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhysioControlsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun at480Font10() = verifySurfaces(480, 1f)
    @Test fun at480Font11() = verifySurfaces(480, 1.1f)
    @Test fun at480Font13() = verifySurfaces(480, 1.3f)
    @Test fun at498Font10() = verifySurfaces(498, 1f)
    @Test fun at498Font11() = verifySurfaces(498, 1.1f)
    @Test fun at498Font13() = verifySurfaces(498, 1.3f)

    private fun verifySurfaces(pixels: Int, fontScale: Float) {
        val density = 340f / 160
        val monitoring = mutableStateOf(true)
        val alerting = mutableStateOf(false)
        val metric = mutableStateOf<TopDisplayMetric?>(null)
        val warning = mutableStateOf(false)
        var thresholdOpens = 0
        val restoration = StateRestorationTester(compose)
        val context = RuntimeEnvironment.getApplication()
        val monitorLabel = context.getString(R.string.physiological_monitoring)
        val alertsLabel = context.getString(R.string.physiological_alerts)
        val thresholdsLabel = context.getString(R.string.metric_alert_thresholds)
        val iconLabel = context.getString(R.string.top_display_metric)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                WearTAKCIVTheme {
                    Box(Modifier.requiredSize((pixels / density).dp)) {
                        if (metric.value == null) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 32.dp)
                                .verticalScroll(rememberScrollState())) {
                                MetricMonitoringControls(monitoring.value, alerting.value,
                                    { monitoring.value = it }, { alerting.value = it }, { thresholdOpens++ })
                                TopMetricChoice("Heart Rate", "Off-Wrist", true) {}
                            }
                        } else {
                            val selected = metric.value!!
                            Box(Modifier.fillMaxSize().padding(5.dp)) {
                                HomeDashboardColumn {
                                    Row(Modifier.fillMaxWidth().weight(1f)) {
                                        HomeTopMetricCard(selected.name, when (selected) {
                                            TopDisplayMetric.MGRS -> "60XWU1234598765"
                                            TopDisplayMetric.LAT_LONG -> "-79.12345/-179.98765"
                                            else -> "112bpm"
                                        }, selected, if (warning.value) R.drawable.warning else selected.icon,
                                            Utils.AlertChipState.None,
                                            if (warning.value) "Warning" else null, {})
                                    }
                                    repeat(2) { Spacer(Modifier.weight(1f)) }
                                    Spacer(Modifier.height(48.dp))
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        fun assertTextFits(label: String) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("$label clipped vertically at $pixels/$fontScale", layouts.single().didOverflowHeight)
            assertFalse("$label clipped horizontally at $pixels/$fontScale", layouts.single().didOverflowWidth)
        }
        assertTextFits(monitorLabel)
        assertTextFits(alertsLabel)
        val monitorBounds = compose.onNodeWithText(monitorLabel).fetchSemanticsNode().boundsInRoot
        val alertBounds = compose.onNodeWithText(alertsLabel).fetchSemanticsNode().boundsInRoot
        assertTrue("Monitoring must lead", monitorBounds.top < alertBounds.top)
        compose.onNodeWithContentDescription(thresholdsLabel).performClick()
        assertEquals(1, thresholdOpens)
        assertFalse(alerting.value)
        compose.onAllNodes(isToggleable())[1].performClick()
        assertTrue(alerting.value)
        assertTrue(monitoring.value)
        compose.onNodeWithText(monitorLabel).performClick()
        assertFalse(monitoring.value)
        assertTrue(alerting.value)
        restoration.emulateSavedInstanceStateRestore()
        assertFalse(monitoring.value)
        assertTrue(alerting.value)
        compose.runOnIdle { metric.value = TopDisplayMetric.MGRS }
        compose.onNodeWithContentDescription(iconLabel).assertDoesNotExist()
        assertTextFits("60XWU1234598765")
        compose.runOnIdle { metric.value = TopDisplayMetric.LAT_LONG }
        compose.onNodeWithContentDescription(iconLabel).assertExists()
        assertTextFits("-79.12345/-179.98765")
        compose.runOnIdle { metric.value = TopDisplayMetric.HEART_RATE }
        assertEquals(R.drawable.heart, TopDisplayMetric.HEART_RATE.icon)
        compose.onNodeWithContentDescription(iconLabel).assertExists()
        assertTextFits("112bpm")
        compose.runOnIdle { metric.value = TopDisplayMetric.MGRS; warning.value = true }
        compose.onNodeWithContentDescription(iconLabel).assertExists()
        compose.onNodeWithText("Warning").assertExists()
    }
}
