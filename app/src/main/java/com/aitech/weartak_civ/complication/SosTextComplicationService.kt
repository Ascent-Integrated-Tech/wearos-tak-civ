/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.complication

import android.os.Build
import android.text.Html
import android.text.Html.FROM_HTML_MODE_LEGACY
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.complication.SosColorComplicationService.Companion.isAlertActive
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivityIsInit
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import androidx.core.text.htmlEncode


class SosTextComplicationService: SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + CallsignComplicationService::class.java.simpleName
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) {
            return null
        }

        return createComplicationData(getString(R.string.sos), getString(R.string.sos_inactive))
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {

        if (null == mainActivityIsInit) {
            return createComplicationData("Loading...", "SOS Details Loading")
        }

        return when (isLicenseValid()) {
            true -> return when (isAlertActive.value) {
                true -> createComplicationData(getString(R.string.sos_active), getString(R.string.sos_active))
                false -> createComplicationData(getString(R.string.sos), getString(R.string.sos_inactive))

                else -> createComplicationData("Loading...", "SOS Details Loading")
            }
            false -> createComplicationData("Loading...", getString(R.string.invalid_license_key))
        }
    }

    private fun createComplicationData(text: String, contentDescription: String) =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        ).build()

}

