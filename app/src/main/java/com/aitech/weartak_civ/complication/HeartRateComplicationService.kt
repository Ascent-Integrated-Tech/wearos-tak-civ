package com.aitech.weartak_civ.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysioServiceLiveData
import com.aitech.weartak_civ.service.HeartrateService.Companion.hr
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid

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
            true -> return when (enablePhysioServiceLiveData.value) {
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