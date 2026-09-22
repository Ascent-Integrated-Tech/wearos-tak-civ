package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository.MarkerType
import java.io.StringReader
import java.time.Instant
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Logging-only ownership clues; explicit channel stamps are not treated as authorization data. */
data class CotIngressDiagnostics(
    val uid: String?,
    val type: String?,
    val eventTime: Long?,
    val teamName: String?,
    val teamRole: String?,
    val flowTagNames: List<String>,
    val explicitChannelStamps: List<String>
)

object CotMessageParser {
    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"

    fun parseUserPositionOrNull(rawXml: String): CotUserPositionEvent? {
        val type = eventAttribute(rawXml, "type") ?: return null
        if (!isUserPositionType(type)) return null
        val uid = eventAttribute(rawXml, "uid") ?: return null
        val time = eventAttribute(rawXml, "time") ?: return null
        val start = eventAttribute(rawXml, "start") ?: time
        val stale = eventAttribute(rawXml, "stale") ?: time

        return CotUserPositionEvent(
            version = eventAttribute(rawXml, "version") ?: "",
            uid = uid,
            team = extractAttribute(rawXml, "__group", "name") ?: "",
            type = type,
            role = extractAttribute(rawXml, "__group", "role") ?: "",
            how = eventAttribute(rawXml, "how") ?: "",
            time = parseIsoToEpochMs(time),
            start = parseIsoToEpochMs(start),
            stale = parseIsoToEpochMs(stale),
            access = eventAttribute(rawXml, "access"),
            point = pointOf(rawXml) ?: return null,
            detail = detailOf(rawXml),
        )
    }

    fun parseMarkerOrNull(rawXml: String): CotMarkerEvent? {
        val type = eventAttribute(rawXml, "type") ?: return null
        if (!isMarkerType(type)) return null
        val uid = eventAttribute(rawXml, "uid") ?: return null
        val time = eventAttribute(rawXml, "time") ?: return null
        val start = eventAttribute(rawXml, "start") ?: time
        val stale = eventAttribute(rawXml, "stale") ?: time

        return CotMarkerEvent(
            version = eventAttribute(rawXml, "version") ?: "",
            uid = uid,
            type = type,
            how = eventAttribute(rawXml, "how") ?: "",
            time = parseIsoToEpochMs(time),
            start = parseIsoToEpochMs(start),
            stale = parseIsoToEpochMs(stale),
            access = eventAttribute(rawXml, "access"),
            point = pointOf(rawXml) ?: return null,
            detail = detailOf(rawXml),
        )
    }

    fun isUserPositionType(type: String): Boolean =
        type == "a-f-G-U-C" || type.startsWith("a-f-G-U-C-")

    fun isMarkerType(type: String): Boolean =
        MarkerType.entries.any { it.type == type } || type == "b-m-p-s-p-i"

    fun eventTypeOrNull(rawXml: String): String? = eventAttribute(rawXml, "type")

    fun eventUidOrNull(rawXml: String): String? = eventAttribute(rawXml, "uid")

    fun eventTimeOrNull(rawXml: String): Long? =
        eventAttribute(rawXml, "time")
            ?.let(::parseIsoToEpochMs)
            ?.takeIf { it > 0L }

    fun ingressDiagnostics(rawXml: String): CotIngressDiagnostics = CotIngressDiagnostics(
        uid = eventUidOrNull(rawXml),
        type = eventTypeOrNull(rawXml),
        eventTime = eventTimeOrNull(rawXml),
        teamName = extractAttribute(rawXml, "__group", "name"),
        teamRole = extractAttribute(rawXml, "__group", "role"),
        flowTagNames = flowTagNames(rawXml),
        explicitChannelStamps = explicitChannelStamps(rawXml)
    )

    fun isChatMessage(rawXml: String): Boolean =
        rawXml.contains("<__chat") && rawXml.contains("<remarks")

    fun isChatForRecipient(rawXml: String, myUid: String, myCallsign: String): Boolean =
        rawXml.contains("to=\"$myCallsign\"") ||
            rawXml.contains("to='$myUid'") ||
            rawXml.contains("to=\"$myUid\"") ||
            isAllChatNotSentByMe(rawXml, myCallsign)

