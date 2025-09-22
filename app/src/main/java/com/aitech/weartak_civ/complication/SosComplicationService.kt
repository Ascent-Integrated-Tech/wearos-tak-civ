/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
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
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivityIsInit
import com.aitech.weartak_civ.presentation.SosActivity
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid

class SosComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + SosComplicationService::class.java.simpleName
        var sosActiveComplicationData: SmallImageComplicationData? = null
        var sosInactiveComplicationData: SmallImageComplicationData? = null
        val isAlertActive = SosActivity.isAlertingLiveData
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) {
            return null
        }
        return createComplicationData(
            Icon.createWithResource(
                applicationContext,
                R.drawable.sos_active
            ), getString(R.string.sos_inactive)
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (null == mainActivityIsInit) {
            return null
        }
        return when (isLicenseValid()) {
            true -> return when (isAlertActive.value) {
                true -> when (null != sosActiveComplicationData) {
                    true -> return sosActiveComplicationData
                    false -> {
                        Log.d(TAG, "Creating new sosActiveBgComplicationData")
                        sosActiveComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.sos_active
                            ), getString(R.string.sos_active)
                        )
                        return sosActiveComplicationData
                    }
                }

                false -> when (null != sosInactiveComplicationData) {
                    true -> return sosInactiveComplicationData
                    false -> {
                        Log.d(TAG, "Creating new sosInactiveBgComplicationData")
                        sosInactiveComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.sos_inactive
                            ), getString(R.string.sos_inactive)
                        )
                        return sosInactiveComplicationData
                    }
                }
                else -> return null
            }

            false -> when (null != sosInactiveComplicationData) {
                true -> return sosInactiveComplicationData
                false -> {
                    Log.d(TAG, "Creating new sosInactiveBgComplicationData")
                    sosInactiveComplicationData = createComplicationData(
                        Icon.createWithResource(
                            applicationContext,
                            R.drawable.sos_inactive
                        ), getString(R.string.sos_inactive)
                    )
                    return sosInactiveComplicationData
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