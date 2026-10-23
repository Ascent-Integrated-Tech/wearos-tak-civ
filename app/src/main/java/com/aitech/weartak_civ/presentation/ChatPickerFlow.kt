package com.aitech.weartak_civ.presentation

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.aitech.weartak_civ.cot.ChatDestinations
import com.aitech.weartak_civ.cot.ChatRoom
import com.aitech.weartak_civ.data.DataRepository

data class DirectChatContact(val uid: String, val callsign: String)

fun resolveChatContact(
    selectedUid: String?,
    users: List<DataRepository.ReceivedUser>,
    selfUid: String
): DirectChatContact {
    val user = requireNotNull(users.firstOrNull { it.uid == selectedUid }) { "Selected person is no longer available" }
    require(!user.chatEndpoint.isNullOrBlank()) { "Selected point has no chat endpoint" }
    ChatDestinations.direct(user.uid, user.title, selfUid)
    return DirectChatContact(user.uid, user.title)
}

class ChatContactContract : ActivityResultContract<Unit, DirectChatContact?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(context, ChatContactPickerActivity::class.java).putExtra(EXTRA_SELECT_CHAT_CONTACT, true)

    override fun parseResult(resultCode: Int, intent: Intent?): DirectChatContact? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        return DirectChatContact(intent.getStringExtra(EXTRA_UID).orEmpty(),
            intent.getStringExtra(EXTRA_CALLSIGN).orEmpty())
    }

    companion object {
        const val EXTRA_SELECT_CHAT_CONTACT = "select_chat_contact"
        const val EXTRA_UID = "chat_contact_uid"
        const val EXTRA_CALLSIGN = "chat_contact_callsign"

        fun result(contact: DirectChatContact): Intent = Intent()
            .putExtra(EXTRA_UID, contact.uid).putExtra(EXTRA_CALLSIGN, contact.callsign)
    }
}

class ChatPickerFlow(
    private val selfUid: String,
    private val openConversation: (ChatRoom) -> ChatRoom,
    private val navigateToRoom: (String) -> Unit,
    private val launchMap: () -> Unit,
    private val onError: (IllegalArgumentException) -> Unit
) {
    fun allChatRooms(title: String) = open(ChatDestinations.allRooms(title))

    fun newChat() = launchMap()

    fun selectedContact(contact: DirectChatContact?) {
        if (contact == null) return
        try {
            open(ChatDestinations.direct(contact.uid, contact.callsign, selfUid))
        } catch (error: IllegalArgumentException) {
            onError(error)
        }
    }

    private fun open(room: ChatRoom) {
        try {
            navigateToRoom(openConversation(room).uid)
        } catch (error: IllegalArgumentException) {
            onError(error)
        }
    }
}
