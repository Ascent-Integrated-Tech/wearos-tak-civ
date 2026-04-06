/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.text.format.DateFormat
import android.util.Log
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.Utils.ChatRoom
import com.aitech.weartak_civ.Utils.CotUtils
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.atomic.AtomicBoolean

class ChatService : Service() {
    private lateinit var dataRepository: DataRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val chatRoomMap = LinkedHashMap<String, ChatRoom>() // in memory "inbox" roomId → ChatRoom

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val TAG = "WearTAK-${ChatService::class.java.simpleName}"
        private const val CHAT_SERVICE_NOTIFICATION_ID = 101
        val isRunning : AtomicBoolean = AtomicBoolean(false)
        @Volatile private var _instance: ChatService? = null
        fun getInstance(): ChatService? = _instance
        fun markRoomReadStatic(id: String) { _instance?.markRoomRead(id) }

        /**
         * Static helper to add Peat CannedMessage from anywhere.
         */
        fun addPeatCannedMessageStatic(
            senderNodeId: Long,
            label: String,
            messageCode: Int,
            timestamp: Long,
            senderCallsign: String? = null
        ) {
            _instance?.addPeatCannedMessage(senderNodeId, label, messageCode, timestamp, senderCallsign)
                ?: Log.w(TAG, "[PEAT] ChatService not running, cannot add CannedMessage")
        }

        /**
         * Static helper to update ACK count for a Peat message.
         */
        fun updatePeatAckCountStatic(senderNodeId: Long, timestamp: Long, ackCount: Int) {
            _instance?.updatePeatAckCount(senderNodeId, timestamp, ackCount)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ChatService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            CHAT_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        _instance = this
        EventBus.getDefault().register(this)
        dataRepository = MainActivity.dataRepository

        startSendChatReplyCollector()
        startAutomatedChatCollector()
    }

    private fun startSendChatReplyCollector() {
        serviceScope.launch {
            dataRepository.chatRequestFromUI.collect { req ->
                Log.i(TAG, "Received chat request to be sent: $req")
                handleOutgoingChatRequest(req, seedRoomIfMissing = false)
            }
        }
    }

    private fun startAutomatedChatCollector() {
        serviceScope.launch {
            dataRepository.automatedChatRequest.collect { req ->
                Log.i(TAG, "Received automated chat request to be sent: $req")
                handleOutgoingChatRequest(req, seedRoomIfMissing = true)
            }
        }
    }

    private suspend fun handleOutgoingChatRequest(
        req: DataRepository.SendChatRequest,
        seedRoomIfMissing: Boolean
    ) {
        val uid = req.dstUid
        val title = req.dstChatroom
        val message = req.message
        val timeMillis = req.timeMillis
        if (uid.isBlank() || title.isBlank() || message.isBlank()) return

        val existingChatRoom = chatRoomMap[uid]
        val room = when {
            existingChatRoom != null -> existingChatRoom
            seedRoomIfMissing -> Utils.ChatRoom(
                uid = uid,
                title = title,
                messageList = emptyList(),
                newMessagesPresent = false,
                timeLastOpened = timeMillis,
                timeLastClosed = timeMillis
            )
            else -> null
        } ?: return

        val mergedMessages = room.messageList + Utils.ChatMessage(message, MainActivity.device_uid, MainActivity.callsign, timeMillis)
        val mergedRoom = room.copy(
            title = title,
            messageList = mergedMessages,
            newMessagesPresent = false,
            timeLastClosed = timeMillis
        )
        chatRoomMap[uid] = mergedRoom
        val sortedInbox = chatRoomMap.values.sortedByDescending { it.messageList.lastOrNull()?.timeReceived ?: 0L }
        publishInboxToRepo()
        logChatRooms(sortedInbox)
        dataRepository.sendChatMessage(uid, title, message, timeMillis)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ChatService onDestroy()")
        isRunning.set(false)
        EventBus.getDefault().unregister(this)
        _instance = null
        stopForeground(STOP_FOREGROUND_DETACH)
        serviceScope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ChatService onStartCommand()")
        return START_NOT_STICKY
    }

