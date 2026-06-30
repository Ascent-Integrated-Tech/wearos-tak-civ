package com.aitech.weartak_civ.integration.plugin

import com.weartak.plugin.sdk.MarkerContract
import com.weartak.plugin.sdk.model.MarkerParcel

internal data class ParsedPluginMarker(
    val uid: String,
    val title: String,
    val remark: String,
    val markerTypeRaw: String,
    val lat: Double?,
    val lon: Double?,
)

internal object PluginMarkerMapping {
    fun fromParcel(marker: MarkerParcel): ParsedPluginMarker {
        val attrs = marker.attributes
        val typeRaw = attrs.getString(MarkerContract.ATTR_MARKER_TYPE) ?: "UNKNOWN"
        val title = marker.title?.ifBlank { marker.markerId } ?: marker.markerId
        val remark = attrs.getString(MarkerContract.ATTR_REMARK).orEmpty()
        val lat = if (attrs.containsKey(MarkerContract.ATTR_LAT)) {
            attrs.getDouble(MarkerContract.ATTR_LAT)
        } else {
            null
        }
        val lon = if (attrs.containsKey(MarkerContract.ATTR_LON)) {
            attrs.getDouble(MarkerContract.ATTR_LON)
        } else {
            null
        }
        return ParsedPluginMarker(
            uid = marker.markerId,
            title = title,
            remark = remark,
            markerTypeRaw = typeRaw,
            lat = lat,
            lon = lon,
        )
    }
}
