package com.aitech.weartak_civ.presentation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.GeoJsonObject
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.toJson

class MapGeoJsonSafetyTest {
    private fun json(data: GeoJsonData) = (data as GeoJsonData.JsonString).json

    @Test fun emptyCollectionCrashesUpstreamSerializerButNotHelper() {
        val empty = FeatureCollection<Point, JsonObject?>(emptyList())
        // Reproduces the tester crash: MapLibre serializes sources as a polymorphic GeoJsonObject.
        assertThrows(NoSuchElementException::class.java) { (empty as GeoJsonObject).toJson() }
        assertEquals(EMPTY_FEATURE_COLLECTION_JSON, json(empty.toSafeGeoJsonData()))
    }

    @Test fun propertylessCollectionFallsBackInsteadOfThrowing() {
        val noProps = FeatureCollection(Feature<Point, JsonObject?>(Point(Position(1.0, 2.0)), null))
        assertEquals(EMPTY_FEATURE_COLLECTION_JSON, json(noProps.toSafeGeoJsonData()))
    }

    @Test fun validCollectionSerializes() {
        val fc = FeatureCollection(
            Feature(Point(Position(-87.0, 41.0)), JsonObject(mapOf("id" to JsonPrimitive("a"))))
        )
        val out = json(fc.toSafeGeoJsonData())
        assertTrue(out.contains("\"FeatureCollection\""))
        assertTrue(out.contains("-87.0"))
    }

    @Test fun invalidPositionsAreRejected() {
        assertNull(validMapPositionOrNull(null, 1.0))
        assertNull(validMapPositionOrNull(1.0, null))
        assertNull(validMapPositionOrNull(Double.NaN, 1.0))
        assertNull(validMapPositionOrNull(1.0, Double.POSITIVE_INFINITY))
        assertNull(validMapPositionOrNull(91.0, 1.0))
        assertNull(validMapPositionOrNull(1.0, -181.0))
        val p = validMapPositionOrNull(41.0, -87.0)!!
        assertEquals(41.0, p.latitude, 0.0)
        assertEquals(-87.0, p.longitude, 0.0)
    }
}
