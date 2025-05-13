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
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivityIsInit
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerStatusComplication
import com.aitech.weartak_civ.presentation.takServerComplicationString
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.TAKConnectService

class TakServerComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + TakServerComplicationService::class.java.simpleName
        var connectedComplicationData : SmallImageComplicationData? = null
        var connectingComplicationData : SmallImageComplicationData? = null
        var disconnectedComplicationData : SmallImageComplicationData? = null
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) {
            return null
        }
        return createComplicationData(Icon.createWithResource(applicationContext, R.drawable.tak_server_connected), getString(R.string.server_connected))
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (null == mainActivityIsInit) {
            return null
            /*
            return when (null != disconnectedComplicationData) {
                true -> disconnectedComplicationData
                false -> {
                    Log.d(TAG, "Creating new disconnectedComplicationData")
                    disconnectedComplicationData = createComplicationData(
                        Icon.createWithResource(
                            applicationContext,
                            R.drawable.tak_server_disconnected
                        ), getString(R.string.server_disconnected)
                    )
                    return disconnectedComplicationData
                }
            }

             */
        }
        //Log.d(TAG, "onComplicationRequest called")
        takServerComplicationString()
        /*
        try {
            Log.d(TAG, "onComplicationRequest called w/ ${TAKConnectService.isRunning.get()}")
            if (TAKConnectService.isRunning.get()) {
                takServerComplicationString()
            } else {
                takServerStatusComplication = "disconnected"
            }
        } catch (e: java.lang.ExceptionInInitializerError) {
            Log.w(TAG, "ERROR onComplicationRequest: ${e.message}")
            takServerStatusComplication = "disconnected"
        } catch (e: java.lang.NoClassDefFoundError) {
            Log.w(TAG, "ERROR onComplicationRequest: ${e.message}")
            takServerStatusComplication = "disconnected"
        }

         */
        return when (isLicenseValid()) {
            true -> return when (takServerStatusComplication) {
                "connected" -> when (null != connectedComplicationData) {
                    true -> connectedComplicationData
                    false -> {
                        Log.d(TAG, "Creating new connectedComplicationData")
                        connectedComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.tak_server_connected
                            ), getString(R.string.server_connected)
                        )
                        return connectedComplicationData
                    }
                }

                "connecting" -> when (null != connectingComplicationData) {
                    true -> connectingComplicationData
                    false -> {
                        Log.d(TAG, "Creating new connectingComplicationData")
                        connectingComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.tak_server_connecting
                            ), getString(R.string.server_connecting)
                        )
                        return connectingComplicationData
                    }
                }

                else -> when (null != disconnectedComplicationData) {
                    true -> disconnectedComplicationData
                    false -> {
                        Log.d(TAG, "Creating new disconnectedComplicationData")
                        disconnectedComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.tak_server_disconnected
                            ), getString(R.string.server_disconnected)
                        )
                        return disconnectedComplicationData
                    }
                }

//                else -> throw IllegalArgumentException(
//                    getString(
//                        R.string.not_capturing_takserverstatuscomplication,
//                        takServerStatusComplication
//                    )
//                )
            }
            false -> when (null != disconnectedComplicationData) {
                true -> disconnectedComplicationData
                false -> {
                    Log.d(TAG, "Creating new disconnectedComplicationData")
                    disconnectedComplicationData = createComplicationData(
                        Icon.createWithResource(
                            applicationContext,
                            R.drawable.tak_server_disconnected
                        ), getString(R.string.server_disconnected)
                    )
                    return disconnectedComplicationData
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