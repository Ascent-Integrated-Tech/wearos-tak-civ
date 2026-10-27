package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.data.CompanionAppRepository.BleConnectionState
import com.aitech.weartak_civ.data.DataRepository.SitxServerStatus
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionStatusIconsTest {
    @get:Rule val compose = createComposeRule()

    private val active = ConnectionRouteSnapshot(
        dittoEnabled = true, dittoSyncActive = true, companionEnabled = true,
        companionBle = BleConnectionState.CONNECTING, takEnabled = true,
        takServers = listOf(RouteTakServerStatus(true, "CONNECTED"), RouteTakServerStatus(true)),
        multicastEnabled = true, multicastRunning = true, sitxEnabled = true,
        sitxState = SitxServerStatus.CONNECTING
    )
    private val routeDescriptions = listOf("Ditto Edge Sync: Connected", "WearTAK Companion: Connecting",
        "TAK Servers: 1 of 2 connected", "Multicast: Connected", "SITX: Connecting")

    @Test fun allRoutesFitSmallRound() = verifyRound(192)

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun allRoutesFitLargerRound() = verifyRound(227)

    private fun verifyRound(size: Int) {
        var density = 1f
        var opens = 0
        compose.setContent {
            density = LocalDensity.current.density
            WearTAKCIVTheme {
                Box(Modifier.requiredSize(size.dp).padding(5.dp)) {
                    HomeDashboardColumn {
                        Text("Metric")
                        Spacer(Modifier.weight(1f))
                    }
                    ConnectionStatusIcons(connectionRouteStatuses(active), true, true, true, true,
                        false, true, 80) { opens++ }
                }
            }
        }
        val routes = routeDescriptions.map { label ->
            val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true)
            node.assertIsDisplayed()
            node.fetchSemanticsNode().boundsInRoot
        }
        val otherBounds = listOf("Wifi", "Cell", "Bluetooth: Connected", "Battery: 80%", "Open Reporting")
            .map { label ->
                compose.onNodeWithContentDescription(label, useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            }
        val radius = size * density / 2
        for (bounds in routes + otherBounds) {
            for (corner in listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight)) {
                val dx = corner.x - radius
                val dy = corner.y - radius
                assertTrue("Outside round face ($size): $bounds",
                    dx * dx + dy * dy <= radius * radius + 1)
            }
        }
        routes.zipWithNext { left, right -> assertTrue("Routes overlap", left.right < right.left) }
        val metric = compose.onNodeWithText("Metric").fetchSemanticsNode().boundsInRoot
        routes.forEach { assertTrue("Route overlaps main metric", it.bottom <= metric.top) }
        otherBounds.forEach { other ->
            routes.forEach { assertFalse("Route overlaps physical indicator", it.overlaps(other)) }
        }
        compose.onNode(hasClickAction() and hasAnyDescendant(
            hasContentDescription("Ditto Edge Sync: Connected")), useUnmergedTree = true).performClick()
        assertEquals(1, opens)
    }

    @Test fun disabledRoutesDisappearAndStateDescriptionsUpdateInPlace() {
        var snapshot by mutableStateOf(active)
        compose.setContent {
            WearTAKCIVTheme {
                Box(Modifier.requiredSize(192.dp).padding(5.dp)) {
                    ConnectionStatusIcons(connectionRouteStatuses(snapshot), false, false, false, false,
                        true, false, 10) {}
                }
            }
        }
        compose.runOnIdle { snapshot = active.copy(companionReady = true) }
        compose.onNodeWithContentDescription("WearTAK Companion: Connected", true).assertExists()
        listOf("Ditto Edge Sync", "TAK Servers", "Multicast", "SITX").forEach {
            compose.onNodeWithContentDescription("$it: Paused for Companion", true).assertExists()
        }
        compose.runOnIdle { snapshot = ConnectionRouteSnapshot() }
        routeDescriptions.forEach {
            compose.onNodeWithContentDescription(it, true).assertDoesNotExist()
        }
        listOf("Ditto Edge Sync", "TAK Servers", "Multicast", "SITX").forEach {
            compose.onNodeWithContentDescription("$it: Paused for Companion", true).assertDoesNotExist()
        }
        compose.onNodeWithContentDescription("Bluetooth", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Battery: 10%", true).assertExists()
    }
}
