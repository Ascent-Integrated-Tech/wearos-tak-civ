/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.command.ChatCommandProcessor
import com.aitech.weartak_civ.command.ChatCommandRequest
import com.aitech.weartak_civ.command.EnrollServerHandler
import com.aitech.weartak_civ.command.LoadProfileHandler
import com.aitech.weartak_civ.command.PresetServerSink
import com.aitech.weartak_civ.command.ProfilePresetParser
import com.aitech.weartak_civ.command.ProfileHandler
import com.aitech.weartak_civ.command.ProfileOptions
import com.aitech.weartak_civ.command.ProfileStore
import com.aitech.weartak_civ.command.ServerEnrollment
import com.aitech.weartak_civ.command.SetCallsignHandler
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.takserver.TakServerId
import com.aitech.weartak_civ.data.takserver.TakServerRegistration
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity.Constants.sexOptionsList
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity.Constants.userTypeOptionsList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Connects the chat command processor to settings storage, chat replies and the credential prompt. */
object ChatCommandRuntime {
    private const val TAG = "WearTAK-ChatCommands"
    private const val CHANNEL_ID = "chat_commands"
    private const val NOTIFICATION_ID = 7130

    fun create(context: Context, repo: DataRepository): suspend (ChatCommandRequest) -> Boolean {
        val appContext = context.applicationContext
        val identityLocked: suspend () -> Boolean = { repo.phoneManagedSettingsLocked.first() }
        val options = ProfileOptions(
            teams = { MainActivity.teamEntriesValuesList.ltEntries },
            roles = { MainActivity.categoryRolesEntryLists.flatMap { c -> c.roles.map { c.category to it } } },
            sexes = { sexOptionsList.map(appContext::getString) },
            userTypes = { userTypeOptionsList.map(appContext::getString) }
        )
        val store = object : ProfileStore {
            override suspend fun setCallsign(value: String) = repo.storeCallsign(value)
            override suspend fun setTeam(value: String) = repo.storeTeam(value)
            override suspend fun setRole(category: String, role: String) {
                repo.storeRoleCategory(category)
                repo.storeRole(role)
            }
            override suspend fun setHeight(feet: Int, inches: Int) {
                repo.storePhysiologicalMetricHeightFeet(feet)
                repo.storePhysiologicalMetricHeightInches(inches)
            }
            override suspend fun setWeight(pounds: Int) = repo.storePhysiologicalMetricWeight(pounds)
            override suspend fun setBirthYear(year: Int) = repo.storeYearOfBirth(year)
            override suspend fun setSex(value: String) = repo.storePhysiologicalMetricSex(value)
            override suspend fun setUserType(value: String) = repo.storePhysiologicalMetricUserType(value)
            override suspend fun setPantsLength(value: Int) = repo.storePhysiologicalMetricUniformPantsLength(value)
            override suspend fun setWaistSize(value: Int) = repo.storePhysiologicalMetricUniformWaistSize(value)
        }
        val profileHandlers = ProfileHandler.COMMAND_KEYS.mapValues { (_, keys) ->
            ProfileHandler(keys, identityLocked, options, store)
        }
        val processor = ChatCommandProcessor(
            isEnabled = { repo.getEnableChatCommands.first() },
            allowedCallsigns = { repo.getChatCommandCallsigns.first() },
            handlers = profileHandlers + mapOf(
                "loadprofile" to LoadProfileHandler(
                    source = { name -> readPreset(appContext, name) },
                    profileHandler = profileHandlers.getValue("setprofile"),
                    serverSink = serverSink(repo),
                    networkLocked = { repo.phoneManagedSettingsLocked.first() || repo.getNetworkPreferencesLocked.first() },
                    unitPrefsSink = { repo.storeUnitPrefs(it) },
                    customAlertsSink = { alerts ->
                        val store = (appContext as WearTakApplication).savedCustomAlerts
                        val before = store.alerts.first().size
                        if (store.merge(alerts)) store.alerts.first().size - before else 0
                    }
                ),
                "setcallsign" to SetCallsignHandler(
                    isLocked = { repo.phoneManagedSettingsLocked.first() },
                    store = { repo.storeCallsign(it) }
                ),
                "enrollserver" to EnrollServerHandler(
                    isLocked = {
                        repo.phoneManagedSettingsLocked.first() || repo.getNetworkPreferencesLocked.first()
                    },
                    prompt = { promptForCredentials(appContext, it) }
                )
            ),
            reply = { request, text ->
                repo.sendChatMessage(request.senderUid, request.senderCallsign, text, System.currentTimeMillis())
            },
            audit = { Log.i(TAG, it) }
        )
        return processor::handle
    }

    /** Presets are JSON files in the app's external files folder, pushed to the watch with adb. */
    private suspend fun readPreset(context: Context, name: String): String? = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir("presets") ?: return@withContext null
        File(dir, "$name.json").takeIf { it.isFile && it.length() <= 64 * 1024 }?.readText()
    }

    private fun serverSink(repo: DataRepository) = PresetServerSink { name, address, port, username, password ->
        val serverId = TakServerId.ensure(null)
        val item = DataRepository.TakServerItem(true, name, address, port, false, username, password, "", "", serverId)
        var added = false
        TAKConnectService.serverRegistration.saveAndConnect(
            serverId,
            save = {
                when (repo.addTakServerItem(item)) {
                    DataRepository.TakServerAddResult.ADDED -> {
                        added = true
                        TakServerRegistration.SaveResult.SAVED
                    }
                    DataRepository.TakServerAddResult.DUPLICATE -> TakServerRegistration.SaveResult.DUPLICATE
                }
            },
            connect = { TAKConnectService.getInstance().registerServer(serverId) }
        )
        added
    }

    private fun promptForCredentials(context: Context, enrollment: ServerEnrollment) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Chat commands", NotificationManager.IMPORTANCE_HIGH)
        )
        val intent = Intent(context, WearTAKSettingsActivity::class.java)
            .putExtra(WearTAKSettingsActivity.EXTRA_ENROLLMENT_TOKEN, enrollment.token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.weartak_logo)
            .setContentTitle(context.getString(R.string.chat_command_enroll_title))
            .setContentText(context.getString(R.string.chat_command_enroll_text, enrollment.name))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}