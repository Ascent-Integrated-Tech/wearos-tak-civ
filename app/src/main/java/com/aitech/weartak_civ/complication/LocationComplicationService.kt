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
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivityIsInit
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid

class LocationComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + LocationComplicationService::class.java.simpleName
        private var locationOnComplicationData : SmallImageComplicationData? = null
        private var locationOffComplicationData : SmallImageComplicationData? = null
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) {
            return null
        }
        return createComplicationData(Icon.createWithResource(applicationContext, R.drawable.location_on), getString(R.string.location_on))
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        //Log.d(TAG, "onComplicationRequest called")
        if (null == mainActivityIsInit) {
            return null
            /*
            return when (null != locationOffComplicationData) {
                true -> return locationOffComplicationData
                false -> {
                    Log.d(TAG, "Creating new locationOffComplicationData")
                    locationOffComplicationData = createComplicationData(
                        Icon.createWithResource(
                            applicationContext,
                            R.drawable.location_off
                        ), getString(R.string.location_off)
                    )
                    return locationOffComplicationData
                }
            }

             */
        }
        return when (isLicenseValid()) {
            true -> return when (enableLocationService) {
                true -> when (null != locationOnComplicationData) {
                    true -> return locationOnComplicationData
                    false -> {
                        Log.d(TAG, "Creating new locationOnComplicationData")
                        locationOnComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.location_on
                            ), getString(R.string.location_on)
                        )
                        return locationOnComplicationData
                    }
                }

                false -> when (null != locationOffComplicationData) {
                    true -> return locationOffComplicationData
                    false -> {
                        Log.d(TAG, "Creating new locationOffComplicationData")
                        locationOffComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.location_off
                            ), getString(R.string.location_off)
                        )
                        return locationOffComplicationData
                    }
                }
            }
            false -> when (null != locationOffComplicationData) {
                true -> return locationOffComplicationData
                false -> {
                    Log.d(TAG, "Creating new locationOffComplicationData")
                    locationOffComplicationData = createComplicationData(
                        Icon.createWithResource(
                            applicationContext,
                            R.drawable.location_off
                        ), getString(R.string.location_off)
                    )
                    return locationOffComplicationData
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