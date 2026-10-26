package com.aitech.weartak_civ.cot

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** TAK Protocol v1 field numbers follow the public TAK takproto schemas. */
object TakDatagramDecoder {
    fun decode(data: ByteArray, offset: Int, length: Int): String? {
        require(offset >= 0 && length >= 0 && offset <= data.size - length)
        if (length == 0) throw IOException("Empty CoT datagram")
        if (data[offset].toInt() and 0xff != 0xbf) {
            return String(data, offset, length, Charsets.UTF_8)
        }
        if (length < 3 || data[offset + 2].toInt() and 0xff != 0xbf) {
            throw IOException("Invalid TAK datagram header")
        }
        val version = data[offset + 1].toInt() and 0xff
        if (version != 1) throw IOException("Unsupported TAK datagram version: $version")
        val message = readFields(CodedInputStream.newInstance(data, offset + 3, length - 3),
            strings = emptySet(), integers = emptySet(), doubles = emptySet(), messages = setOf(1, 2))
        val event = message.messages[2] ?: return null // Control-only TAK message.
        return eventXml(event)
    }

    private class Fields {
        val strings = mutableMapOf<Int, String>()
        val integers = mutableMapOf<Int, Long>()
        val doubles = mutableMapOf<Int, Double>()
        val messages = mutableMapOf<Int, ByteArray>()
    }

    private fun readFields(
        input: CodedInputStream,
        strings: Set<Int>,
        integers: Set<Int> = emptySet(),
        doubles: Set<Int> = emptySet(),
        messages: Set<Int> = emptySet()
    ): Fields {
        val fields = Fields()
        while (!input.isAtEnd) {
            val tag = input.readTag()
            val field = WireFormat.getTagFieldNumber(tag)
            val expectedWire = when (field) {
                in strings, in messages -> WireFormat.WIRETYPE_LENGTH_DELIMITED
                in integers -> WireFormat.WIRETYPE_VARINT
                in doubles -> WireFormat.WIRETYPE_FIXED64
                else -> null
            }
            if (expectedWire != null && WireFormat.getTagWireType(tag) != expectedWire) {
                throw IOException("Invalid protobuf wire type for field $field")
            }
            when (field) {
                in strings -> fields.strings[field] = input.readStringRequireUtf8()
                in integers -> fields.integers[field] = input.readUInt64()
                in doubles -> fields.doubles[field] = input.readDouble()
                in messages -> fields.messages[field] = input.readByteArray()
                else -> if (!input.skipField(tag)) throw IOException("Unexpected protobuf end group")
            }
        }
        return fields
    }

    private fun eventXml(bytes: ByteArray): String {
        val fields = readFields(CodedInputStream.newInstance(bytes),
            strings = setOf(1, 2, 3, 4, 5, 9), integers = setOf(6, 7, 8),
            doubles = (10..14).toSet(), messages = setOf(15))
        if (fields.strings[1].isNullOrBlank() || fields.strings[5].isNullOrBlank()) {
            throw IOException("TAK event is missing type or UID")
        }
        val detailFields = fields.messages[15]?.let {
            readFields(CodedInputStream.newInstance(it), strings = setOf(1), messages = (2..7).toSet())
        } ?: Fields()
        val opaqueDetail = detailFields.strings[1].orEmpty()
        if (opaqueDetail.contains("<!DOCTYPE", ignoreCase = true) ||
            opaqueDetail.contains("<!ENTITY", ignoreCase = true)) {
            throw IOException("DTD/entity declarations are not permitted in TAK detail")
        }
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("External entities are not permitted") }
        builder.setErrorHandler(org.xml.sax.helpers.DefaultHandler())
        val document = builder.parse(InputSource(StringReader(
            "<detail>$opaqueDetail</detail>"
        )))
        val detail = document.documentElement
        val event = document.createElement("event")
        event.setAttribute("version", "2.0")
        for ((field, name) in mapOf(1 to "type", 2 to "access", 3 to "qos", 4 to "opex", 5 to "uid", 9 to "how")) {
            fields.strings[field]?.takeIf { it.isNotEmpty() }?.let { event.setAttribute(name, it) }
        }
        for ((field, name) in mapOf(6 to "time", 7 to "start", 8 to "stale")) {
            val time = fields.integers[field] ?: 0L
            if (time <= 0) throw IOException("TAK event has invalid $name timestamp")
            event.setAttribute(name, Instant.ofEpochMilli(time).toString())
        }
        val point = document.createElement("point")
        for ((field, name) in mapOf(10 to "lat", 11 to "lon", 12 to "hae", 13 to "ce", 14 to "le")) {
            // Proto3 omits zero-valued coordinates.
            point.setAttribute(name, (fields.doubles[field] ?: 0.0).toString())
        }
        event.appendChild(point)
        val schemas = listOf(
            Triple(2, "contact", mapOf(1 to "endpoint", 2 to "callsign")),
            Triple(3, "__group", mapOf(1 to "name", 2 to "role")),
            Triple(4, "precisionlocation", mapOf(1 to "geopointsrc", 2 to "altsrc")),
            Triple(5, "status", emptyMap()),
            Triple(6, "takv", mapOf(1 to "device", 2 to "platform", 3 to "os", 4 to "version")),
            Triple(7, "track", emptyMap())
        )
        for ((field, name, attributes) in schemas) {
            val nested = detailFields.messages[field] ?: continue
            // Per TAK protocol, opaque XML wins if a sender also supplies a typed equivalent.
            if ((0 until detail.childNodes.length).any {
                    (detail.childNodes.item(it) as? Element)?.tagName == name
                }) continue
            val values = readFields(CodedInputStream.newInstance(nested), strings = attributes.keys,
                integers = if (field == 5) setOf(1) else emptySet(),
                doubles = if (field == 7) setOf(1, 2) else emptySet())
            val element = document.createElement(name)
            for ((number, attribute) in attributes) {
                val value = values.strings[number].orEmpty()
                if (field != 2 || number != 1 || value.isNotEmpty()) element.setAttribute(attribute, value)
            }
            if (field == 5) element.setAttribute("battery", (values.integers[1] ?: 0L).toString())
            if (field == 7) {
                element.setAttribute("speed", (values.doubles[1] ?: 0.0).toString())
                element.setAttribute("course", (values.doubles[2] ?: 0.0).toString())
            }
            detail.appendChild(element)
        }
        document.removeChild(detail)
        event.appendChild(detail)
        document.appendChild(event)
        return StringWriter().also { output ->
            TransformerFactory.newInstance().newTransformer()
                .transform(DOMSource(document), StreamResult(output))
        }.toString()
    }
}
