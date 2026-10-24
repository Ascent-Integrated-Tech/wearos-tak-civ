package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w300dp-h300dp-round-340dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopMetricRecoveryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeAndMonitorAt480FontScale13SurviveRecreation() = verifySurfaces(480, 1.3f)
    @Test fun homeAndMonitorAt498FontScale11SurviveRecreation() = verifySurfaces(498, 1.1f)
    @Test fun homeAndMonitorAt480NormalFontSurviveRecreation() = verifySurfaces(480, 1f)
    @Test fun homeAndMonitorAt498NormalFontSurviveRecreation() = verifySurfaces(498, 1f)
    @Test fun homeHeartRateAt480FontScale13DoesNotClip() = verifySurfaces(480, 1.3f, true)

    private fun verifySurfaces(pixels: Int, fontScale: Float, onlyHeartRate: Boolean = false) {
        val density = 340f / 160
        val restored = StateRestorationTester(compose)
        val coordinate = "-79.12345/-179.98765"
        val grid = "60XWU1234598765"
        restored.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                WearTAKCIVTheme {
                    Column(Modifier.requiredWidth((pixels / density).dp).verticalScroll(rememberScrollState())) {
                        Box(Modifier.height((pixels / density).dp).padding(5.dp)) {
                          HomeDashboardColumn {
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                HomeTopMetricCard("Heart Rate", "105bpm", TopDisplayMetric.HEART_RATE,
                                    R.drawable.heart_rate, Utils.AlertChipState.None, null, {})
                            }
                            repeat(2) { Spacer(Modifier.weight(1f)) }
                            Spacer(Modifier.height(4.dp))
                            Spacer(Modifier.height(48.dp))
                          }
                        }
                        if (!onlyHeartRate) {
                            Box(Modifier.height((pixels / density).dp).padding(5.dp)) {
                              HomeDashboardColumn {
                                Row(Modifier.fillMaxWidth().weight(1f)) {
                                    HomeTopMetricCard("Lat/Long", coordinate, TopDisplayMetric.LAT_LONG,
                                        R.drawable.heart_rate, Utils.AlertChipState.None, null, {})
                                }
                                repeat(2) { Spacer(Modifier.weight(1f)) }
                                Spacer(Modifier.height(4.dp))
                                Spacer(Modifier.height(48.dp))
                              }
                            }
                            Row(Modifier.width(IntrinsicSize.Max)) {
                                TopMetricChoice("MGRS", grid, true) {}
                            }
                            Row(Modifier.width(IntrinsicSize.Max)) {
                                TopMetricChoice("Heart Rate", "Off-Wrist", false) {}
                            }
                        }
                    }
                }
            }
        }
        fun assertMetrics() {
            val values = if (onlyHeartRate) listOf("105bpm")
                else listOf("105bpm", coordinate, "MGRS $grid", "Heart Rate Off-Wrist")
            for (value in values) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(value)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertEquals(value, 1, layout.lineCount)
                assertFalse("$value horizontal overflow at $pixels/$fontScale", layout.didOverflowWidth)
                assertFalse("$value vertical overflow at $pixels/$fontScale", layout.didOverflowHeight)
                assertFalse(value, layout.isLineEllipsized(0))
            }
        }
        assertMetrics()
        restored.emulateSavedInstanceStateRestore()
        assertMetrics()
    }
}
