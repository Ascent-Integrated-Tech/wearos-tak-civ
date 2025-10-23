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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material.CardDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ExperimentalWearMaterialApi
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TitleCard
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.ChatActivity.Companion.TAG
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableChatServiceSettingsState
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ChatActivity : ComponentActivity() {
    companion object {
        val TAG = "WearTAK-" + ChatActivity::class.java.simpleName
        lateinit var dataRepository: DataRepository
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        dataRepository = DataRepository(applicationContext)

        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    ChatCivApp(exitActivity = { finishAndRemoveTask() }, context)
                }
            }
        }
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun ChatCivApp(exitActivity: () -> Unit, context: Context) {
    val navController = rememberSwipeDismissableNavController()
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")
    val chatEnabledLive by enableChatServiceSettingsState.observeAsState()

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

        composable("chat_disabled_screen") {
//            ChatDisabledScreen(navController, exitActivity)
            ActivityDisabledScreen(
                navController,
                exitActivity,
                "Chat Disabled",
                "Please enable Chat in order to use this feature"
            )
        }

        composable(
            "chat_screen_detailed/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            Log.d(TAG, "chat_screen_detailed index: $index")
            ChatScreenDetailed(navController, index, chatRooms)
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
    Log.d(TAG, "TakServerSetting called")
    val context = LocalContext.current

    WearTakCivScreenDimensions(
        columnContent = {
            LazyColumn(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.height(300.dp)) {
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
            Spacer(modifier = Modifier.height(10.dp))},
        rowContent = {
            BackButton(onClick = { exitActivity(); context.startActivity(Intent(context, MainActivity::class.java)) })
        },
        text = "Chat Messages"
    )

//    WearTAKCIVTheme {
//        Box(
//            modifier = Modifier
//                .fillMaxSize()
//                .background(colors.background)
//                .padding(bottom = 16.dp, top = 15.dp)
//                .padding(horizontal = 16.dp),
//            contentAlignment = Alignment.Center
//        ) {
//            Column(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .fillMaxHeight(0.9f)
//                    .align(Alignment.TopCenter)
//                    .padding(bottom = 30.dp),
//                horizontalAlignment = Alignment.CenterHorizontally
//            ) {
//                Text(
//                    text = "Chat Messages",
//                    textAlign = TextAlign.Center,
//                    fontWeight = FontWeight.SemiBold,
//                    modifier = Modifier.padding(7.dp)
//                )
//                LazyColumn(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.height(300.dp)) {
//                    itemsIndexed(chatRooms) { index, room ->
//                        // room.messageList is the List<ChatMessage>
//                        val lastMessage = room.messageList.lastOrNull()
//                        val lastMessageTime = lastMessage?.timeReceived ?: 0L
//                        val lastMessageText = lastMessage?.message.orEmpty()
//
//                        ChatCard(
//                            index = index,
//                            title = room.title,
//                            time = utcToHoursMinutes(lastMessageTime),
//                            message = lastMessageText,
//                            newMessagesPresent = room.newMessagesPresent,
//                            onClick = {
//                                navController.navigate("chat_screen_detailed/$index")
//                            }
//                        )
//                        Spacer(modifier = Modifier.height(5.dp))
//                    }
//                }
//                Spacer(modifier = Modifier.height(10.dp))
//            }
//
//            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
//                BackButton(onClick = { exitActivity(); context.startActivity(Intent(context, MainActivity::class.java)) })
//            }
//        }
//    }
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
            startBackgroundColor = MaterialTheme.colors.primary
                .copy(alpha = 0.60f)
                .compositeOver(MaterialTheme.colors.background),
            endBackgroundColor = MaterialTheme.colors.onSurfaceVariant
                .copy(alpha = 0.20f)
                .compositeOver(MaterialTheme.colors.background),
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
    chatRooms: List<Utils.ChatRoom> // <-- Use Utils.ChatRoom here
) {
    WearTAKCIVTheme {
        val room = chatRooms.getOrNull(index)
        val chatRoomTitle = room?.title.orEmpty()
        val messageList = room?.messageList.orEmpty()
        val roomId = room?.uid

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
                    itemsIndexed(messageList.reversed()) { messageIndex, chatMsg ->
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
                            message = chatMsg.message
                        )
                    }
                }
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                BackButton(onClick = {
                    navController.navigate("chat_screen") {
                        popUpTo("chat_screen") { inclusive = true }
                    }
                })
            }
        }
    }
}

@Composable
fun ChatMessageItem(
    time: String,
    callsign: String,
    message: String,
) {
    Chip(
        onClick = { /* no-op */ },
        enabled = true,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.outlinedChipColors(),
        label = {
            Text(
                text = "$callsign - $time",
                maxLines = 1,
                fontSize = 12.sp,
                textAlign = TextAlign.Start,
                fontWeight = FontWeight.Light,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        },
        secondaryLabel = {
            Text(
                text = message,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth()
            )
        },
    )
}

//TODO: WEAR-234: Delete this
//@Composable
//fun ChatDisabledScreen(navController: NavController, exitActivity: () -> Unit) {
//    WearTAKCIVTheme {
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .background(colors.background)
//                .padding(16.dp),
//            horizontalAlignment = Alignment.CenterHorizontally,
//            verticalArrangement = Arrangement.Center
//        ) {
//            Text(
//                text = "Chat Disabled",
//                fontWeight = FontWeight.SemiBold,
//                textAlign = TextAlign.Center
//            )
//            Spacer(modifier = Modifier.size(5.dp))
//            Text(
//                text = "Please enable Chat in order to use this feature",
//                textAlign = TextAlign.Center,
//                fontSize = 14.sp
//            )
//            Spacer(modifier = Modifier.size(10.dp))
//            Button(
//                onClick = {
//                    navController.navigate("invalid_license_screen") {
//                        popUpTo("invalid_license_screen") { inclusive = true }
//                    }
//                },
//                colors = ButtonDefaults.buttonColors(
//                    containerColor = colors.primary,
//                    contentColor = Color.Black
//                )
//            ) {
//                Text(
//                    text = "Go to Settings",
//                    color = Color.Black,
//                    fontWeight = FontWeight.SemiBold,
//                    textAlign = TextAlign.Center
//                )
//            }
//            Button(
//                onClick = { exitActivity() },
//                colors = ButtonDefaults.buttonColors(
//                    containerColor = Color.DarkGray,
//                    contentColor = Color.White
//                ),
//                modifier = Modifier.padding(top = 2.dp)
//            ) {
//                Text(
//                    text = "Exit",
//                    color = Color.White,
//                    fontWeight = FontWeight.SemiBold,
//                    textAlign = TextAlign.Center
//                )
//            }
//        }
//    }
//}

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
    ChatScreenDetailed(rememberSwipeDismissableNavController(), 0, emptyList())
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun ChatDisabledPreview() {
//    ChatDisabledScreen(rememberSwipeDismissableNavController(), {})
    ActivityDisabledScreen(
        rememberSwipeDismissableNavController(),
        { },
        "Chat Disabled",
        "Please enable Chat in order to use this feature"
    )
}
