/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.weartak.weartak.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.weartak.weartak.R
import com.weartak.weartak.presentation.MainActivity.Constants.enablePhysiologicalServicesSettingsState
import com.weartak.weartak.service.HeartrateService.Companion.hr
import com.weartak.weartak.service.LicenseManager.isLicenseValid

class HeartRateComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + HeartRateComplicationService::class.java.simpleName
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) {
            return null
        }
        return createComplicationData(hr.toString(), getString(R.string.heart_rate))
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        //Log.d(TAG, "onComplicationRequest called")
        return when (isLicenseValid()) {
            true -> return when (enablePhysiologicalServicesSettingsState.value) {
//            return when (hr) {
//                hr -> createComplicationData(hr.toString(), getString(R.string.heart_rate))
//                else -> throw IllegalArgumentException(getString(R.string.heart_rate_could_not_be_found))
//            }
                true -> createComplicationData(hr.toString(), getString(R.string.heart_rate))
                false -> createComplicationData("--- ", getString(R.string.heart_rate_disabled))
                else -> createComplicationData("--- ", getString(R.string.heart_rate_could_not_be_found))
            }
            false -> createComplicationData("--- ", getString(R.string.heart_rate_disabled))
        }
    }

    private fun createComplicationData(text: String, contentDescription: String) =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        ).build()
}
