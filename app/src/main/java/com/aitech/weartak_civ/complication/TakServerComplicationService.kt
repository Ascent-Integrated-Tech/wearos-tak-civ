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
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerStatusComplication
import com.aitech.weartak_civ.presentation.takServerComplicationString
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.TAKConnectService

class TakServerComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) {
            return null
        }
        return createComplicationData(Icon.createWithResource(applicationContext, R.drawable.tak_server_connected), getString(R.string.server_connected))
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        takServerComplicationString()
        return when (isLicenseValid()) {
            true -> return when (takServerStatusComplication) {
                "connected" -> createComplicationData(
                    Icon.createWithResource(
                        applicationContext,
                        R.drawable.tak_server_connected
                    ), getString(R.string.server_connected)
                )

                "connecting" -> createComplicationData(
                    Icon.createWithResource(
                        applicationContext,
                        R.drawable.tak_server_connecting
                    ), getString(R.string.server_connecting)
                )

                "disconnected" -> createComplicationData(
                    Icon.createWithResource(
                        applicationContext,
                        R.drawable.tak_server_disconnected
                    ), getString(R.string.server_disconnected)
                )

                else -> throw IllegalArgumentException(
                    getString(
                        R.string.not_capturing_takserverstatuscomplication,
                        takServerStatusComplication
                    )
                )
            }
            false -> createComplicationData(
                Icon.createWithResource(
                    applicationContext,
                    R.drawable.tak_server_disconnected
                ), getString(R.string.server_disconnected)
            )
        }

    }

    private fun createComplicationData(icon: Icon, contentDescription: String) =
        SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(icon, SmallImageType.ICON).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        ).build()
}