    /*
    private fun startForegroundService() {
        Log.d(TAG, "Starting $TAG as a ForegroundService")
        val channelId = createNotificationChannel()
        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentText("$TAG running")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        try {
            startForeground(CHAT_SERVICE_NOTIFICATION_ID, notification)
        } catch (e: NullPointerException) {
            Log.e(TAG, "Failed to set $TAG as ForegroundService", e)
        }
    }

    private fun createNotificationChannel(): String {
        val channelId = "Chat_Service"
        val channelName = "$TAG Background Channel"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW).apply {
            lightColor = Color.GRAY
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(chan)
        return channelId
    }
    */

    // update the inbox
    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onTakChat(event: TakChatEvent) {
        // 1) Parse incoming rooms from COT
        val incomingRooms = CotUtils.parseChatRooms(event.rawXml, MainActivity.device_uid)
        Log.v(TAG, "Received TakChatEvent: ${event.rawXml}")

        // 2) Merge into in-memory inbox
        incomingRooms.forEach { incoming ->
            val existing = chatRoomMap[incoming.uid]
            if (existing != null) {
                // Append new messages, and only mark “new” if these messages truly weren’t already in list
                val mergedMessages = existing.messageList + incoming.messageList
                val hasNew = incoming.messageList.any { newMsg ->
                    existing.messageList.none { it.timeReceived == newMsg.timeReceived }
                }
                val mergedRoom = existing.copy(
                    messageList        = mergedMessages,
                    newMessagesPresent = existing.newMessagesPresent || hasNew,
                    timeLastOpened     = existing.timeLastOpened,
                    timeLastClosed     = existing.timeLastClosed
                )
                chatRoomMap[incoming.uid] = mergedRoom
            } else {
                // Brand‐new room → mark newMessagesPresent = true
                chatRoomMap[incoming.uid] = incoming.copy(newMessagesPresent = true)
            }
        }

        // 3) Sort & publish once
        val sortedInbox = chatRoomMap.values
            .sortedByDescending { it.messageList.lastOrNull()?.timeReceived ?: 0L }

        // 4) Notify the data layer (updates per‐room booleans correctly)
        publishInboxToRepo()

        // 5) (Optional) log for debugging
        logChatRooms(sortedInbox)
    }

    private fun publishInboxToRepo() {
        val list = getInbox()
        dataRepository.setChatRooms(list)

        CoroutineScope(Dispatchers.Default).launch {
            dataRepository.refreshUnreadCount()
        }
    }

    /* Call publishInboxToRepo() wherever you previously did
       dataRepository.setChatRooms(…) – typically in onTakChat(). */

    private fun logChatRooms(rooms: List<ChatRoom>) {
        Log.d(TAG, "===== Chat Rooms (${rooms.size}) =====")
        rooms.forEachIndexed { roomIdx, room ->
            // Header for this room
            Log.d(TAG, "Room ${roomIdx + 1}/${rooms.size}:")
            Log.d(TAG, "  uid:    ${room.uid}")
            Log.d(TAG, "  title:  ${room.title}")
            Log.d(TAG, "  unread: ${room.newMessagesPresent}")
            Log.d(TAG, "  messages (${room.messageList.size}):")

            // Each message in the room
            room.messageList.forEachIndexed { msgIdx, msg ->
                val timeStr = DateFormat.format("yyyy-MM-dd HH:mm:ss", msg.timeReceived)
                Log.d(TAG, "    ${msgIdx + 1}. [$timeStr] ${msg.callsign} → ${msg.uid}")
                Log.d(TAG, "        “${msg.message}”")
            }
            Log.d(TAG, "----------------------------------")
        }
        Log.d(TAG, "===== End of Chat Rooms =====")
    }

    /**
     * Returns all chat rooms sorted by most‐recent message first.
     * The last message in each ChatRoom.messageList is assumed newest.
     */
    fun getInbox(): List<ChatRoom> = synchronized(chatRoomMap) {
        chatRoomMap.values
            .sortedByDescending { it.messageList.lastOrNull()?.timeReceived ?: 0L }
    }

