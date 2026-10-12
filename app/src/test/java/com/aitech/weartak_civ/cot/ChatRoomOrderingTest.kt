package com.aitech.weartak_civ.cot

import org.junit.Assert.*
import org.junit.Test

class ChatRoomOrderingTest {
    private fun message(sender: String, time: Long) = ChatMessage("Message $time", sender, sender, time)
    private fun room(uid: String, vararg messages: ChatMessage) =
        ChatRoom(uid, uid, messages.toList(), false, 0, 0, isGroup = uid == "team")

    @Test fun pickerExcludesPinnedBroadcastAndSortsDirectAndGroupByReceivedTimeNotListPosition() {
        val direct = room("alice", message("alice", 200), message("alice", 50))
        val group = room("team", message("bob", 300), message("watch", 1000))
        val broadcast = ChatDestinations.allRooms("All Chat Rooms").copy(
            messageList = listOf(message("bob", 2000)))
        val empty = ChatDestinations.direct("carol", "Carol", "watch")
        val outgoingOnly = room("dave", message("watch", 3000))
        assertEquals(listOf("team", "alice", "carol", "dave"),
            ChatRoomOrdering.pickerRooms(listOf(empty, direct, broadcast, outgoingOnly, group), "watch").map { it.uid })
        assertEquals(300L, ChatRoomOrdering.latestReceivedTime(group, "watch"))
    }

    @Test fun newMessageReordersRoomWithoutReplayOrLocalSendPromotingIt() {
        val alice = room("alice", message("alice", 100))
        val team = room("team", message("bob", 200))
        fun order(first: ChatRoom) = ChatRoomOrdering.pickerRooms(listOf(first, team), "watch").map { it.uid }
        assertEquals(listOf("team", "alice"), order(alice))
        val updated = ChatInboxMerge.merge(alice, room("alice", message("alice", 300)))
        assertEquals(listOf("alice", "team"), order(updated))
        val replay = ChatInboxMerge.merge(updated, room("alice", message("alice", 50)))
        assertEquals(listOf("alice", "team"), order(replay))
        assertEquals(listOf("team", "alice"), order(alice.copy(
            messageList = alice.messageList + message("watch", 400))))
    }
}
