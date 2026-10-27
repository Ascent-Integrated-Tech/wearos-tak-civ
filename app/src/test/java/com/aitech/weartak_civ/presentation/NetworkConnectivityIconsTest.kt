package com.aitech.weartak_civ.presentation

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkConnectivityIconsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun builtInArtworkMapsToEveryChecklistRoute() {
        val expected = mapOf(
            "tak" to R.drawable.tak_server_connected,
            "multicast" to R.drawable.multicast_connected,
            "sitx" to R.drawable.ic_connection_sitx,
            "ditto" to R.drawable.ic_integration_ditto,
            "companion" to R.drawable.weartak_companion_plugin
        )
        expected.forEach { (key, icon) ->
            assertEquals(icon, NetworkConnectivityRow(key, key, "", false, {}).iconRes)
        }
        assertNull(networkConnectivityIcon("plugin:example"))
        assertEquals(R.drawable.tak_server_disconnected,
            NetworkConnectivityRow("tak", "TAK", "", false, {},
                iconRes = TakServerIndicator.NO_SERVER.drawable).iconRes)
    }

    @Test fun checklistIconsLabelsAndSwitchesFit192dpRound() = verifyRound(192)

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun checklistIconsLabelsAndSwitchesFit227dpRound() = verifyRound(227)

    private fun verifyRound(size: Int) {
        val labels = mapOf("tak" to ("TAK Servers" to "1/6"),
            "multicast" to ("TAK SA Multicast" to ""),
            "sitx" to ("SITX TAK" to "No group"),
            "ditto" to ("Ditto Edge Sync" to "Community"),
            "companion" to ("WearTAK Companion" to ""))
        var key by mutableStateOf("tak")
        var checked by mutableStateOf(true)
        var bonded by mutableStateOf(false)
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            WearTAKCIVTheme {
                Box(Modifier.requiredSize(size.dp).padding(horizontal = 7.dp),
                    contentAlignment = Alignment.Center) {
                    val (title, subtitle) = labels.getValue(key)
                    NetworkConnectivityChip(
                        NetworkConnectivityRow(key, title, subtitle, checked, { checked = it }),
                        false, bonded, {}, {}, {})
                }
            }
        }
        labels.forEach { (route, label) ->
            compose.runOnIdle { key = route; checked = true }
            val icon = compose.onNode(hasContentDescription(label.first) and !isToggleable(),
                useUnmergedTree = true)
            icon.assertIsDisplayed()
            val text = compose.onNodeWithText(label.first, useUnmergedTree = true)
            text.assertIsDisplayed()
            val textLayouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
            val layout = textLayouts.single()
            assertFalse("${label.first} width overflow at $size", layout.didOverflowWidth)
            assertFalse("${label.first} height overflow at $size", layout.didOverflowHeight)
            assertFalse("${label.first} truncated at $size", layout.isLineEllipsized(layout.lineCount - 1))
            val toggle = compose.onNode(isToggleable()).assertIsOn()
            val iconBounds = icon.fetchSemanticsNode().boundsInRoot
            val textBounds = text.fetchSemanticsNode().boundsInRoot
            val switchBounds = toggle.fetchSemanticsNode().boundsInRoot
            assertTrue(iconBounds.right < textBounds.left)
            assertTrue(textBounds.right <= switchBounds.left)
            val radius = size * density / 2
            for (bounds in listOf(iconBounds, textBounds)) {
                for (corner in listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight)) {
                    val x = corner.x - radius
                    val y = corner.y - radius
                    assertTrue("${label.first} clipped by round face at $size: $bounds",
                        x * x + y * y <= radius * radius + 1)
                }
            }
            toggle.performClick().assertIsOff()
            icon.assertIsDisplayed()
            compose.runOnIdle { bonded = true }
            val bondedText = compose.onNodeWithText("Bonded", useUnmergedTree = true)
            bondedText.assertIsDisplayed()
            if (label.second.isNotEmpty()) compose.onNodeWithText(label.second).assertDoesNotExist()
            val bondedLayouts = mutableListOf<TextLayoutResult>()
            bondedText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(bondedLayouts) }
            assertFalse(bondedLayouts.single().didOverflowWidth)
            assertFalse(bondedLayouts.single().didOverflowHeight)
            val bondedBounds = bondedText.fetchSemanticsNode().boundsInRoot
            for (corner in listOf(bondedBounds.topLeft, bondedBounds.topRight, bondedBounds.bottomLeft, bondedBounds.bottomRight)) {
                val x = corner.x - radius
                val y = corner.y - radius
                assertTrue("Bonded clipped at $size: $bondedBounds", x * x + y * y <= radius * radius + 1)
            }
            compose.onNode(isToggleable()).assertIsNotEnabled()
            compose.runOnIdle { bonded = false }
        }
    }

    @Test fun suppliedPluginIconIsRenderedAndUpdatedWithoutASettingsDestination() {
        val first = ColorDrawable(Color.RED)
        val second = ColorDrawable(Color.BLUE)
        var drawable by mutableStateOf(first)
        lateinit var root: View
        compose.setContent {
            root = LocalView.current.rootView
            WearTAKCIVTheme {
                NetworkConnectivityChip(
                    NetworkConnectivityRow("plugin:mesh", "Mesh Plugin", "", true, {}, pluginIcon = drawable),
                    false, false, {}, {}, {})
            }
        }
        compose.onNodeWithText("Mesh Plugin").assertIsDisplayed()
        fun findIcon(view: View): ImageView? {
            if (view is ImageView && view.contentDescription == "Mesh Plugin") return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    findIcon(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
        compose.runOnIdle { assertSame(first, requireNotNull(findIcon(root)).drawable) }
        compose.runOnIdle { drawable = second }
        compose.runOnIdle { assertSame(second, requireNotNull(findIcon(root)).drawable) }
    }
}
