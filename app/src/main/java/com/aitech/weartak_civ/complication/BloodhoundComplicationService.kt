package com.aitech.weartak_civ.complication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.DrawableRes
import androidx.core.graphics.drawable.IconCompat
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivityIsInit
import com.aitech.weartak_civ.presentation.bloodhoundActiveComplication
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation

class BloodhoundComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + BloodhoundComplicationService::class.java.simpleName
        const val CHAT_DISABLED = "disabled"
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.LONG_TEXT) {
            return null
        }
        return createComplicationData(callsign, getString(R.string.watch_callsign), R.drawable.compass_1, R.drawable.new_item_indicator,
            getString(R.string.compass), 0F)

    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val app = applicationContext as WearTakApplication
        val bhRepo = app.bhRepo
        val active = bhRepo.activeBhItemUi.value
        val northBearing = app.repo.azimuthTrueDegSmoothed.value
        val activeDistanceText = com.aitech.weartak_civ.ui.formatter.UnitFormatter.distanceKm(
            active?.distance,
            com.aitech.weartak_civ.ui.formatter.UnitPrefsState.prefs.value,
            separator = ""
        )
        val properNorthBearing = Utils.normalize0to360(0.0 - (northBearing ?: 0.0))
        val needleOrientation = active?.orientation ?: properNorthBearing

        if (null == mainActivityIsInit) {
            return createComplicationData(getString(R.string.loading), getString(R.string.callsign_loading), R.drawable.compass_1, R.drawable.new_item_indicator,"Loading", 0F)
        }
        return when (isLicenseValid()) {
            //TODO: WEAR-450 see if this updates
                true -> return when (active != null) {
                    true -> createComplicationData(
                        text = activeDistanceText,
                        contentDescription = getString(R.string.watch_callsign),
                        icon = R.drawable.compass_1,
                        image = bloodhoundActiveComplication(),
                        titleText = active.target,
                        orientation = active.orientation
                    )

                    false -> createComplicationData(
                        "---",
                        getString(R.string.watch_callsign),
                        icon = R.drawable.compass_needle,
                        image = bloodhoundActiveComplication(),
                        titleText = getString(R.string.compass),
                        needleOrientation.toFloat()
                    )
                }
                false -> createComplicationData(
                    "---",
                    getString(R.string.watch_callsign),
                    icon = R.drawable.disabled_compass,
                    image = bloodhoundActiveComplication(),
                    titleText = getString(R.string.compass_disabled),
                    needleOrientation.toFloat()
                )
                else -> createComplicationData(
                    "---",
                    getString(R.string.watch_callsign),
                    icon = R.drawable.disabled_compass,
                    image = bloodhoundActiveComplication(),
                    titleText = getString(R.string.compass_disabled),
                    needleOrientation.toFloat()
                )


            /*
            true -> return when (chatComplication) {
                CHAT_DISABLED -> createComplicationData(
                    "---",
                    getString(R.string.watch_callsign),
                    icon = R.drawable.compass_1,
                    titleText = getString(R.string.compass),
                    needleOrientation.toFloat()
                )
                else -> return when (active != null) {
                    true -> createComplicationData(
                        text = active.distance.toString() + " km",
                        contentDescription = getString(R.string.watch_callsign),
                        icon = R.drawable.compass_1,
                        titleText = active.target,
                        orientation = active.orientation
                    )

                    false -> createComplicationData(
                        "---",
                        getString(R.string.watch_callsign),
                        icon = R.drawable.compass_1,
                        titleText = getString(R.string.compass),
                        needleOrientation.toFloat()
                    )
                }
            }
            false -> createComplicationData(
                getString(R.string.invalid_license), getString(R.string.invalid_license_key),
                icon = R.drawable.compass_1,
                titleText = getString(R.string.invalid_license),
                0F
            )
            */
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
        val baseBitmap = BitmapFactory.decodeResource(context.resources, drawableResId)

        val outWidth = baseBitmap.width
        val outHeight = baseBitmap.height

        val output = createBitmap(outWidth, outHeight)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        canvas.withRotation(rotationDegrees, outWidth / 2f, outHeight / 2f) {
            drawBitmap(baseBitmap, 0f, 0f, paint)
        }

        return output
    }
    private fun createComplicationData(text: String, contentDescription: String, icon: Int, image: Int, titleText: String, orientation: Float): LongTextComplicationData {
        val complicationData = LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        )

        val rotation = orientation

        // Create a rotated version of your PNG
        val rotatedBitmap = getRotatedCompassBitmap(
            context = this,
            drawableResId = icon, // your one compass PNG
            rotationDegrees = rotation
        )

        val image = IconCompat.createWithResource(this, image).toIcon(this.baseContext)
        complicationData.setSmallImage(SmallImage.Builder(
            image,
            SmallImageType.ICON
        ).build())

        val icon = IconCompat.createWithBitmap(rotatedBitmap).toIcon(this.baseContext)
        complicationData.setMonochromaticImage(MonochromaticImage.Builder(icon).build())

        val title: ComplicationText? = PlainComplicationText.Builder(titleText).build()
        complicationData.setTitle(title)
        return complicationData.build()
    }
}
