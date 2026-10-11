package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.CompanionAppRepository

object CompanionChatIngestor {
    fun room(payload: CompanionAppRepository.ChatReceivedPayload?, now: Long): ChatRoom {
        requireNotNull(payload) { "Missing chat payload" }
        val id = requireNotNull(payload.uid?.takeIf { it.isNotBlank() }) { "Missing message ID" }
        val sender = requireNotNull(payload.senderUid?.takeIf { it.isNotBlank() }) { "Missing sender UID" }
        val callsign = requireNotNull(payload.senderCallsign?.takeIf { it.isNotBlank() }) { "Missing sender callsign" }
        val text = requireNotNull(payload.msg?.takeIf { it.isNotBlank() }) { "Missing chat text" }
        return ChatRoom(
            uid = sender,
            title = callsign,
            messageList = listOf(ChatMessage(
                message = text, uid = sender, callsign = callsign,
                timeReceived = payload.receivedAt?.takeIf { it > 0L } ?: now,
                messageId = id
            )),
            newMessagesPresent = true,
            timeLastOpened = 0L,
            timeLastClosed = 0L
        )
    }
}
