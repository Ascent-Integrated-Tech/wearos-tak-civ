package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.CompanionAppRepository
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class CompanionChatIngestorTest {
    private fun payload() = CompanionAppRepository.ChatReceivedPayload(
        uid = "message-1", senderUid = "sender", senderCallsign = "Alice",
        msg = "Hello", receivedAt = 100L
    )

    @Test fun bleEnvelopeReachesInboxAndReplayIsDeduplicated() {
        val json = """{"msgType":"chat_received","payload":{"uid":"m1","senderUid":"alice",
            "senderCallsign":"Alice","msg":"Hello","receivedAt":100}}"""
        val envelope = Gson().fromJson(json, CompanionAppRepository.ChatReceivedEnvelope::class.java)
        val incoming = CompanionChatIngestor.room(envelope.payload, 200)
        val room = ChatInboxMerge.merge(null, incoming)
        assertEquals("alice", room.uid)
        assertEquals("Alice", room.title)
        assertEquals("Hello", room.messageList.single().message)
        assertEquals(room, ChatInboxMerge.merge(room, incoming))
    }

    @Test fun invalidRequiredFieldsAreRejected() {
        listOf(null, payload().copy(uid = ""), payload().copy(senderUid = " "),
            payload().copy(senderCallsign = null), payload().copy(msg = "")
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { CompanionChatIngestor.room(value, 200) }
        }
    }

    @Test fun invalidTimestampUsesReceiptTime() {
        assertEquals(200L, CompanionChatIngestor.room(payload().copy(receivedAt = 0), 200)
            .messageList.single().timeReceived)
    }

    @Test fun malformedCanonicalSenderNeverFallsBackToMessageIdAsDirectDestination() {
        for (sender in listOf(null, "", " ")) {
            val canonical = payload().copy(
                uid = "GeoChat.phone.alice.message-123",
                senderUid = sender,
                conversationUid = "alice",
                conversationTitle = "Alice",
                isGroup = false
            )
            assertThrows(IllegalArgumentException::class.java) {
                CompanionChatIngestor.room(canonical, 200)
            }
        }
        val valid = payload().copy(uid = "event-message-id", senderUid = "alice",
            conversationUid = "phone", conversationTitle = "Phone", isGroup = false)
        val room = CompanionChatIngestor.room(valid, 200)
        assertEquals("alice", room.uid)
        assertEquals("event-message-id", room.messageList.single().messageId)
    }
}
