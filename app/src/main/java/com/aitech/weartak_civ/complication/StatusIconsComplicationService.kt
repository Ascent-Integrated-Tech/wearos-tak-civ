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
import com.aitech.weartak_civ.presentation.overallComplicationString

class StatusIconsComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + StatusIconsComplicationService::class.java.simpleName
        var cellServerConnectingLocationEnabledComplicationData: SmallImageComplicationData? = null
        var cellServerConnectingLocationDisabledComplicationData: SmallImageComplicationData? = null
        var cellServerConnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var cellServerConnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var cellServerDisconnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var cellServerDisconnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var wifiServerConnectingLocationEnabledComplicationData: SmallImageComplicationData? = null
        var wifiServerConnectingLocationDisabledComplicationData: SmallImageComplicationData? = null
        var wifiServerConnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var wifiServerConnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var wifiServerDisconnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var wifiServerDisconnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
        var noConnectionServerConnectingLocationEnabledComplicationData: SmallImageComplicationData? = null
        var noConnectServerConnectingLocationDisabledComplicationData: SmallImageComplicationData? = null
        var noConnectServerDisconnectedLocationEnabledComplicationData: SmallImageComplicationData? = null
        var noConnectServerDisconnectedLocationDisabledComplicationData: SmallImageComplicationData? = null
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) {
            return null
        }
        return createComplicationData(
            Icon.createWithResource(
                applicationContext,
                R.drawable.no_connect_server_disconnected_location_disabled
            ), getString(R.string.no_connect_server_disconnected_location_disabled)
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (null == mainActivityIsInit) {
            return null
        }
        return when (overallComplicationString(applicationContext)) {
            getString(R.string.cell_server_connecting_location_enabled) -> when (null != cellServerConnectingLocationEnabledComplicationData) {
                true -> cellServerConnectingLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellServerConnectingLocationEnabledComplicationData")
                    cellServerConnectingLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_server_connecting_location_enabled), getString(R.string.cell_server_connecting_location_enabled))
                    return cellServerConnectingLocationEnabledComplicationData
                }
            }
            getString(R.string.cell_server_connecting_location_disabled) -> when (null != cellServerConnectingLocationDisabledComplicationData) {
                true -> cellServerConnectingLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellServerConnectingLocationDisabledComplicationData")
                    cellServerConnectingLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_server_connecting_location_disabled), getString(R.string.cell_server_connecting_location_disabled))
                    return cellServerConnectingLocationDisabledComplicationData
                }
            }
            getString(R.string.cell_server_connected_location_enabled) -> when (null != cellServerConnectedLocationEnabledComplicationData) {
                true -> cellServerConnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellServerConnectedLocationEnabledComplicationData")
                    cellServerConnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_server_connected_location_enabled), getString(R.string.cell_server_connected_location_enabled))
                    return cellServerConnectedLocationEnabledComplicationData
                }
            }
            getString(R.string.cell_server_connected_location_disabled) -> when (null != cellServerConnectedLocationDisabledComplicationData) {
                true -> cellServerConnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellServerConnectedLocationDisabledComplicationData")
                    cellServerConnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_server_connected_location_disabled), getString(R.string.cell_server_connected_location_disabled))
                    return cellServerConnectedLocationDisabledComplicationData
                }
            }
            getString(R.string.cell_server_disconnected_location_enabled) -> when (null != cellServerDisconnectedLocationEnabledComplicationData) {
                true -> cellServerDisconnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellServerDisconnectedLocationEnabledComplicationData")
                    cellServerDisconnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_server_disconnected_location_enabled), getString(R.string.cell_server_disconnected_location_enabled))
                    return cellServerDisconnectedLocationEnabledComplicationData
                }
            }
            getString(R.string.cell_server_disconnected_location_disabled) -> when (null != cellServerDisconnectedLocationDisabledComplicationData) {
                true -> cellServerDisconnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new cellServerDisconnectedLocationDisabledComplicationData")
                    cellServerDisconnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.cell_server_disconnected_location_disabled), getString(R.string.cell_server_disconnected_location_disabled))
                    return cellServerDisconnectedLocationDisabledComplicationData
                }
            }
            getString(R.string.wifi_server_connecting_location_enabled) -> when (null != wifiServerConnectingLocationEnabledComplicationData) {
                true -> wifiServerConnectingLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerConnectingLocationEnabledComplicationData")
                    wifiServerConnectingLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_connecting_location_enabled), getString(R.string.wifi_server_connecting_location_enabled))
                    return wifiServerConnectingLocationEnabledComplicationData
                }
            }
            getString(R.string.wifi_server_connecting_location_disabled) -> when (null != wifiServerConnectingLocationDisabledComplicationData) {
                true -> wifiServerConnectingLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerConnectingLocationDisabledComplicationData")
                    wifiServerConnectingLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_connecting_location_disabled), getString(R.string.wifi_server_connecting_location_disabled))
                    return wifiServerConnectingLocationDisabledComplicationData
                }
            }
            getString(R.string.wifi_server_connected_location_enabled) -> when (null != wifiServerConnectedLocationEnabledComplicationData) {
                true -> wifiServerConnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerConnectedLocationEnabledComplicationData")
                    wifiServerConnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_connected_location_enabled), getString(R.string.wifi_server_connected_location_enabled))
                    return wifiServerConnectedLocationEnabledComplicationData
                }
            }
            getString(R.string.wifi_server_connected_location_disabled) -> when (null != wifiServerConnectedLocationDisabledComplicationData) {
                true -> wifiServerConnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerConnectedLocationDisabledComplicationData")
                    wifiServerConnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_connected_location_disabled), getString(R.string.wifi_server_connected_location_disabled))
                    return wifiServerConnectedLocationDisabledComplicationData
                }
            }
            getString(R.string.wifi_server_disconnected_location_enabled) -> when (null != wifiServerDisconnectedLocationEnabledComplicationData) {
                true -> wifiServerDisconnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerDisconnectedLocationEnabledComplicationData")
                    wifiServerDisconnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_disconnected_location_enabled), getString(R.string.wifi_server_disconnected_location_enabled))
                    return wifiServerDisconnectedLocationEnabledComplicationData
                }
            }
            getString(R.string.wifi_server_disconnected_location_disabled) -> when (null != wifiServerDisconnectedLocationDisabledComplicationData) {
                true -> wifiServerDisconnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new wifiServerDisconnectedLocationDisabledComplicationData")
                    wifiServerDisconnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.wifi_server_disconnected_location_disabled), getString(R.string.wifi_server_disconnected_location_disabled))
                    return wifiServerDisconnectedLocationDisabledComplicationData
                }
            }
            getString(R.string.no_connect_server_connecting_location_enabled) -> when (null != noConnectionServerConnectingLocationEnabledComplicationData) {
                true -> noConnectionServerConnectingLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new noConnectionServerConnectingLocationEnabledComplicationData")
                    noConnectionServerConnectingLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.no_connect_server_connecting_location_enabled), getString(R.string.no_connect_server_connecting_location_enabled))
                    return noConnectionServerConnectingLocationEnabledComplicationData
                }
            }
            getString(R.string.no_connect_server_connecting_location_disabled) -> when (null != noConnectServerConnectingLocationDisabledComplicationData) {
                true -> noConnectServerConnectingLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new noConnectServerConnectingLocationDisabledComplicationData")
                    noConnectServerConnectingLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.no_connect_server_connecting_location_disabled), getString(R.string.no_connect_server_connecting_location_disabled))
                    return noConnectServerConnectingLocationDisabledComplicationData
                }
            }
            getString(R.string.no_connect_server_disconnected_location_enabled) -> when (null != noConnectServerDisconnectedLocationEnabledComplicationData) {
                true -> noConnectServerDisconnectedLocationEnabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new noConnectServerDisconnectedLocationEnabledComplicationData")
                    noConnectServerDisconnectedLocationEnabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.no_connect_server_disconnected_location_enabled), getString(R.string.no_connect_server_disconnected_location_enabled))
                    return noConnectServerDisconnectedLocationEnabledComplicationData
                }
            }
            else -> when (null != noConnectServerDisconnectedLocationDisabledComplicationData) {
                true -> noConnectServerDisconnectedLocationDisabledComplicationData
                false -> {
                    Log.d(TAG, "Creating new noConnectServerDisconnectedLocationDisabledComplicationData")
                    noConnectServerDisconnectedLocationDisabledComplicationData = createComplicationData(Icon.createWithResource(applicationContext, R.drawable.no_connect_server_disconnected_location_disabled), getString(R.string.no_connect_server_disconnected_location_disabled))
                    return noConnectServerDisconnectedLocationDisabledComplicationData
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