package com.aitech.weartak_civ

import com.aitech.weartak_civ.cot.CotMessageParser

data class MulticastEndpoint(val address: String, val port: Int) {
    init {
        require(address.isNotBlank()) { "Multicast address cannot be blank" }
        require(port in 1..65535) { "Invalid multicast UDP port: $port" }
    }

    val sourceId: String get() = "multicast:$address:$port"
}

object TakMulticastEndpoints {
    val chat = MulticastEndpoint("224.10.10.1", 17012)
    // ATAK uses port 6969 for non-chat CoT sent to a discovered UDP contact.
    val directCot = MulticastEndpoint(chat.address, 6969)
    private val contactTag = Regex("""<contact\b[^>]*>""")
    private val contactEndpoint = Regex("""\bendpoint\s*=\s*(['"])[^'"]*\1""")

    fun prepareOutbound(xml: String): String {
        val type = CotMessageParser.eventTypeOrNull(xml) ?: return xml
        if (!CotMessageParser.isUserPositionType(type)) return xml
        return contactTag.replace(xml) { contact ->
            contactEndpoint.replace(contact.value) {
                "endpoint='${chat.address}:${chat.port}:udp'"
            }
        }
    }

    fun receiveEndpoints(address: String, port: Int): List<MulticastEndpoint> =
        listOf(MulticastEndpoint(address, port), chat, directCot).distinct()

    fun outbound(xml: String, address: String, port: Int): MulticastEndpoint =
        if (CotMessageParser.isChatMessage(xml)) chat else MulticastEndpoint(address, port)
}
