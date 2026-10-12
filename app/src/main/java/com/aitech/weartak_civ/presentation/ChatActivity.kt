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
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewmodel.compose.viewModel
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
import android.net.Uri
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.cot.ChatMessage
import com.aitech.weartak_civ.cot.ChatRoom
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.ChatActivity.Companion.TAG
import com.aitech.weartak_civ.presentation.ChatActivity.Companion.reply
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.quickReplyOptions
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.presentation.viewmodel.WearTAKSettingsViewModel
import com.aitech.weartak_civ.service.ChatService
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        val sampleRooms = listOf(ChatRoom(
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
        val sampleMessages = ChatRoom(
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

        dataRepository = DataRepository(applicationContext)

        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    val uid = intent.getStringExtra("user_uid")

                    val callsign = intent.getStringExtra("user_callsign")
                    var fromMaps = intent.getBooleanExtra("from_maps", false)
                    val settingsVm: WearTAKSettingsViewModel = viewModel()

                    ChatCivApp(
                        exitActivity = { finishAndRemoveTask() },
                        context = context,
                        initialRoomUid = intent.getStringExtra(EXTRA_OPEN_ROOM_UID),
                        uid = uid,
                        callsign = callsign,
                        fromMaps = fromMaps,
                        settingsVm
                    )
                }
            }
        }
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun ChatCivApp(exitActivity: () -> Unit, context: Context, initialRoomUid: String?, uid: String?, callsign: String?, fromMaps: Boolean, settingsVm: WearTAKSettingsViewModel) {
    val navController = rememberSwipeDismissableNavController()
    var pendingRoomUid by rememberSaveable { mutableStateOf(initialRoomUid) }

    val chatRooms by dataRepository.chatRoomsFlow.collectAsState(initial = emptyList())

    val hasAnyNew = chatRooms.any { it.newMessagesPresent }

    if (uid != null && callsign != null) {
        ChatService.getInstance()?.resolveChatRoom(uid, callsign)
    }

    val actualStartDestination = when {
        fromMaps && uid != null -> "chat_screen_detailed/${Uri.encode(uid)}"
        else -> "chat_screen"
    }

    LaunchedEffect(chatRooms, pendingRoomUid) {
        val roomUid = pendingRoomUid ?: return@LaunchedEffect
        val roomIndex = chatRooms.indexOfFirst { it.uid == roomUid }
        val currentDestination = navController.currentBackStackEntry?.destination?.route
        if (roomIndex >= 0 && currentDestination == "chat_screen") {
            navController.navigate("chat_screen_detailed/${Uri.encode(roomUid)}")
            pendingRoomUid = null
        }
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination =
        if (isLicenseValid()) {
            actualStartDestination
        } else {
            "invalid_license_screen"
        }
    ) {
        composable("chat_screen") {
            ChatScreen(navController, exitActivity, chatRooms)
        }

        composable(
            "chat_reply_screen/{roomUid}",
            arguments = listOf(navArgument("roomUid") { type = NavType.StringType })
        ) { backStackEntry ->
            val roomUid = backStackEntry.arguments?.getString("roomUid")
            val room = chatRooms.firstOrNull { it.uid == roomUid }
            if (room == null) {
                LaunchedEffect(roomUid) {
                    Toast.makeText(context, "Chat conversation is no longer available", Toast.LENGTH_SHORT).show()
                    navController.popBackStack()
                }
            } else {
                val sendPreset = rememberChatPresetSender(room) { navController.popBackStack() }
                TextSelectionScreen(
                    navController, quickReplyOptions, reply, "New Chat",
                    { navController.popBackStack() },
                    { navController.popBackStack() },
                    onOptionSelected = sendPreset,
                    onKeyboardSend = sendPreset
                )
            }
        }

        composable(
            "chat_screen_detailed/{roomUid}",
            arguments = listOf(navArgument("roomUid") { type = NavType.StringType })
        ) { backStackEntry ->
            val roomUid = backStackEntry.arguments?.getString("roomUid")
            ChatScreenDetailed(navController, roomUid, context, chatRooms)
        }

        composable("invalid_license_screen") {
            context.startActivity(Intent(context, WearTAKSettingsActivity::class.java))
            exitActivity()
        }
    }
}

