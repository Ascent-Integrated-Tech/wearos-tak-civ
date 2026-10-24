package com.aitech.weartak_civ.cot

import java.io.StringWriter
import java.time.Instant
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

/** ATAK Edge Sync v2 chat fields, with optional lossless WearTAK CoT extensions. */
internal object DittoChatDocument {
    const val TTL_MILLIS = 86_400_000L

    fun encode(xml: String, peer: String): Map<String, Any?> {
        require(xml.toByteArray(Charsets.UTF_8).size <= DittoCotDocument.MAX_PAYLOAD_BYTES) {
            "Oversized GeoChat"
        }
        require(CotMessageParser.isValidGatewayChat(xml)) { "Invalid outgoing GeoChat" }
        val event = requireNotNull(CotMessageParser.parseDocumentOrNull(xml)).documentElement
        val chat = event.getElementsByTagName("__chat").item(0) as Element
        val group = chat.getElementsByTagName("chatgrp").item(0) as Element
        val remarks = event.getElementsByTagName("remarks").item(0) as Element
        val point = event.getElementsByTagName("point").item(0) as Element
        val detail = event.getElementsByTagName("detail").item(0) as Element
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }.transform(DOMSource(detail), StreamResult(writer))
        fun time(name: String) = Instant.parse(event.getAttribute(name)).toEpochMilli()
        fun coordinate(name: String) = point.getAttribute(name).toDouble()
        val sender = group.getAttribute("uid0")
        val senderCallsign = chat.getAttribute("senderCallsign")
        val document = mapOf<String, Any?>(
            "_id" to chat.getAttribute("messageId"), "_v" to 2, "_r" to false,
            "a" to peer, "b" to time("time"), "d" to sender, "e" to senderCallsign,
            "msg" to remarks.textContent, "room" to chat.getAttribute("chatroom"),
            "roomId" to chat.getAttribute("id"),
            "parent" to chat.getAttribute("parent").ifBlank { "RootContactGroup" },
            "authorType" to ((event.getElementsByTagName("link").item(0) as? Element)
                ?.getAttribute("type")?.takeIf { it.isNotBlank() } ?: "a-f-G-U-C"),
            "authorLoc" to "${coordinate("lat")},${coordinate("lon")},${coordinate("hae")}",
            "eventUid" to event.getAttribute("uid"), "r" to writer.toString(),
            "n" to time("start"), "o" to time("stale"),
            "g" to event.getAttribute("version"),
            "h" to coordinate("ce"), "i" to coordinate("hae"), "j" to coordinate("lat"),
            "k" to coordinate("le"), "l" to coordinate("lon"),
            "p" to event.getAttribute("how"),
            "q" to event.getAttribute("access").ifBlank { "Undefined" }
        )
        decode(document)
        return document
    }

    fun decode(raw: Map<String, Any?>, knownGroupMembers: List<String> = emptyList()): DittoCotDocument {
        fun text(key: String, limit: Int = 512): String =
            (raw[key] as? String)?.also {
                require(it.isNotBlank() && it.length <= limit && it.none(Char::isISOControl)) {
                    "Invalid Ditto chat field $key"
                }
            } ?: throw IllegalArgumentException("Missing Ditto chat field $key")
        val id = text("_id")
        val sender = text("d")
        val callsign = text("e")
        val peer = text("a")
        val room = text("room")
        val roomId = text("roomId")
        val parent = text("parent")
        val message = (raw["msg"] as? String)?.also {
            require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= DittoCotDocument.MAX_PAYLOAD_BYTES) {
                "Invalid Ditto chat message"
            }
        } ?: throw IllegalArgumentException("Missing Ditto chat message")
        val time = (raw["b"] as? Number)?.toDouble()
            ?: throw IllegalArgumentException("Missing Ditto chat timestamp")
        require(time.isFinite() && time > 0 && time <= 253402214399999.0 &&
            time == time.toLong().toDouble()) { "Invalid Ditto chat timestamp" }
        val expiry = time.toLong() + TTL_MILLIS
        val location = text("authorLoc", 512).split(',').map(String::trim)
        require(location.size >= 2) { "Invalid Ditto chat author location" }
        val latitude = location[0].toDoubleOrNull() ?: Double.NaN
        val longitude = location[1].toDoubleOrNull() ?: Double.NaN
        fun optionalNumber(index: Int) = location.getOrNull(index)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        // GeoPoint.toString(6) appends ALTREF, CE and LE. AGL is not an HAE measurement.
        val altitude = optionalNumber(2)?.takeIf {
            location.getOrNull(3).isNullOrBlank() || location[3] == "HAE"
        } ?: 9_999_999.0
        fun error(index: Int) = optionalNumber(index)?.takeIf { it >= 0.0 } ?: 999999.0
        require(latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0) {
            "Invalid Ditto chat author location"
        }
        val authorType = text("authorType")
        val eventUid = if ("eventUid" in raw) text("eventUid")
            else "GeoChat.$sender.$roomId.$id"
        val detail = if ("r" in raw) raw["r"] as? String
            ?: throw IllegalArgumentException("Invalid Ditto chat detail")
        else {
            val recipients = knownGroupMembers.filter { it != sender }.distinct().ifEmpty { listOf(roomId) }
            val chat = CotXml.element("__chat", listOf(
                "id" to CotXml.escape(roomId), "chatroom" to CotXml.escape(room),
                "senderCallsign" to CotXml.escape(callsign), "messageId" to CotXml.escape(id),
                "parent" to CotXml.escape(parent), "groupOwner" to "false"
            ), CotXml.element("chatgrp", listOf(
                "id" to CotXml.escape(roomId), "uid0" to CotXml.escape(sender)
            ) + recipients.mapIndexed { index, uid -> "uid${index + 1}" to CotXml.escape(uid) }))
            CotXml.element("detail", content = chat + CotXml.element("link", listOf(
                "uid" to CotXml.escape(sender), "type" to CotXml.escape(authorType), "relation" to "p-p"
            )) + CotXml.element("remarks", listOf(
                "source" to CotXml.escape(sender), "to" to CotXml.escape(roomId),
                "time" to Instant.ofEpochMilli(time.toLong())
            ), CotXml.escape(message)))
        }
        val document = DittoCotDocument.decode(raw + mapOf(
            "_id" to eventUid, "d" to eventUid, "a" to peer, "AuthorId" to sender, "w" to "b-t-f",
            "n" to (raw["n"] ?: raw["b"]), "o" to (raw["o"] ?: expiry),
            "g" to (raw["g"] ?: "2.0"), "p" to (raw["p"] ?: "h-g-i-g-o"),
            "q" to (raw["q"] ?: "Undefined"), "r" to detail,
            "j" to (raw["j"] ?: latitude), "l" to (raw["l"] ?: longitude),
            "i" to (raw["i"] ?: altitude), "h" to (raw["h"] ?: error(4)),
            "k" to (raw["k"] ?: error(5))
        ))
        require(document.stale <= expiry) { "Ditto chat exceeds 24-hour TTL" }
        require(CotMessageParser.isValidGatewayChat(document.xml)) { "Invalid GeoChat document in Ditto chat" }
        val root = requireNotNull(CotMessageParser.parseDocumentOrNull(document.xml)).documentElement
        require(root.getElementsByTagName("__chat").length == 1 &&
            root.getElementsByTagName("chatgrp").length == 1 &&
            root.getElementsByTagName("remarks").length == 1) { "Ambiguous Ditto chat detail" }
        val chat = root.getElementsByTagName("__chat").item(0) as Element
        val group = chat.getElementsByTagName("chatgrp").item(0) as Element
        val remarks = root.getElementsByTagName("remarks").item(0) as Element
        require(chat.getAttribute("messageId") == id && group.getAttribute("uid0") == sender &&
            chat.getAttribute("id") == roomId && chat.getAttribute("chatroom") == room &&
            remarks.textContent == message) { "Ditto chat envelope/detail mismatch" }
        return document
    }
}
