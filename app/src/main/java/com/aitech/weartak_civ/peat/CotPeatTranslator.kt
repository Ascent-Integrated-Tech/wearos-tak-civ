/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.peat

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.time.Instant

/**
 * Translates between CoT (Cursor on Target) XML format and HIVE lightweight document format.
 *
 * CoT is the standard military situational awareness format.
 * HIVE uses compact binary payloads optimized for BLE transmission.
 *
 * Mapping:
 * - track → PeatDocument.peripheral (GPS, battery, vitals)
 * - mapitem → PeatDocument.extended (compact marker payload)
 * - alert → PeatDocument.emergency (SOS with ACK tracking)
 */
object CotPeatTranslator {

    private const val TAG = "WearTAK-CotPeatTranslator"

    /**
     * Parsed CoT event data.
     */
    data class CotEvent(
        val uid: String,
        val type: String,
        val time: Long,         // epoch millis
        val stale: Long,        // epoch millis
        val how: String,
        val lat: Double,
        val lon: Double,
        val hae: Double,
        val ce: Double,
        val le: Double,
        val callsign: String?,
        val detailXml: String?
    )

    /**
     * Compact marker payload for BLE transmission.
     * Designed to fit in a single BLE packet (~244 bytes).
     */
    data class CompactMarker(
        val uid: String,        // 36 bytes max (UUID)
        val type: String,       // 12 bytes max (a-f-G-U-C)
        val lat: Float,         // 4 bytes
        val lon: Float,         // 4 bytes
        val hae: Float,         // 4 bytes
        val callsign: String,   // 16 bytes max
        val time: Long          // 8 bytes
    ) {
        /**
         * Encode to compact binary format (~84 bytes typical).
         */
        fun encode(): ByteArray {
            val uidBytes = uid.take(36).toByteArray(Charsets.UTF_8)
            val typeBytes = type.take(12).toByteArray(Charsets.UTF_8)
            val csBytes = callsign.take(16).toByteArray(Charsets.UTF_8)

            // Simple length-prefixed encoding
            val result = mutableListOf<Byte>()

            // UID (length + bytes)
            result.add(uidBytes.size.toByte())
            result.addAll(uidBytes.toList())

            // Type (length + bytes)
            result.add(typeBytes.size.toByte())
            result.addAll(typeBytes.toList())

            // Lat/Lon/Hae as floats (12 bytes)
            result.addAll(floatToBytes(lat).toList())
            result.addAll(floatToBytes(lon).toList())
            result.addAll(floatToBytes(hae).toList())

            // Callsign (length + bytes)
            result.add(csBytes.size.toByte())
            result.addAll(csBytes.toList())

            // Time (8 bytes)
            result.addAll(longToBytes(time).toList())

            return result.toByteArray()
        }

        companion object {
            /**
             * Decode from compact binary format.
             */
            fun decode(data: ByteArray): CompactMarker? {
                try {
                    var offset = 0

                    // UID
                    val uidLen = data[offset++].toInt() and 0xFF
                    val uid = String(data, offset, uidLen, Charsets.UTF_8)
                    offset += uidLen

                    // Type
                    val typeLen = data[offset++].toInt() and 0xFF
                    val type = String(data, offset, typeLen, Charsets.UTF_8)
                    offset += typeLen

                    // Lat/Lon/Hae
                    val lat = bytesToFloat(data.sliceArray(offset until offset + 4))
                    offset += 4
                    val lon = bytesToFloat(data.sliceArray(offset until offset + 4))
                    offset += 4
                    val hae = bytesToFloat(data.sliceArray(offset until offset + 4))
                    offset += 4

                    // Callsign
                    val csLen = data[offset++].toInt() and 0xFF
                    val callsign = String(data, offset, csLen, Charsets.UTF_8)
                    offset += csLen

                    // Time
                    val time = bytesToLong(data.sliceArray(offset until offset + 8))

                    return CompactMarker(uid, type, lat, lon, hae, callsign, time)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode CompactMarker: ${e.message}")
                    return null
                }
            }

            private fun floatToBytes(f: Float): ByteArray {
                val bits = java.lang.Float.floatToIntBits(f)
                return byteArrayOf(
                    (bits shr 24).toByte(),
                    (bits shr 16).toByte(),
                    (bits shr 8).toByte(),
                    bits.toByte()
                )
            }

            private fun bytesToFloat(bytes: ByteArray): Float {
                val bits = ((bytes[0].toInt() and 0xFF) shl 24) or
                        ((bytes[1].toInt() and 0xFF) shl 16) or
                        ((bytes[2].toInt() and 0xFF) shl 8) or
                        (bytes[3].toInt() and 0xFF)
                return java.lang.Float.intBitsToFloat(bits)
            }

            private fun longToBytes(l: Long): ByteArray {
                return byteArrayOf(
                    (l shr 56).toByte(),
                    (l shr 48).toByte(),
                    (l shr 40).toByte(),
                    (l shr 32).toByte(),
                    (l shr 24).toByte(),
                    (l shr 16).toByte(),
                    (l shr 8).toByte(),
                    l.toByte()
                )
            }

            private fun bytesToLong(bytes: ByteArray): Long {
                return ((bytes[0].toLong() and 0xFF) shl 56) or
                        ((bytes[1].toLong() and 0xFF) shl 48) or
                        ((bytes[2].toLong() and 0xFF) shl 40) or
                        ((bytes[3].toLong() and 0xFF) shl 32) or
                        ((bytes[4].toLong() and 0xFF) shl 24) or
                        ((bytes[5].toLong() and 0xFF) shl 16) or
                        ((bytes[6].toLong() and 0xFF) shl 8) or
                        (bytes[7].toLong() and 0xFF)
            }
        }
    }