@SuppressLint("CoroutineCreationDuringComposition", "UnrememberedMutableState")
@Composable
fun ChatScreen(
    navController: NavController,
    exitActivity: () -> Unit,
    chatRooms: List<ChatRoom>
) {
    Log.d(TAG, "Chat called")
    val context = LocalContext.current

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
                                navController.navigate("chat_screen_detailed/${Uri.encode(room.uid)}")
                            }
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                WearTAKBackButton(
                    onClick = {
                        exitActivity(); context.startActivity(Intent(context, MainActivity::class.java))
                    }
                )
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
    roomUid: String?,
    context: Context,
    chatRooms: List<ChatRoom>
) {
    WearTAKCIVTheme {
        val room = chatRooms.firstOrNull { it.uid == roomUid }
        val chatRoomTitle = room?.title.orEmpty()
        val messageList = room?.messageList.orEmpty()
        val roomId = room?.uid
        val replyMessage by reply.observeAsState(initial = "")
        val sendPreset = room?.let { rememberChatPresetSender(it) }

        // Mark this room as read when entering detail
        LaunchedEffect(key1 = roomId) {
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
                            uid= chatMsg.uid,
                            context = context
                        )
                    }
                    if (sendPreset != null) item {
                        TextPresetButtons(quickReplyOptions, sendPreset)
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
                                onClick = {
                                    navController.navigate("chat_reply_screen/${Uri.encode(roomId)}") {
                                        launchSingleTop = true
                                    }
                                },
                                enabled = room != null,
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
                                    if (replyMessage.trim().isNotEmpty()) {
                                        // TODO: Do we want to add success dialog box?
                                        // I think no, as this is ATAK behavior and we will send it once any endpoint gets connected.
                                        // It might be good to have an enqueuedToBeSent flag
                                        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                                            dataRepository.requestChatMessageSendFromUI(roomId?: "", chatRoomTitle, replyMessage.trim(), System.currentTimeMillis())
                                            reply.value = ""
                                        }
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
                WearTAKBackButton(
                    onClick = {
                        navController.navigate("chat_screen") {
                            popUpTo("chat_screen") { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun rememberChatPresetSender(room: ChatRoom, onSent: () -> Unit = {}): (String) -> Unit {
    val scope = rememberCoroutineScope()
    var submitting by remember(room.uid) { mutableStateOf(false) }
    return { message ->
        if (!submitting) {
            submitting = true
            scope.launch {
                try {
                    dataRepository.requestChatMessageSendFromUI(
                        room.uid, room.title, message, System.currentTimeMillis()
                    )
                    reply.value = ""
                    onSent()
                } finally {
                    submitting = false
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
    context: Context
) {
    val clipboardManager = LocalClipboard.current

    Chip(
        onClick = {
            val clipData = ClipData.newPlainText(
                "chat copy",
                message
            )

            clipboardManager.nativeClipboard.setPrimaryClip(
                clipData
            )
            Toast.makeText(
                mainActivity,
                "Copied: $message",
                Toast.LENGTH_LONG
            ).show()
        },
        enabled = true,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.outlinedChipColors(
            contentColor = if (uid == device_uid) Color.Green else  colors.primary
        ),
        label = {
            Text(
                text = "$callsign - $time",
                maxLines = 1,
                fontSize = 12.sp,
                textAlign = if (uid == device_uid) TextAlign.End else TextAlign.Start,
                fontWeight = FontWeight.Normal,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
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
    onCancel: () -> Unit,
    onOptionSelected: ((String) -> Unit)? = null,
    onKeyboardSend: ((String) -> Unit)? = null ){

    val context = LocalContext.current
    var replyTextFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                confirmedText.value ?: ""
            )
        )
    }
//TODO: WEAR-450 test this
    WearTAKPageWithConfirmCancel(
        title = titleText,
        columnContent = {
            item {
                Row(
                    modifier = Modifier.padding(bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
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
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (onKeyboardSend != null) ImeAction.Send else ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (replyTextFieldValue.text.isBlank()) {
                                    Toast.makeText(context, R.string.chat_empty_message, Toast.LENGTH_SHORT).show()
                                } else {
                                    onKeyboardSend?.invoke(replyTextFieldValue.text)
                                }
                            }
                        )
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
            }
            item {
                TextPresetButtons(buttonOptions) { optionText ->
                    val selectedText = selectTextOption(
                        replyTextFieldValue.text,
                        optionText,
                        onOptionSelected
                    )
                    replyTextFieldValue = TextFieldValue(
                        text = selectedText,
                        selection = TextRange(selectedText.length)
                    )
                }
            }
        },
        navController = navController,
        onClickConfirm = {
            confirmedText.value = replyTextFieldValue.text
            onConfirm()
        },
        onClickCancel = { onCancel() },
    )

}

@Composable
internal fun TextPresetButtons(
    options: List<Utils.TextSelectionButton>,
    onSelected: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth().height(100.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items(options) { option ->
            Button(
                onClick = { onSelected(option.longText) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.DarkGray,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(size = 10.dp)
            ) {
                Text(
                    text = option.longText,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

internal fun selectTextOption(
    currentText: String,
    optionText: String,
    onOptionSelected: ((String) -> Unit)?
): String {
    if (onOptionSelected == null) return "$currentText $optionText"
    onOptionSelected(optionText)
    return optionText
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

@Preview(widthDp = 192, heightDp = 400, showBackground = true)
@Composable
fun ChatScreenDetailedPreview() {
//    ChatScreenDetailed(rememberSwipeDismissableNavController(), 0, emptyList())
}

@Preview(widthDp = 192, heightDp = 400, showBackground = true)
@Composable
fun ChatReplyPreview() {
    TextSelectionScreen(rememberSwipeDismissableNavController(), quickReplyOptions, reply, "New Chat",{}, {})
}
