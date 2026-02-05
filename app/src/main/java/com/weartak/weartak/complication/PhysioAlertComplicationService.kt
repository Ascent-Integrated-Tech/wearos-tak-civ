/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.weartak.weartak.complication

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
import com.weartak.weartak.R
import com.weartak.weartak.Utils
import com.weartak.weartak.presentation.MainActivity.Constants.dataRepository
import com.weartak.weartak.presentation.MainActivity.Constants.enablePhysiologicalAlerts
import com.weartak.weartak.presentation.MainActivity.Constants.mainActivityIsInit
import com.weartak.weartak.presentation.alertChipState
import com.weartak.weartak.service.LicenseManager.isLicenseValid

class PhysioAlertComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + PhysioAlertComplicationService::class.java.simpleName
        var alertWarningComplicationData: SmallImageComplicationData? = null
        var alertEmergencyComplicationData: SmallImageComplicationData? = null
        var noAlertComplicationData: SmallImageComplicationData? = null
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {

        if (type != ComplicationType.SMALL_IMAGE) {
            return null
        }
        return createComplicationData(
            Icon.createWithResource(
                applicationContext,
                R.drawable.alert_none
            ), getString(R.string.physio_alert_none)
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        //Log.d(TAG, "PhysioAlertComplicationService onComplicationRequest called w/ enablePhysiologicalAlerts=$enablePhysiologicalAlerts, chipState=${dataRepository.physioAlertsFlow.value.alertChipState(enablePhysiologicalAlerts)}")
        if (null == mainActivityIsInit) {
            return null
        }

        when (isLicenseValid()) {
            true -> when (enablePhysiologicalAlerts) {
                true -> when (dataRepository.physioAlertsFlow.value.alertChipState(true)) {
                    Utils.AlertChipState.Warning -> when (null != alertWarningComplicationData) {
                        true -> return alertWarningComplicationData
                        false -> {
                            Log.d(TAG, "Creating new alertWarningComplicationData")
                            alertWarningComplicationData = createComplicationData(
                                Icon.createWithResource(
                                    applicationContext,
                                    R.drawable.alert_warning
                                ), getString(R.string.physio_alert_warning)
                            )
                            return alertWarningComplicationData
                        }
                    }

                    Utils.AlertChipState.Emergency -> when (null != alertEmergencyComplicationData) {
                        true -> return alertEmergencyComplicationData
                        false -> {
                            Log.d(TAG, "Creating new alertEmergencyComplicationData")
                            alertEmergencyComplicationData = createComplicationData(
                                Icon.createWithResource(
                                    applicationContext,
                                    R.drawable.alert_emergency
                                ), getString(R.string.physio_alert_emergency)
                            )
                            return alertEmergencyComplicationData
                        }
                    }

                    Utils.AlertChipState.None -> when (null != noAlertComplicationData) {
                        true -> return noAlertComplicationData
                        false -> {
                            Log.d(TAG, "Creating new noAlertComplicationData")
                            noAlertComplicationData = createComplicationData(
                                Icon.createWithResource(
                                    applicationContext,
                                    R.drawable.alert_none
                                ), getString(R.string.physio_alert_none)
                            )
                            return noAlertComplicationData
                        }
                    }
                }

                false -> when (null != noAlertComplicationData) {
                    true -> return noAlertComplicationData
                    false -> {
                        Log.d(TAG, "Creating new noAlertComplicationData")
                        noAlertComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.alert_none
                            ), "Phsyio Alert None"
                        )
                        return noAlertComplicationData
                    }
                }
            }

            false -> when (null != noAlertComplicationData) {
                true -> return noAlertComplicationData
                false -> {
                    Log.d(TAG, "Creating new noAlertComplicationData")
                    noAlertComplicationData = createComplicationData(
                        Icon.createWithResource(
                            applicationContext,
                            R.drawable.alert_none
                        ), "Phsyio Alert None"
                    )
                    return noAlertComplicationData
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