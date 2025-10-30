package com.aitech.weartak_civ.complication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.annotation.DrawableRes
import androidx.core.graphics.drawable.IconCompat
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivityIsInit
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid

class BloodhoundComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + BloodhoundComplicationService::class.java.simpleName
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) {
            return null
        }
        return createComplicationData(callsign, getString(R.string.watch_callsign), R.drawable.compass_1, "compass", 0F)

    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val bhRepo = (applicationContext as WearTakApplication).bhRepo
        val active = bhRepo.activeBhItemUi.value

        if (null == mainActivityIsInit) {
            return createComplicationData(getString(R.string.loading), getString(R.string.callsign_loading), R.drawable.compass_1, "Loading", 0F)
        }
//        return when (isLicenseValid()) {
//            true -> return when (isAnyBloodhoundingActive()) {
//                true -> createComplicationData(
//                    BloodhoundActivity.activeBhoundItem.value?.distance.toString(),
//                    getString(R.string.watch_callsign),
//                    icon = R.drawable.compass_1,
//                    titleText = BloodhoundActivity.activeBhoundItem.value!!.target,
//                    BloodhoundActivity.activeBhoundItem.value!!.orientation
//                )
//                false -> createComplicationData(
//                    "---",
//                    getString(R.string.watch_callsign),
//                    icon = R.drawable.compass_1,
//                    titleText = "Compass",
//                    0F
//                )
//            }
//            false -> createComplicationData(
//                getString(R.string.invalid_license), getString(R.string.invalid_license_key),
//                icon = R.drawable.compass_1,
//                titleText = getString(R.string.invalid_license),
//                0F
//            )
//        }
        return if (isLicenseValid()) {
            if (active != null) {
                createComplicationData(
                    text = active.distance.toString() + " km",
                    contentDescription = getString(R.string.watch_callsign),
                    icon = R.drawable.compass_1,
                    titleText = active.target,
                    orientation = active.orientation
                )
            } else {
                createComplicationData("---", getString(R.string.watch_callsign), R.drawable.compass_1, "Compass", 0F)
            }
        } else {
            createComplicationData(getString(R.string.invalid_license), getString(R.string.invalid_license_key), R.drawable.compass_1, getString(R.string.invalid_license), 0F)
        }
    }

    //    private fun createComplicationData(text: String, contentDescription: String) =
//        ShortTextComplicationData.Builder(
//            text = PlainComplicationText.Builder(text).build(),
//            contentDescription = PlainComplicationText.Builder(contentDescription).build()
//        ).build()
    //TODO: WEAR-161: Change this back and add code to bloodhoundcomplicationservice when its made

    //TODO: WEAR-161: See if this is more or less jank than referencing the 360 images vvvv
    private fun getRotatedCompassBitmap(
        context: Context,
        @DrawableRes drawableResId: Int,
        rotationDegrees: Float
    ): Bitmap {
        // Decode your existing PNG into a bitmap
        val baseBitmap = BitmapFactory.decodeResource(context.resources, drawableResId)
        val matrix = Matrix().apply {
            postRotate(rotationDegrees)
        }

        // Create a new rotated bitmap
        return Bitmap.createBitmap(
            baseBitmap,
            0,
            0,
            baseBitmap.width,
            baseBitmap.height,
            matrix,
            true
        )
    }
    private fun createComplicationData(text: String, contentDescription: String, icon: Int, titleText: String, orientation: Float): LongTextComplicationData {
        val complicationData = LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        )

        val rotation = orientation

        // Create a rotated version of your PNG
        val rotatedBitmap = getRotatedCompassBitmap(
            context = this,
            drawableResId = R.drawable.compass_1, // your one compass PNG
            rotationDegrees = rotation
        )

        val icon = IconCompat.createWithBitmap(rotatedBitmap).toIcon(this.baseContext)
        complicationData.setMonochromaticImage(MonochromaticImage.Builder(icon).build())

        val title: ComplicationText? = PlainComplicationText.Builder(titleText).build()
        complicationData.setTitle(title)
        return complicationData.build()
    }
}