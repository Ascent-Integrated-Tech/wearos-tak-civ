/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.Utils.ChatRoom
import com.aitech.weartak_civ.Utils.CotUtils
import com.aitech.weartak_civ.Utils.ChatMessage
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.TAKConnectService.Companion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.DocumentBuilderFactory

class ChatService : Service() {
    private val TAG = "WearTAK-${ChatService::class.java.simpleName}"
    private lateinit var dataRepository: DataRepository
    private val chatRoomMap = LinkedHashMap<String, ChatRoom>() // in memory "inbox" roomId → ChatRoom

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHAT_SERVICE_NOTIFICATION_ID = 101
        val isRunning : AtomicBoolean = AtomicBoolean(false)
        @Volatile private var _instance: ChatService? = null
        fun getInstance(): ChatService? = _instance
        fun markRoomReadStatic(id: String) { _instance?.markRoomRead(id) }
    }


    override fun onCreate() {
        super.onCreate()
        _instance = this
        Log.d(TAG, "Oncreate for ChatService called")
        EventBus.getDefault().register(this)
        dataRepository = MainActivity.dataRepository
    }
    override fun onDestroy() {
        _instance = null
        super.onDestroy()
        EventBus.getDefault().unregister(this)
        isRunning.set(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning.getAndSet(true)) {
            startForegroundService()
        }
        return START_NOT_STICKY
    }

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
    public fun markRoomRead(roomId: String) = synchronized(chatRoomMap) {
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



}