    /**
     * Returns the total number of unread chat messages across all rooms.
     * A message is “unread” if its timeReceived is later than the room’s timeLastClosed.
     */
    fun getUnreadMsgsCount(): Int = synchronized(chatRoomMap) {
        chatRoomMap.values.sumOf { room ->
            room.messageList.count { msg ->
                msg.timeReceived > room.timeLastClosed
            }
        }
    }

    /**
     * Permanently removes a chat room (and all its messages) from the inbox.
     *
     * @param roomId the UID of the room to delete
     * @return true if the room existed and was removed, false otherwise
     */
    fun deleteChatRoom(roomId: String): Boolean = synchronized(chatRoomMap) {
        val existed = chatRoomMap.remove(roomId) != null
        if (existed) {
            Log.d(TAG, "Deleted chat room: $roomId")
            // TODO: notify your UI or repository that the inbox changed
        }
        existed
    }

    /**
     * Clears out every chat room and message from memory.
     */
    fun clearAllChatRooms() = synchronized(chatRoomMap) {
        chatRoomMap.clear()
        Log.d(TAG, "Cleared all chat rooms")
        // TODO: notify UI / repo of full wipe
    }

    /**
     * Call this when the user opens a room to mark everything up to now as “read.”
     */
    fun markRoomRead(roomId: String) = synchronized(chatRoomMap) {
        chatRoomMap[roomId]?.let { room ->
            room.timeLastClosed = System.currentTimeMillis()
            room.newMessagesPresent = false
        }
    }

    /**
     * Send a new chat message into the given room.
     * Returns true if the send was queued/succeeded locally.
     */
    fun sendMessage(roomId: String, text: String): Boolean { return false}

    /**
     * Add a Peat mesh CannedMessage to the chat inbox.
     * Creates a room per sender using their callsign (or node ID if unknown).
     */
    fun addPeatCannedMessage(
        senderNodeId: Long,
        label: String,
        messageCode: Int,
        timestamp: Long,
        senderCallsign: String? = null
    ) = synchronized(chatRoomMap) {
        val senderHex = String.format("%08X", senderNodeId)
        val displayName = senderCallsign ?: senderHex
        val roomId = "peat-$senderHex"
        val roomTitle = displayName  // Room title is callsign or node ID

        val newMessage = Utils.ChatMessage(
            message = label,
            uid = "peat-$senderHex-$timestamp",
            callsign = displayName,
            timeReceived = timestamp
        )

        val existing = chatRoomMap[roomId]
        if (existing != null) {
            // Append new message to existing room
            val mergedMessages = existing.messageList + newMessage
            chatRoomMap[roomId] = existing.copy(
                messageList = mergedMessages,
                newMessagesPresent = true
            )
        } else {
            // Create new room for this sender
            chatRoomMap[roomId] = ChatRoom(
                uid = roomId,
                title = roomTitle,
                messageList = listOf(newMessage),
                newMessagesPresent = true,
                timeLastOpened = 0L,
                timeLastClosed = 0L
            )
        }

        Log.i(TAG, "[PEAT] Added CannedMessage to chat: $label from $senderHex")
        publishInboxToRepo()
    }

    /**
     * Update the ACK count for a specific Peat message.
     * Called when ACKs are received/propagated through the mesh.
     */
    fun updatePeatAckCount(senderNodeId: Long, timestamp: Long, ackCount: Int) = synchronized(chatRoomMap) {
        val senderHex = String.format("%08X", senderNodeId)
        val roomId = "peat-$senderHex"
        val messageUid = "peat-$senderHex-$timestamp"

        val room = chatRoomMap[roomId] ?: return@synchronized
        val updatedMessages = room.messageList.map { msg ->
            if (msg.uid == messageUid && msg.ackCount != ackCount) {
                msg.copy(ackCount = ackCount)
            } else {
                msg
            }
        }

        // Only update if something changed
        if (updatedMessages != room.messageList) {
            chatRoomMap[roomId] = room.copy(messageList = updatedMessages)
            Log.d(TAG, "[PEAT] Updated ACK count for $messageUid: $ackCount")
            publishInboxToRepo()
        }
    }
}