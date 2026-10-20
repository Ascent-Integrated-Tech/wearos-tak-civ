package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import com.aitech.weartak_civ.presentation.viewmodel.RouteDetailsView
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
class RouteDetailsViewTest {
    @get:Rule val compose = createComposeRule()
    private val route = TakRoute("route", "Test route", listOf(
        TakRoutePoint(34.0, -117.0, name = "Start"),
        TakRoutePoint(34.5, -117.5),
        TakRoutePoint(35.0, -118.0, name = "Finish")
    ), "server")

    @Test fun wholeRouteRemovalRequiresConfirmationAndCanBeCancelled() {
        var removed = 0
        compose.setContent { RouteDetailsView(route, {}, { removed++ }, {}) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("route-remove"))
        compose.onNodeWithTag("route-remove").performClick()
        compose.runOnIdle { assertEquals(0, removed) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("route-remove-cancel"))
        compose.onNodeWithTag("route-remove-cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, removed) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("route-remove"))
        compose.onNodeWithTag("route-remove").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("route-remove-confirm"))
        compose.onNodeWithTag("route-remove-confirm").performClick()
        compose.runOnIdle { assertEquals(1, removed) }
    }

    @Test fun routeListIncludesUnnamedPointsAndSelectsOriginalIndex() {
        val selected = mutableListOf<Int>()
        compose.setContent { RouteDetailsView(route, { selected += it }, {}, {}) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("route-point-1"))
        compose.onNodeWithTag("route-point-1").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(1), selected) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("route-point-2"))
        compose.onNodeWithTag("route-point-2").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(1, 2), selected) }
    }

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun routeActionsFitLargerWatchAndBackDoesNotRemove() {
        var removed = 0
        var backed = 0
        compose.setContent { RouteDetailsView(route, {}, { removed++ }, { backed++ }) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Back"))
        compose.onNodeWithText("Back").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, backed)
            assertEquals(0, removed)
        }
    }
}