    fun parseChatRooms(
        rawXml: String,
        myUid: String,
        myCallsign: String,
        sourceServerId: String? = null,
        receivedAt: Long = System.currentTimeMillis()
    ): List<ChatRoom> {
        val document = parseDocumentOrNull(rawXml)
        if (document == null) {
            return parseChatRoomsWithRegex(rawXml, myUid, sourceServerId, receivedAt)
        }

        val rooms = mutableListOf<ChatRoom>()
        val chatElements = document.getElementsByTagName("__chat")
        val remarkElements = document.getElementsByTagName("remarks")
        for (chatIndex in 0 until chatElements.length) {
            val chat = chatElements.item(chatIndex) as? Element ?: continue
            val group = chat.getElementsByTagName("chatgrp").item(0) as? Element
            val senderUid = group?.getAttribute("uid0") ?: continue
            val senderCallsign = chat.getAttribute("senderCallsign")
            for (remarkIndex in 0 until remarkElements.length) {
                val remark = remarkElements.item(remarkIndex) as? Element ?: continue
                val recipient = remark.getAttribute("to")
                if (recipient == myCallsign || recipient == myUid || recipient == ALL_CHAT_ROOMS) {
                    rooms += chatRoom(
                        senderUid,
                        senderCallsign,
                        remark.textContent.trim(),
                        sourceServerId,
                        receivedAt
                    )
                }
            }
        }
        return rooms
    }

    private fun isAllChatNotSentByMe(rawXml: String, myCallsign: String): Boolean {
        val toAll = rawXml.contains("to='$ALL_CHAT_ROOMS'") || rawXml.contains("to=\"$ALL_CHAT_ROOMS\"")
        val sentByMe = rawXml.contains("senderCallsign='$myCallsign'") || rawXml.contains("senderCallsign=\"$myCallsign\"")
        return toAll && !sentByMe
    }

    private fun detailOf(rawXml: String) = CotMarkerDetail(
        markerCallsign = extractAttribute(rawXml, "contact", "callsign") ?: "",
        creator = creatorOf(rawXml),
        link = linkOf(rawXml),
        usericon = null,
        remarks = extractElementText(rawXml, "remarks"),
        contact = null,
    )

    private fun pointOf(rawXml: String): CotPoint? {
        val lat = extractAttribute(rawXml, "point", "lat")?.toDoubleOrNull() ?: return null
        val lon = extractAttribute(rawXml, "point", "lon")?.toDoubleOrNull() ?: return null
        val hae = extractAttribute(rawXml, "point", "hae")?.toDoubleOrNull() ?: return null
        val ce = extractAttribute(rawXml, "point", "ce")?.toDoubleOrNull() ?: return null
        val le = extractAttribute(rawXml, "point", "le")?.toDoubleOrNull() ?: return null
        return CotPoint(lat, lon, hae, ce, le)
    }

    private fun creatorOf(rawXml: String) = CotCreator(
        uid = extractAttribute(rawXml, "creator", "uid"),
        callsign = extractAttribute(rawXml, "creator", "callsign"),
        time = extractAttribute(rawXml, "creator", "time")?.let(::parseIsoToEpochMs),
        type = extractAttribute(rawXml, "creator", "type"),
    )

    private fun linkOf(rawXml: String) = CotLink(
        uid = extractAttribute(rawXml, "link", "uid"),
        productionTime = extractAttribute(rawXml, "link", "production_time")?.let(::parseIsoToEpochMs),
        type = extractAttribute(rawXml, "link", "type"),
        parentCallsign = extractAttribute(rawXml, "link", "parent_callsign"),
        relation = extractAttribute(rawXml, "link", "relation"),
    )

