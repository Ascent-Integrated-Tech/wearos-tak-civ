package com.aitech.weartak_civ.cot

object ChatDestinations {
    const val ALL_CHAT_ROOMS = "All Chat Rooms"

    fun allRooms(title: String): ChatRoom {
        require(title.isNotBlank()) { "Missing broadcast conversation title" }
        return ChatRoom(ALL_CHAT_ROOMS, title, emptyList(), false, 0, 0, isGroup = true)
    }

    fun direct(uid: String, callsign: String, selfUid: String): ChatRoom {
        require(uid.isNotBlank() && uid != selfUid && uid != ALL_CHAT_ROOMS) { "Invalid direct chat recipient" }
        require(callsign.isNotBlank()) { "Missing direct chat callsign" }
        return ChatRoom(uid, callsign, emptyList(), false, 0, 0, participantUids = listOf(uid))
    }
}
