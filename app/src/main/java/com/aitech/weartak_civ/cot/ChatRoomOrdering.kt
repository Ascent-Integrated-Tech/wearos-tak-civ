package com.aitech.weartak_civ.cot

object ChatRoomOrdering {
    fun latestReceivedTime(room: ChatRoom, selfUid: String): Long =
        room.messageList.filter { it.uid != selfUid }.maxOfOrNull { it.timeReceived } ?: 0L

    fun byLatestReceived(rooms: Collection<ChatRoom>, selfUid: String): List<ChatRoom> =
        rooms.sortedByDescending { latestReceivedTime(it, selfUid) }

    fun pickerRooms(rooms: Collection<ChatRoom>, selfUid: String): List<ChatRoom> =
        byLatestReceived(rooms.filter { it.uid != ChatDestinations.ALL_CHAT_ROOMS }, selfUid)
}
