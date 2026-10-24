package com.aitech.weartak_civ.presentation

import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.GeoJsonObject
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson

/**
 * MapLibre Compose serializes [GeoJsonData.Features] on the main thread with spatial-k's
 * polymorphic serializer, which throws for an empty FeatureCollection (or one whose
 * features all lack properties) and for non-finite coordinates. That crashed the map
 * indoors without a GPS fix. These helpers keep every map source well-formed.
 */
internal const val EMPTY_FEATURE_COLLECTION_JSON = """{"type":"FeatureCollection","features":[]}"""

/** Returns a map position only for finite, in-range coordinates. */
internal fun validMapPositionOrNull(lat: Double?, lon: Double?): Position? {
    if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return Position(longitude = lon, latitude = lat)
}

/** Serializes off the crash-prone source path; falls back to an empty collection. */
internal fun FeatureCollection<*, *>.toSafeGeoJsonData(): GeoJsonData {
    if (features.isEmpty()) return GeoJsonData.JsonString(EMPTY_FEATURE_COLLECTION_JSON)
    val json = runCatching { (this as GeoJsonObject).toJson() }.getOrNull()
    return GeoJsonData.JsonString(json ?: EMPTY_FEATURE_COLLECTION_JSON)
}
