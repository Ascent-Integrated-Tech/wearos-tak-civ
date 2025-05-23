/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.complication

import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.chatComplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivityIsInit
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerStatusComplication
import com.aitech.weartak_civ.presentation.chatComplicationString
import com.aitech.weartak_civ.presentation.takServerComplicationString
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.TAKConnectService

class ChatComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + TakServerComplicationService::class.java.simpleName
        var disabledComplicationData: SmallImageComplicationData? = null
        var enabledNoMessageComplicationData: SmallImageComplicationData? = null
        var enabledNewMessageComplicationData: SmallImageComplicationData? = null
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) {
            return null
        }
        return createComplicationData(
            Icon.createWithResource(
                applicationContext,
                R.drawable.tak_server_connected
            ), getString(R.string.server_connected)
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (null == mainActivityIsInit) {
            return null
        }
        //Log.d(TAG, "onComplicationRequest called")
        chatComplicationString()

        return when (isLicenseValid()) {
            true -> return when (chatComplication) {
                "disabled" -> when (null != disabledComplicationData) {
                    true -> disabledComplicationData
                    false -> {
                        Log.d(TAG, "Creating new connectedComplicationData")
                        disabledComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.takchat_disabled
                            ), "Chat Disabled"
                        )
                        return disabledComplicationData
                    }
                }

                "enabledNoMessage" -> when (null != enabledNoMessageComplicationData) {
                    true -> enabledNoMessageComplicationData
                    false -> {
                        Log.d(TAG, "Creating new enabledNoMessageComplicationData")
                        enabledNoMessageComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.takchat_enablednomessage
                            ), "Chat enabled, no new messages"
                        )
                        return enabledNoMessageComplicationData
                    }
                }

                else -> when (null != enabledNewMessageComplicationData) {
                    true -> enabledNewMessageComplicationData
                    false -> {
                        Log.d(TAG, "Creating new enabledNewMessageComplicationData")
                        enabledNewMessageComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.takchat_enablednewmessage
                            ), "Chat enabled, new message(s)"
                        )
                        return enabledNewMessageComplicationData
                    }
                }
            }

            false -> when (null != enabledNewMessageComplicationData) {
                true -> enabledNewMessageComplicationData
                false -> {
                    Log.d(TAG, "Creating new disabledComplicationData")
                    disabledComplicationData = createComplicationData(
                        Icon.createWithResource(
                            applicationContext,
                            R.drawable.takchat_disabled
                        ), "Chat Disabled"
                    )
                    return disabledComplicationData
                }
            }
        }

    }

    private fun createComplicationData(icon: Icon, contentDescription: String) =
        SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(icon, SmallImageType.ICON).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        ).build()
}