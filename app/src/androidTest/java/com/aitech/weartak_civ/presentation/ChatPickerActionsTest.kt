package com.aitech.weartak_civ.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.cot.ChatDestinations
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatPickerActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun broadcastAndMapSelectionAreSeparateEntryActions() {
        var broadcasts = 0
        var mapSelections = 0
        compose.setContent {
            WearTAKCIVTheme {
                ChatScreen(rememberSwipeDismissableNavController(), {},
                    listOf(ChatDestinations.direct("alice", "Existing chat", "watch"),
                        ChatDestinations.allRooms("All Chat Rooms")),
                    { broadcasts++ }, { mapSelections++ })
            }
        }
        compose.onNodeWithText("Existing chat").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("All Chat Rooms").performScrollTo().assertIsDisplayed().performClick()
        compose.onAllNodesWithText("All Chat Rooms").assertCountEquals(1)
        compose.runOnIdle {
            assertEquals(1, broadcasts)
            assertEquals(0, mapSelections)
        }
        compose.onNodeWithText("Select User for Chat").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, broadcasts)
            assertEquals(1, mapSelections)
        }
    }

    @Test fun selectedPersonConfirmationSurvivesReorderingAndDisablesWhenPersonDisappears() {
        MainActivity.device_uid = "watch"
        fun user(uid: String, title: String) = DataRepository.ReceivedUser(
            uid, "a-f-G-U-C", "Blue", title, "Team Member", 1, 100, null, "server")
        val alice = user("alice", "Alice")
        val bob = user("bob", "Bob")
        val users = mutableStateOf(listOf(alice, bob))
        val selections = mutableListOf<DirectChatContact>()
        compose.setContent {
            SelectedUser(rememberSwipeDismissableNavController(), "alice", users.value,
                LocalContext.current, selectingChatContact = true,
                onChatContactSelected = { selections += it })
        }
        compose.runOnIdle { users.value = listOf(bob, alice.copy(title = "Alice updated")) }
        compose.onNodeWithText("Chat with this person").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(DirectChatContact("alice", "Alice updated")), selections) }
        compose.runOnIdle { users.value = listOf(bob) }
        compose.onNodeWithText("Chat with this person").assertIsNotEnabled()
    }
}