    private fun parseChatRoomsWithRegex(
        rawXml: String,
        myUid: String,
        sourceServerId: String?,
        receivedAt: Long
    ): List<ChatRoom> {
        val senderUid = """<chatgrp[^>]*uid0=['"]([^'"]+)['"]""".toRegex().find(rawXml)?.groupValues?.get(1)
            ?: return emptyList()
        val senderCallsign = """<__chat[^>]*senderCallsign=['"]([^'"]+)['"]""".toRegex().find(rawXml)?.groupValues?.get(1)
            ?: return emptyList()
        return """<remarks[^>]*to=['"]([^'"]+)['"][^>]*>(.*?)</remarks>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
            .findAll(rawXml)
            .filter { it.groupValues[1] == myUid || it.groupValues[1] == ALL_CHAT_ROOMS }
            .map {
                chatRoom(
                    senderUid,
                    senderCallsign,
                    it.groupValues[2].trim(),
                    sourceServerId,
                    receivedAt
                )
            }
            .toList()
    }

    private fun chatRoom(
        senderUid: String,
        senderCallsign: String,
        message: String,
        sourceServerId: String?,
        receivedAt: Long
    ) = ChatRoom(
        uid = senderUid,
        title = senderCallsign,
        messageList = listOf(
            ChatMessage(
                message = message,
                uid = senderUid,
                callsign = senderCallsign,
                timeReceived = receivedAt,
                sourceServerId = sourceServerId
            )
        ),
        newMessagesPresent = true,
        timeLastOpened = 0L,
        timeLastClosed = 0L,
    )

    private fun parseDocumentOrNull(rawXml: String) = try {
        val cleaned = rawXml.trimStart { it <= ' ' || it == '\uFEFF' }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            trySetFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            trySetAttribute(ACCESS_EXTERNAL_DTD, "")
            trySetAttribute(ACCESS_EXTERNAL_SCHEMA, "")
            trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }.parse(InputSource(StringReader(cleaned)))
    } catch (_: Exception) {
        null
    }

    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Some Android XML implementations do not expose every hardening feature.
        }
    }

    private fun DocumentBuilderFactory.trySetAttribute(name: String, value: String) {
        try {
            setAttribute(name, value)
        } catch (_: Exception) {
            // Some Android XML implementations do not expose every hardening attribute.
        }
    }

    private fun eventAttribute(xml: String, name: String): String? = extractAttribute(xml, "event", name)

    private fun extractAttribute(xml: String, elementName: String, attributeName: String): String? {
        val pattern = """<${Regex.escape(elementName)}\b[^>]*?\b${Regex.escape(attributeName)}\s*=\s*(['"])(.*?)\1"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        return pattern.find(xml)?.groupValues?.getOrNull(2)
    }

    private fun extractElementText(xml: String, elementName: String): String? {
        val pattern = """<${Regex.escape(elementName)}\b[^>]*>(.*?)</${Regex.escape(elementName)}>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return pattern.find(xml)?.groupValues?.get(1)?.trim()
    }

    private fun flowTagNames(rawXml: String): List<String> {
        val flowTag = TAG_PATTERN.findAll(rawXml)
            .map(MatchResult::value)
            .firstOrNull { it.startsWith("<_flow-tags_", ignoreCase = true) }
            ?: return emptyList()
        return ATTRIBUTE_PATTERN.findAll(flowTag)
            .map { it.groupValues[1] }
            .filterNot { it.equals("_flow-tags_", ignoreCase = true) }
            .distinct()
            .toList()
    }

    private fun explicitChannelStamps(rawXml: String): List<String> =
        TAG_PATTERN.findAll(rawXml)
            .flatMap { tag -> CHANNEL_ATTRIBUTE_PATTERN.findAll(tag.value) }
            .map { match -> "${match.groupValues[1]}=${match.groupValues[3]}" }
            .distinct()
            .toList()

    private fun parseIsoToEpochMs(value: String): Long {
        try {
            return Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            val withZulu = if (value.endsWith("Z", ignoreCase = true)) value else "${value}Z"
            try {
                return Instant.parse(withZulu).toEpochMilli()
            } catch (_: Exception) {
                return try {
                    Instant.parse(withZulu.substringBefore('.', withZulu)).toEpochMilli()
                } catch (_: Exception) {
                    0L
                }
            }
        }
    }

    private const val ALL_CHAT_ROOMS = "All Chat Rooms"
    private val TAG_PATTERN = """<[^>]+>""".toRegex()
    private val ATTRIBUTE_PATTERN =
        """\b([A-Za-z_][A-Za-z0-9_.:-]*)\s*=\s*(['"])(.*?)\2""".toRegex()
    private val CHANNEL_ATTRIBUTE_PATTERN =
        """\b(channel|channelId|channelUid|channelName|bitpos)\s*=\s*(['"])(.*?)\2"""
            .toRegex(RegexOption.IGNORE_CASE)
}
