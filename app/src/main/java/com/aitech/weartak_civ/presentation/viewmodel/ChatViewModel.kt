package com.aitech.weartak_civ.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitech.weartak_civ.DEFAULT_ENABLE_CHAT_SERVICE
import com.aitech.weartak_civ.cot.ChatRoom
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ChatViewModel: ViewModel() {
    val savedEnableChatService: StateFlow<Boolean> =
        dataRepository.getEnableChatService.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_CHAT_SERVICE
        )

    val chatRoomsFlow: SharedFlow<List<ChatRoom>> =
        dataRepository.chatRoomsFlow

    fun markRoomRead(uid: String) {
        viewModelScope.launch {
            dataRepository.markRoomRead(uid)
        }
    }

    fun requestChatMessageSendFromUI(dstUid: String, dstChatroom: String, message: String, timeMillis: Long) {
        viewModelScope.launch {
            dataRepository.requestChatMessageSendFromUI(dstUid, dstChatroom, message, timeMillis)
        }
    }

    fun utcToHoursMinutes(time: Long): String {
        val instant = Instant.ofEpochMilli(time)
        val localTime = instant.atZone(ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofPattern("HH:mm a")
        return localTime.format(formatter)
    }

    fun utcToDate(time: Long): String {
        val instant = Instant.ofEpochMilli(time)
        val localTime = instant.atZone(ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")
        return localTime.format(formatter)
    }

}