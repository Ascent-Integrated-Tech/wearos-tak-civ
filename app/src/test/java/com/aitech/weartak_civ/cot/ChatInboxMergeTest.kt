package com.aitech.weartak_civ.cot

import org.junit.Assert.*
import org.junit.Test

class ChatInboxMergeTest {
    private fun message(id: String? = "id", text: String = "Hello", sender: String = "sender") =
        ChatMessage(text, sender, "Alice", 100, messageId = id)
    private fun room(vararg messages: ChatMessage) =
        ChatRoom("room", "Room", messages.toList(), false, 10, 20)

    @Test fun deduplicatesWithinNewRoomAndIncomingBatch() {
        val first = ChatInboxMerge.merge(null, room(message(), message()))
        assertEquals(1, first.messageList.size)
        assertEquals(2, ChatInboxMerge.merge(first, room(message("next"), message("next"))).messageList.size)
    }

    @Test fun replayDoesNotMarkReadRoomUnreadOrChangeOpenTimes() {
        val existing = room(message())
        assertEquals(existing, ChatInboxMerge.merge(existing, room(message().copy(timeReceived = 200))))
    }

    @Test fun fallbackKeepsDistinctMessagesWithSameTimestamp() {
        assertEquals(3, ChatInboxMerge.merge(null, room(
            message(null), message(null, "Different"), message(null, sender = "bob"),
            message(null), message("", "Different")
        )).messageList.size)
    }

    @Test fun sameIdFromDifferentSendersIsNotCollapsed() {
        assertEquals(2, ChatInboxMerge.merge(null, room(message(), message(sender = "bob")))
            .messageList.size)
    }
}
