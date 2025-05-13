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
import com.aitech.weartak_civ.presentation.connectivityComplicationString
import com.aitech.weartak_civ.presentation.MainActivity.Constants.connectivityStatusComplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivityIsInit

class ConnectivityComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + ConnectivityComplicationService::class.java.simpleName
        var cellComplicationData : SmallImageComplicationData? = null
        var wifiComplicationData : SmallImageComplicationData? = null
        var noneComplicationData : SmallImageComplicationData? = null
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) {
            return null
        }
        return createComplicationData(Icon.createWithResource(applicationContext, R.drawable.no_cell_wifi), getString(R.string.no_connection))
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (null == mainActivityIsInit) {
            return null
            /*
            return when (null != noneComplicationData) {
                true -> noneComplicationData
                false -> {
                    Log.d(TAG, "Creating new noneComplicationData")
                    noneComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.no_cell_wifi), getString(R.string.no_connection))
                    return noneComplicationData
                }
            }

             */
        }
        //Log.d(TAG, "onComplicationRequest called")
        connectivityComplicationString()
        return when (connectivityStatusComplication) {
            "cell" -> when (null != cellComplicationData) {
                true -> cellComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellComplicationData")
                    cellComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell), getString(R.string.cell))
                    return cellComplicationData
                }
            }
            "wifi" -> when (null != wifiComplicationData) {
                true -> wifiComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiComplicationData")
                    wifiComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi), getString(R.string.wifi))
                    return wifiComplicationData
                }
            }
            else -> when (null != noneComplicationData) {
                true -> noneComplicationData
                false -> {
                    Log.d(TAG, "Creating new noneComplicationData")
                    noneComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.no_cell_wifi), getString(R.string.no_connection))
                    return noneComplicationData
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