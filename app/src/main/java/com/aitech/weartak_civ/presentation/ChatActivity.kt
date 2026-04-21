/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.MutableLiveData
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material.CardDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ExperimentalWearMaterialApi
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TitleCard
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.Utils.ChatMessage
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.PeatBtleRepository
import com.aitech.weartak_civ.events.PeatCannedMessageEvent
import com.aitech.weartak_civ.events.PeatDocumentEvent
import com.aitech.weartak_civ.presentation.ChatActivity.Companion.TAG
import com.aitech.weartak_civ.presentation.ChatActivity.Companion.reply
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableChatServiceSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.quickReplyOptions
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.Long
import kotlin.String

class ChatActivity : ComponentActivity() {
    companion object {
        val TAG = "WearTAK-" + ChatActivity::class.java.simpleName
        const val EXTRA_OPEN_ROOM_UID = "extra_open_room_uid"
        lateinit var dataRepository: DataRepository
        val sampleRooms = listOf(Utils.ChatRoom(
                "0",
                "Test Channel",
                listOf(
                    ChatMessage( "This is a message from atak", "0", "ATAK-Tab", 1L),
                    ChatMessage( "This is a message from wearos", "0", "WEAROS-02", 2L),
                    ChatMessage( "This is a message from your watch", "1", "WEAROS-01", 3L),
                    ),
                false,
                0L,
                0L))
        val sampleMessages = Utils.ChatRoom(
            "0",
            "Test Channel",
            listOf(
                ChatMessage( "This is a message from atak", "0", "ATAK-Tab", 1L),
                ChatMessage( "This is a message from wearos", "0", "WEAROS-02", 2L),
                ChatMessage( "This is a message from your watch", "1", "WEAROS-01", 3L),
            ),
            false,
            0L,
            0L)
        var reply = MutableLiveData<String>("")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = applicationContext as WearTakApplication
        val dataRepository = app.repo
        val peatBtleRepo = app.peatBtleRepo

        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    ChatCivApp(
                        exitActivity = { finishAndRemoveTask() },
                        context = context,
                        initialRoomUid = intent.getStringExtra(EXTRA_OPEN_ROOM_UID),
                        peatBtleRepo = peatBtleRepo,
                        dataRepository = dataRepository
                    )
                }
            }
        }
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun ChatCivApp(exitActivity: () -> Unit, context: Context, initialRoomUid: String?, peatBtleRepo: PeatBtleRepository, dataRepository: DataRepository) {
    val navController = rememberSwipeDismissableNavController()
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")
    val chatEnabledLive by enableChatServiceSettingsState.observeAsState()
    var pendingRoomUid by rememberSaveable { mutableStateOf(initialRoomUid) }

    // Now using Utils.ChatRoom
    val chatRooms by dataRepository.chatRoomsFlow.collectAsState(initial = emptyList())

    val hasAnyNew = chatRooms.any { it.newMessagesPresent }

    LaunchedEffect(licenseKey) {
        Log.d(TAG, "Launch chatEnabledLive = $chatEnabledLive")
        val currentDestination = navController.currentBackStackEntry?.destination?.route
        val isValid = isLicenseValid()

        when {
            !isValid && currentDestination != "invalid_license_screen" -> {
                context.startActivity(Intent(context, SettingsActivity::class.java))
                exitActivity()
            }
            isValid && chatEnabledLive == true && currentDestination != "chat_screen" -> {
                navController.navigate("chat_screen") {
                    popUpTo(0) { inclusive = true } // clears the back stack
                }
            }
            isValid && chatEnabledLive == false && currentDestination != "chat_disabled_screen" -> {
                navController.navigate("chat_disabled_screen") {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    LaunchedEffect(chatRooms, pendingRoomUid) {
        val roomUid = pendingRoomUid ?: return@LaunchedEffect
        val roomIndex = chatRooms.indexOfFirst { it.uid == roomUid }
        val currentDestination = navController.currentBackStackEntry?.destination?.route
        if (roomIndex >= 0 && currentDestination == "chat_screen") {
            navController.navigate("chat_screen_detailed/$roomIndex")
            pendingRoomUid = null
        }
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination =
        if (isLicenseValid()) {
            if (chatEnabledLive == true) "chat_screen" else "chat_disabled_screen"
        } else {
            "invalid_license_screen"
        }
    ) {
        composable("chat_screen") {
            ChatScreen(navController, exitActivity, chatRooms)
        }

        composable("chat_reply_screen")
        {
            TextSelectionScreen(navController, quickReplyOptions, reply, "New Chat",{navController.popBackStack()}, {navController.popBackStack()})
        }

        composable("chat_disabled_screen") {
            ActivityDisabledScreen( navController, exitActivity, stringResource(R.string.chat_disabled),
                stringResource(R.string.please_enable_chat_in_order_to_use_this_feature))
        }

        composable(
            "chat_screen_detailed/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            Log.d(TAG, "chat_screen_detailed index: $index")
            ChatScreenDetailed(navController, index, chatRooms, peatBtleRepo, dataRepository)
        }

        composable("invalid_license_screen") {
            context.startActivity(Intent(context, SettingsActivity::class.java))
            exitActivity()
        }
    }
}

@SuppressLint("CoroutineCreationDuringComposition", "UnrememberedMutableState")
@Composable
fun ChatScreen(
    navController: NavController,
    exitActivity: () -> Unit,
    chatRooms: List<Utils.ChatRoom> // <-- Use Utils.ChatRoom here
) {
    Log.d(TAG, "Chat called")
    val context = LocalContext.current

    //val rooms = sampleRooms

    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .align(Alignment.TopCenter)
                    .padding(bottom = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.chat_messages),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )
                LazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
                    itemsIndexed(chatRooms) { index, room ->
                        // room.messageList is the List<ChatMessage>
                        val lastMessage = room.messageList.lastOrNull()
                        val lastMessageTime = lastMessage?.timeReceived ?: 0L
                        val lastMessageText = lastMessage?.message.orEmpty()

                        ChatCard(
                            index = index,
                            title = room.title,
                            time = utcToHoursMinutes(lastMessageTime),
                            message = lastMessageText,
                            newMessagesPresent = room.newMessagesPresent,
                            onClick = {
                                navController.navigate("chat_screen_detailed/$index")
                            }
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                BackButton(onClick = { exitActivity(); context.startActivity(Intent(context, MainActivity::class.java)) })
            }
        }
    }
}

@OptIn(ExperimentalWearMaterialApi::class)
@Composable
fun ChatCard(
    index: Int,
    title: String,
    time: String,
    message: String,
    newMessagesPresent: Boolean,
    onClick: () -> Unit,
) {
    TitleCard(
        onClick = { onClick() },
        title = {
            if (newMessagesPresent) {
                Image(
                    painter = painterResource(R.drawable.unread_circle),
                    contentDescription = "Unread icon",
                    modifier = Modifier
                        .size(15.dp)
                        .padding(end = 5.dp),
                )
                Text(
                    title,
                    maxLines = 1,
                    modifier = Modifier.size(width = 100.dp, height = 20.dp),
                    overflow = TextOverflow.Ellipsis,)
            } else {
                Text(
                    title,
                    maxLines = 1,
                    modifier = Modifier.size(width = 100.dp, height = 20.dp),
                    overflow = TextOverflow.Ellipsis,)
            }
        },
        time = { Text(text = time, fontSize = 12.sp) },
        backgroundPainter = if (newMessagesPresent) CardDefaults.cardBackgroundPainter(
            startBackgroundColor = colors.primary
                .copy(alpha = 0.60f)
                .compositeOver(colors.background),
            endBackgroundColor = colors.onSurfaceVariant
                .copy(alpha = 0.20f)
                .compositeOver(colors.background),
        ) else CardDefaults.cardBackgroundPainter()
    ) {
        if (newMessagesPresent) {
            Text(
                text = message,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                text = message,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

@Composable
fun ChatScreenDetailed(
    navController: NavController,
    index: Int,
    chatRooms: List<Utils.ChatRoom>,
    peatBtleRepo: PeatBtleRepository,
    dataRepository: DataRepository
) {
    WearTAKCIVTheme {
        val context = LocalContext.current
        val room = chatRooms.getOrNull(index)
        val chatRoomTitle = room?.title.orEmpty()
        val messageList = room?.messageList.orEmpty()
        val roomId = room?.uid
        //val room = sampleMessages
        //val chatRoomTitle = room?.title.orEmpty()
        //val messageList = room?.messageList.orEmpty()
        //val roomId = room?.uid
        val replyMessage by reply.observeAsState(initial = "")


        // Check if Peat mesh is active for ACK button
        val isPeatActive by peatBtleRepo.isActive.collectAsState()

        // Mark this room as read when entering detail
        LaunchedEffect(key1 = index) {
            if (roomId != null) {
                dataRepository.markRoomRead(roomId)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .align(Alignment.TopCenter)
                    .padding(bottom = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = chatRoomTitle,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                LazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
                    itemsIndexed(messageList) { messageIndex, chatMsg ->
                        val currentDate = utcToDate(chatMsg.timeReceived)
                        val displayDate = if (messageIndex == 0) {
                            true
                        } else {
                            utcToDate(messageList[messageIndex - 1].timeReceived) != currentDate
                        }

                        if (displayDate) {
                            Text(
                                text = currentDate,
                                fontSize = 12.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 5.dp)
                            )
                        }
                        ChatMessageItem(
                            time = utcToHoursMinutes(chatMsg.timeReceived),
                            callsign = chatMsg.callsign,
                            message = chatMsg.message,
                            uid = chatMsg.uid,
                            ackCount = chatMsg.ackCount
                        )
                    }
                    item {
                        Row(
                            verticalAlignment= Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .padding(top = 5.dp)
                        ) {
                            Chip(
                                onClick = { navController.navigate("chat_reply_screen") {
                                    popUpTo("chat_reply_screen") { inclusive = true }
                                }},
                                enabled = true,
                                modifier = if (replyMessage.isEmpty()) {
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(.8F)
                                        .height(30.dp)
                                } else {
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(.8F)
                                        .defaultMinSize(minHeight = 30.dp)
                                        .wrapContentHeight()
                                },
                                label = {
                                    Text(
                                        text = replyMessage,
                                        fontSize  = 12.sp,
                                        maxLines = 3,
                                        fontWeight = FontWeight.Normal,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = ChipDefaults.chipColors(
                                    backgroundColor = Color.DarkGray,
                                    contentColor = Color.White,
                                    secondaryContentColor = Color.LightGray
                                ),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            IconButton(
                                onClick = {
                                    Log.i(TAG, "[SEND] Send button clicked: replyMessage='${replyMessage.trim()}', roomId=$roomId, roomTitle=$chatRoomTitle")
                                    if (replyMessage.trim().isNotEmpty()) {
                                        Log.i(TAG, "[SEND] Dispatching chat: roomId=$roomId, title=$chatRoomTitle, msg=${replyMessage.trim()}")
                                        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                                            dataRepository.requestChatMessageSendFromUI(roomId?: "", chatRoomTitle, replyMessage.trim(), System.currentTimeMillis())
                                            reply.value = ""
                                        }
                                    } else {
                                        Log.w(TAG, "[SEND] Reply message is empty, not sending")
                                    }
                                },
                                modifier = Modifier
                                    .size(35.dp),
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = Color.Black,
                                    containerColor = colors.primary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Close icon",
                                    tint = Color.Black
                                )
                            }
                        }
                    }
                }
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                BackButton(onClick = {
                    navController.navigate("chat_screen") {
                        popUpTo("chat_screen") { inclusive = true }
                    }
                })

                // ACK button - only show when Peat mesh is active
                if (isPeatActive) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Button(
                        onClick = {
                            try {
                                // Check if this is Peat Mesh CannedMessage room (starts with "peat-")
                                val isPeatMeshRoom = roomId?.startsWith("peat-") == true

                                if (isPeatMeshRoom && roomId != null) {
                                    // Extract node ID from room ID (format: "peat-XXXXXXXX")
                                    val nodeIdHex = roomId.removePrefix("peat-")
                                    val targetNode = nodeIdHex.toLongOrNull(16) ?: 0L
                                    if (targetNode != 0L) {
                                        // Get the timestamp of the last message for CRDT ACK lookup
                                        val lastMsg = messageList.lastOrNull()
                                        val origTimestamp = lastMsg?.timeReceived ?: 0L
                                        Log.i(TAG, "[CANNED-ACK] Posting ACK: targetNode=$nodeIdHex, origTimestamp=$origTimestamp, lastMsg=${lastMsg?.message}")
                                        EventBus.getDefault().post(PeatCannedMessageEvent(
                                            messageCode = 0x00,
                                            targetNode = targetNode,
                                            originalTimestamp = origTimestamp
                                        ))
                                        android.widget.Toast.makeText(context, "ACK sent", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        Log.w(TAG, "[CANNED-ACK] Invalid node ID in room: $roomId")
                                        android.widget.Toast.makeText(context, "Invalid sender", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    // Regular chat ACK
                                    val lastChat = peatBtleRepo.lastMeshChat
                                    val ackCot = "<remarks>ACK</remarks>"
                                    EventBus.getDefault().post(PeatDocumentEvent(
                                        collection = "chat",
                                        cotXml = ackCot,
                                        replyToNode = lastChat?.originNodeId ?: 0L,
                                        replyToTimestamp = lastChat?.timestamp ?: 0L
                                    ))
                                    if (lastChat != null) {
                                        Log.i(TAG, "ACK sent (replyTo: ${String.format("%08X", lastChat.originNodeId)}:${lastChat.timestamp})")
                                    } else {
                                        Log.i(TAG, "ACK sent (no replyTo - no mesh chat received)")
                                    }
                                    android.widget.Toast.makeText(context, "ACK sent", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send ACK: ${e.message}", e)
                                android.widget.Toast.makeText(context, "ACK failed", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(height = 35.dp, width = 75.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("ACK", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(
    time: String,
    callsign: String,
    message: String,
    uid: String,
    ackCount: Int = 0
) {
    Chip(
        onClick = { /* no-op */ },
        enabled = true,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.outlinedChipColors(
            contentColor = if (uid == device_uid) Color.Green else  colors.primary
        ),
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$callsign - $time",
                    maxLines = 1,
                    fontSize = 12.sp,
                    textAlign = if (uid == device_uid) TextAlign.End else TextAlign.Start,
                    fontWeight = FontWeight.Normal,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (ackCount > 0) {
                    Text(
                        text = "\u2713$ackCount",  // checkmark + count
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF4CAF50),  // green
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        },
        secondaryLabel = {
            Text(
                text = message,
                fontSize = 15.sp,
                color = Color.White,
                textAlign = if (uid == device_uid) TextAlign.End else TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        },
    )
}
//Used for Chat Reply AND Manual Alert Type (SosActivity)
@Composable
fun TextSelectionScreen(
    navController: NavController,
    buttonOptions: List<Utils.TextSelectionButton>,
    confirmedText: MutableLiveData<String>,
    titleText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit ){
//    val textBox = remember { mutableStateOf("") }
    var replyTextFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                confirmedText.value ?: ""
            )
        )
    }

    WearTakCivScreenDimensions(
        columnContent = {
            Row(
                modifier = Modifier.padding(bottom=5.dp),
                verticalAlignment= Alignment.CenterVertically,
                ) {
                TextField(
                    value = replyTextFieldValue,
                    onValueChange = {
                        replyTextFieldValue = it
                    },
                    placeholder = { Text("") },
                    modifier = if (replyTextFieldValue.text.isEmpty()) {
                        Modifier
                            .fillMaxWidth()
                            .weight(.8F)
                            .height(30.dp)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .weight(.8F)
                            .defaultMinSize(minHeight = 30.dp)
                            .wrapContentHeight()
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.DarkGray,
                        unfocusedContainerColor = Color.DarkGray,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        unfocusedTextColor = Color.White,
                        focusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(50), // pill shape like Chip
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                Spacer(modifier = Modifier.width(5.dp))
                IconButton(
                    onClick = {
                        if (replyTextFieldValue.text.isNotEmpty()) {
                            val newText = replyTextFieldValue.text.dropLast(1)
                            replyTextFieldValue = replyTextFieldValue.copy(
                                text = newText,
                                selection = TextRange(newText.length) // keep cursor at the end
                            )
                        }
                    },
                    modifier = Modifier
                        .size(35.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = Color.Black,
                        containerColor = Color.Unspecified
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.backspace),
                        contentDescription = "Close icon",
                        tint = colors.primary,
                        modifier = Modifier.size(25.dp)
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.height(100.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                items(buttonOptions) { replyOption ->
                    Button(
                        onClick = {  replyTextFieldValue = TextFieldValue(
                            text = replyTextFieldValue.text + " " + replyOption.longText,
                            selection = TextRange((replyTextFieldValue.text +  " " + replyOption.longText).length)
                        )},
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.DarkGray,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(size = 10.dp)
                    ) {
                        Text(
                            text = replyOption.shortenedText,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                }
            }
        },
        rowContent = {
            ConfirmCancelButtons(
                onClickCancel = {
//                    navController.popBackStack()
                    onCancel()
                },
            onClickConfirm = {
                confirmedText.value = replyTextFieldValue.text
                onConfirm()
//                navController.popBackStack()
            }
            )
        },
        text = titleText
    )

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

@Preview(widthDp = 192, heightDp = 400, showBackground = true)
@Composable
fun ChatScreenPreview() {
    ChatScreen(rememberSwipeDismissableNavController(), {}, emptyList())
}

// Preview disabled - requires PeatBtleRepository
// @Preview(widthDp = 192, heightDp = 400, showBackground = true)
// @Composable
// fun ChatScreenDetailedPreview() {
//     ChatScreenDetailed(rememberSwipeDismissableNavController(), 0, emptyList(), ...)
// }

@Preview(widthDp = 192, heightDp = 400, showBackground = true)
@Composable
fun ChatReplyPreview() {
    TextSelectionScreen(rememberSwipeDismissableNavController(), quickReplyOptions, reply, "New Chat",{}, {})
}
