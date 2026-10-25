package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.aitech.weartak_civ.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w227dp-h227dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelfLocationPopupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ownDotPopupShowsResourceHeadingAndUnchangedCoordinates() {
        compose.setContent {
            PopupBox(DpOffset(20.dp, 60.dp), true, 38.0, -77.0, {})
        }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.map_self_location))
            .assertIsDisplayed()
        compose.onNodeWithText("Lat: 38.0\nLon: -77.0", substring = true).assertIsDisplayed()
    }

    @Test fun closedPopupDoesNotAddSelfLabelOrCoordinatesToUnderlyingScreen() {
        compose.setContent {
            PopupBox(DpOffset(20.dp, 60.dp), false, 38.0, -77.0, {})
        }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.map_self_location))
            .assertDoesNotExist()
        compose.onNodeWithText("Lat: 38.0", substring = true).assertDoesNotExist()
    }
}
