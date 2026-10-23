package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.data.DataRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapUserActionsTest {
    @get:Rule val compose = createComposeRule()

    private fun user(uid: String = "alice") = DataRepository.ReceivedUser(
        uid, "a-f-G-U-C", "Blue", "Alice", "Team Member", 1, 100,
        DataRepository.LocationSnapshot(1, 38.0, -77.0, null, null, null, null, null), "server", chatEndpoint = "*:4242:tcp")

    @Test fun pointWithoutChatEndpointKeepsNavigationButDisablesChat() {
        val point = user().copy(chatEndpoint = null)
        compose.setContent {
            SelectedUser(androidx.wear.compose.navigation.rememberSwipeDismissableNavController(),
                point.uid, listOf(point), LocalContext.current)
        }
        compose.onNodeWithTag("map-user-actions").performScrollToNode(hasText("Open Chat"))
        compose.onNodeWithText("Open Chat").assertIsNotEnabled()
        compose.onNodeWithText("Start Bloodhound").assertIsEnabled()
    }

    @Before fun setDeviceUid() {
        MainActivity.device_uid = "watch"
    }

    @Test fun mapUserTapShowsStartBelowChatAndStartsLatestSelectedUser() {
        val alice = user("alice/with space")
        val users = mutableStateOf(listOf(alice))
        val selections = mutableListOf<DataRepository.ReceivedUser>()
        compose.setContent {
            MapUserNavigation(
                users.value, LocalContext.current,
                onBloodhoundUserSelected = { selections += it; true }
            ) { onUserClick ->
                Chip(onClick = { onUserClick(alice.uid) }, label = { Text("Alice on map") })
            }
        }
        compose.onNodeWithText("Alice on map").performClick()
        compose.onNodeWithText("Open Chat").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Start Bloodhound").assertIsDisplayed().assertIsEnabled()
        assertTrue(compose.onNodeWithText("Start Bloodhound").fetchSemanticsNode().positionInRoot.y >
            compose.onNodeWithText("Open Chat").fetchSemanticsNode().positionInRoot.y)
        val updated = alice.copy(title = "Alice moving", location = alice.location?.copy(lat = 39.0))
        compose.runOnIdle { users.value = listOf(user("bob"), updated) }
        compose.onNodeWithText("Start Bloodhound").performClick()
        compose.runOnIdle { assertEquals(listOf(updated), selections) }
        compose.onNodeWithText("Alice on map").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w192dp-h192dp-round")
    fun smallRoundScreenCanScrollToStartAndRejectsMissingInvalidOrSelfTargets() {
        val alice = user()
        val users = mutableStateOf(listOf(alice))
        var selections = 0
        compose.setContent {
            MapUserNavigation(
                users.value, LocalContext.current,
                onBloodhoundUserSelected = { selections++; false }
            ) { onUserClick ->
                Chip(onClick = { onUserClick(alice.uid) }, label = { Text("Alice on map") })
            }
        }
        compose.onNodeWithText("Alice on map").performClick()
        compose.onNodeWithTag("map-user-actions").performScrollToNode(hasText("Start Bloodhound"))
        compose.onNodeWithText("Start Bloodhound").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, selections) }
        for (invalid in listOf(
            emptyList(),
            listOf(alice.copy(location = null)),
            listOf(alice.copy(location = alice.location?.copy(lat = Double.NaN))),
            listOf(alice.copy(location = alice.location?.copy(lon = 181.0)))
        )) {
            compose.runOnIdle { users.value = invalid }
            compose.onNodeWithText("Start Bloodhound").assertIsNotEnabled()
        }
        compose.runOnIdle {
            MainActivity.device_uid = alice.uid
            users.value = listOf(alice)
        }
        compose.onNodeWithText("Start Bloodhound").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun chatPickerMapCanStartBloodhoundWithoutReturningAChatSelection() {
        val alice = user()
        val contacts = mutableListOf<DirectChatContact>()
        val targets = mutableListOf<DataRepository.ReceivedUser>()
        compose.setContent {
            MapUserNavigation(
                listOf(alice), LocalContext.current,
                selectingChatContact = true,
                onChatContactSelected = { contacts += it },
                onBloodhoundUserSelected = { targets += it; true }
            ) { onUserClick ->
                Chip(onClick = { onUserClick(alice.uid) }, label = { Text("Alice on map") })
            }
        }
        compose.onNodeWithText("Alice on map").performClick()
        compose.onNodeWithText("Open Chat").assertIsDisplayed()
        compose.onNodeWithText("Start Bloodhound").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(alice), targets)
            assertTrue(contacts.isEmpty())
        }
        compose.onNodeWithText("Alice on map").assertIsDisplayed().performClick()
        compose.onNodeWithText("Open Chat").performClick()
        compose.runOnIdle { assertEquals(listOf(DirectChatContact(alice.uid, alice.title)), contacts) }
    }
}
