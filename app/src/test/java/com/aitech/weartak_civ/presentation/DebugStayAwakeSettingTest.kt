package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.service.DebugStayAwakePreferences
import com.aitech.weartak_civ.service.DebugStayAwakeService
import org.junit.Assert.*
import org.junit.Before
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
class DebugStayAwakeSettingTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun resetPreference() {
        app.getSharedPreferences("debug_stay_awake", Application.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun smallRoundHasOneTopSplitChipWithReadOnlyDetails() = verifyTopSplitChip()

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun largerRoundHasOneTopSplitChipWithReadOnlyDetails() = verifyTopSplitChip()

    private fun verifyTopSplitChip() {
        compose.setContent {
            DebugInformationPage(
                DebugDisplayInfo("test", 1, "debug", "test", "test", "WIFI", "test", "N/A", 8000),
                onBack = {}, onShowQrCode = {}, onShowLocationDiagnostics = {}
            )
        }
        compose.onNodeWithText("Stay awake").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Resume after update").assertDoesNotExist()
        compose.onNodeWithText("Stay awake").performClick()
        compose.runOnIdle { assertFalse(DebugStayAwakePreferences(app).enabled) }
        compose.onNode(isToggleable()).performClick()
        compose.waitUntil { DebugStayAwakePreferences(app).enabled }
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Enabled, not running").assertExists()
        val start = shadowOf(app).nextStartedService
        assertEquals(DebugStayAwakeService::class.java.name, start?.component?.className)
        assertEquals(DebugStayAwakeService.ACTION_RESTORE, start?.action)
        assertNull(shadowOf(app).nextStartedService)
        compose.onNode(isToggleable()).performClick()
        compose.waitUntil { !DebugStayAwakePreferences(app).enabled }
        compose.onNode(isToggleable()).assertIsOff()
        val stopped = shadowOf(app).nextStoppedService
        assertEquals(DebugStayAwakeService::class.java.name, stopped?.component?.className)
    }

    @Test fun savedOnIsNotShownAsOffWhenAndroidHasNotStartedService() {
        DebugStayAwakePreferences(app).recordSession(true)
        compose.setContent { DebugStayAwakeSetting() }
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Enabled, not running").assertIsDisplayed()
        compose.onNodeWithText("Stay awake").performClick()
        compose.onNodeWithText(
            "Keeps screen, CPU and Wi-Fi awake when Android allows. Your choice survives updates and restart until you turn it off. Cannot unlock the watch or keep it awake while powered off."
        ).assertExists()
        compose.runOnIdle { assertTrue(DebugStayAwakePreferences(app).enabled) }
        compose.onNodeWithText("Extra battery use. May heat watch.").assertExists()
    }

    @Test fun externalStopUpdatesSwitchWithoutReopeningPage() {
        DebugStayAwakePreferences(app).recordSession(true)
        compose.setContent { DebugStayAwakeSetting() }
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle { DebugStayAwakeService.stop(app) }
        compose.waitForIdle()
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Off").assertIsDisplayed()
    }
}
