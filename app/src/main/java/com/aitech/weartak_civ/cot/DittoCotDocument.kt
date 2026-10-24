package com.aitech.weartak_civ.cot

import java.security.MessageDigest
import java.time.Instant

/** The v2 aliases already emitted by DittoService, not a raw-XML or command envelope. */
internal data class DittoCotDocument(
    val uid: String,
    val peer: String,
    val author: String?,
    val time: Long,
    val stale: Long,
    val type: String,
    val xml: String
) {
    val sourceId: String get() = "ditto:${digest(peer)}"
    val key: String get() = digest("$peer\u0000$uid")
    val fingerprint: String get() = digest(xml)

    companion object {
        const val MAX_PAYLOAD_BYTES = 256 * 1024
        val COLLECTIONS = listOf("track", "mapitem", "alert", "chat")

        fun decode(doc: Map<String, Any?>): DittoCotDocument {
            require((doc["_v"] as? Number)?.toDouble() == 2.0) { "Unsupported Ditto schema version" }
            require(doc["_r"] == false) { "Removed or unspecified Ditto document lifecycle" }
            fun text(key: String, max: Int = 512): String =
                (doc[key] as? String)?.also {
                    require(it.isNotBlank() && it.length <= max && it.none(Char::isISOControl)) {
                        "Invalid Ditto field $key"
                    }
                } ?: throw IllegalArgumentException("Missing Ditto field $key")
            fun timestamp(key: String): Long {
                val value = (doc[key] as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("Missing Ditto timestamp $key")
                require(value.isFinite() && value > 0 && value <= MAX_TIMESTAMP &&
                    value == value.toLong().toDouble()) { "Invalid Ditto timestamp $key" }
                return value.toLong()
            }
            fun number(key: String): Double =
                (doc[key] as? Number)?.toDouble()?.also {
                    require(it.isFinite()) { "Invalid Ditto coordinate $key" }
                } ?: throw IllegalArgumentException("Missing Ditto coordinate $key")

            val uid = text("d")
            require(text("_id") == uid) { "Ditto document/event identity mismatch" }
            val peer = text("a")
            val author = doc["AuthorId"]?.let { text("AuthorId") }
            val time = timestamp("b")
            val start = timestamp("n")
            val stale = timestamp("o")
            require(stale >= time) { "Ditto stale precedes event time" }
            val type = text("w")
            require(CotMessageParser.isUserPositionType(type) ||
                CotMessageParser.isMarkerType(type) || CotMessageParser.isEmergencyType(type) ||
                type == "b-t-f" || type == "b-m-r" || type == "t-x-d-d") { "Unsupported Ditto CoT type $type" }
            val detail = doc["r"] as? String
                ?: throw IllegalArgumentException("Missing Ditto detail")
            require(detail.length <= MAX_PAYLOAD_BYTES &&
                detail.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Oversized Ditto detail" }
            require(!detail.contains("<!DOCTYPE", ignoreCase = true) &&
                !detail.contains("<!ENTITY", ignoreCase = true)) { "DTD/entity in Ditto detail" }
            val root = CotMessageParser.parseDocumentOrNull(detail)?.documentElement
            require(root?.tagName == "detail" &&
                root.getElementsByTagName("event").length == 0 &&
                root.getElementsByTagName("detail").length == 0) { "Malformed Ditto detail" }
            val lat = number("j")
            val lon = number("l")
            require(lat in -90.0..90.0 && lon in -180.0..180.0) { "Invalid Ditto location" }
            val attributes = listOf(
                "version" to CotXml.escape(text("g")),
                "uid" to CotXml.escape(uid), "type" to CotXml.escape(type),
                "time" to Instant.ofEpochMilli(time), "start" to Instant.ofEpochMilli(start),
                "stale" to Instant.ofEpochMilli(stale),
                "how" to CotXml.escape(text("p")),
                "access" to CotXml.escape(text("q"))
            )
            val point = CotXml.element("point", listOf(
                "lat" to lat, "lon" to lon, "hae" to number("i"),
                "ce" to number("h"), "le" to number("k")
            ))
            val xml = CotXml.element("event", attributes, point + detail)
            require(xml.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Oversized Ditto CoT" }
            return DittoCotDocument(uid, peer, author, time, stale, type, xml)
        }

        fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

        private const val MAX_TIMESTAMP = 253402300799999.0
    }
}