    /**
     * Parse CoT XML into structured data.
     */
    fun parseCotXml(cotXml: String): CotEvent? {
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(cotXml.reader())

            var uid = ""
            var type = ""
            var timeMs = 0L
            var staleMs = 0L
            var how = ""
            var lat = 0.0
            var lon = 0.0
            var hae = 0.0
            var ce = 0.0
            var le = 0.0
            var callsign: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "event" -> {
                            uid = parser.getAttributeValue(null, "uid") ?: ""
                            type = parser.getAttributeValue(null, "type") ?: ""
                            how = parser.getAttributeValue(null, "how") ?: ""
                            parser.getAttributeValue(null, "time")?.let {
                                timeMs = Instant.parse(it).toEpochMilli()
                            }
                            parser.getAttributeValue(null, "stale")?.let {
                                staleMs = Instant.parse(it).toEpochMilli()
                            }
                        }
                        "point" -> {
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            hae = parser.getAttributeValue(null, "hae")?.toDoubleOrNull() ?: 0.0
                            ce = parser.getAttributeValue(null, "ce")?.toDoubleOrNull() ?: 0.0
                            le = parser.getAttributeValue(null, "le")?.toDoubleOrNull() ?: 0.0
                        }
                        "contact" -> {
                            callsign = parser.getAttributeValue(null, "callsign")
                        }
                    }
                }
                parser.next()
            }

            // Extract detail XML
            val detailXml = cotXml
                .substringAfter("<detail>", "")
                .takeIf { it.contains("</detail>") }
                ?.let { "<detail>${it.substringBefore("</detail>")}</detail>" }

            return CotEvent(
                uid = uid,
                type = type,
                time = timeMs,
                stale = staleMs,
                how = how,
                lat = lat,
                lon = lon,
                hae = hae,
                ce = ce,
                le = le,
                callsign = callsign,
                detailXml = detailXml
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse CoT XML: ${e.message}")
            return null
        }
    }

    /**
     * Convert CoT event to compact marker for BLE transmission.
     */
    fun cotToCompactMarker(cot: CotEvent, defaultCallsign: String): CompactMarker {
        return CompactMarker(
            uid = cot.uid,
            type = cot.type,
            lat = cot.lat.toFloat(),
            lon = cot.lon.toFloat(),
            hae = cot.hae.toFloat(),
            callsign = cot.callsign ?: defaultCallsign,
            time = cot.time
        )
    }

    /**
     * Convert compact marker to minimal CoT XML for local display.
     */
    fun compactMarkerToCotXml(marker: CompactMarker): String {
        val staleTime = marker.time + 300_000 // 5 minute stale
        val timeIso = Instant.ofEpochMilli(marker.time).toString()
        val staleIso = Instant.ofEpochMilli(staleTime).toString()

        return """
            <event version="2.0" uid="${marker.uid}" type="${marker.type}"
                   time="$timeIso" start="$timeIso" stale="$staleIso" how="m-g">
                <point lat="${marker.lat}" lon="${marker.lon}" hae="${marker.hae}"
                       ce="9999999" le="9999999"/>
                <detail>
                    <contact callsign="${marker.callsign}"/>
                </detail>
            </event>
        """.trimIndent()
    }

    /**
     * Extract callsign from CoT XML using regex (fast path).
     */
    fun extractCallsign(cotXml: String): String? {
        return """callsign=['"]([^'"]+)['"]""".toRegex()
            .find(cotXml)?.groupValues?.get(1)
    }

    /**
     * Extract UID from CoT XML using regex (fast path).
     */
    fun extractUid(cotXml: String): String? {
        return """uid=['"]([^'"]+)['"]""".toRegex()
            .find(cotXml)?.groupValues?.get(1)
    }

    /**
     * Extract type from CoT XML using regex (fast path).
     */
    fun extractType(cotXml: String): String? {
        return """type=['"]([^'"]+)['"]""".toRegex()
            .find(cotXml)?.groupValues?.get(1)
    }

    /**
     * Check if CoT type represents an emergency/alert.
     */
    fun isEmergencyType(type: String): Boolean {
        return type.startsWith("b-a") || // alerts
               type.contains("911") ||
               type.contains("EMERGENCY", ignoreCase = true)
    }

    /**
     * Check if CoT type represents a track (position update).
     */
    fun isTrackType(type: String): Boolean {
        return type.startsWith("a-") // atom types are tracks
    }

    /**
     * Check if CoT type represents a map item (marker/waypoint).
     */
    fun isMapItemType(type: String): Boolean {
        return type.startsWith("b-m") || // markers
               type.startsWith("b-r") || // routes
               type.startsWith("u-")     // user-defined
    }
}
