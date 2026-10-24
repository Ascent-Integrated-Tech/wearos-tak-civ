package com.aitech.weartak_civ.command

import com.aitech.weartak_civ.cot.ChatRoom

object ChatCommandIngress {
    suspend fun handle(
        rooms: List<ChatRoom>, sentAtMs: Long, receivedAtMs: Long, source: String,
        handler: suspend (ChatCommandRequest) -> Boolean
    ): Boolean {
        val candidate = rooms.asSequence()
            .flatMap { room -> room.messageList.asSequence().map { room to it } }
            .firstOrNull { (_, message) ->
                message.message.trimStart().startsWith(ChatCommandParser.PREFIX, ignoreCase = true)
            } ?: return false
        val (room, message) = candidate
        return handler(ChatCommandRequest(
            message.uid, message.callsign, message.message, message.messageId,
            sentAtMs, receivedAtMs, source, room.uid, room.title, room.isGroup, room.participantUids
        ))
    }
}
