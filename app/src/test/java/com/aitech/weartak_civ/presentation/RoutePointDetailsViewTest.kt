package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.presentation.viewmodel.RoutePointsDetailsView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w192dp-h192dp-round", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutePointDetailsViewTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun standardPointActionAndWholeRouteRemainReachableOnSmallWatch() {
        var bloodhound = 0
        var wholeRoute = 0
        compose.setContent {
            RoutePointsDetailsView(
                "Point 1235", 34.0, -117.0,
                onBloodhound = { bloodhound++ },
                onWholeRoute = { wholeRoute++ },
                onBack = {}
            )
        }
        compose.onNodeWithText("Bloodhound to Marker").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, bloodhound) }
        compose.onNodeWithText("Whole route").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, wholeRoute) }
    }

    @Test
    fun activePointUsesSameInPositionActionAsStandardMarker() {
        var position = 0
        var bloodhound = 0
        compose.setContent {
            RoutePointsDetailsView(
                "Start", 34.0, -117.0, isBloodhounding = true,
                onBloodhound = { bloodhound++ }, onInPosition = { position++ }, onBack = {}
            )
        }
        compose.onNodeWithText("Bloodhound to Marker").assertDoesNotExist()
        compose.onNodeWithText("nPos")
            .performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, position)
            assertEquals(0, bloodhound)
        }
    }
}
