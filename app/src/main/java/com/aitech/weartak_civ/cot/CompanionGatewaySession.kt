package com.aitech.weartak_civ.cot

import org.json.JSONObject

/** Transient BLE route, never a DataStore/profile setting. */
class CompanionGatewaySession {
    @Volatile var session: String? = null
        private set
    @Volatile var ready: Boolean = false
        private set
    private data class Version(val time: Long, val deleted: Boolean)
    private val received = LinkedHashMap<String, Version>()

    @Synchronized fun reset() { ready = false; session = null; received.clear() }
    @Synchronized fun confirm(token: String): Boolean {
        if (session != token) return false
        ready = true
        return true
    }

    @Synchronized fun negotiate(message: JSONObject): JSONObject {
        require(message.getInt("version") == 1) { "Unsupported gateway protocol version" }
        val token = message.getString("session")
        require(token.matches(Regex("[a-zA-Z0-9-]{1,64}"))) { "Invalid gateway session" }
        require(session == null || session == token) { "Gateway session already negotiated" }
        session = token
        return JSONObject().put("msgType", "gateway_ready").put("version", 1)
            .put("session", token).put("mapPosition", true)
            .put("emergency", true).put("chat", true)
    }

    @Synchronized fun accepts(message: JSONObject): String {
        require(ready && session != null && message.getString("session") == session) { "Ended gateway session" }
        val xml = message.getString("cot")
        require(xml.toByteArray(Charsets.UTF_8).size <= 32768) { "Gateway CoT exceeds 32 KiB" }
        require(supported(xml)) { "Unsupported gateway CoT" }
        return xml
    }

    @Synchronized fun envelope(xml: String): String {
        check(ready) { "Gateway acknowledgement not queued" }
        require(supported(xml)) { "Unsupported gateway CoT" }
        require(xml.toByteArray(Charsets.UTF_8).size <= 32768) { "Gateway CoT exceeds 32 KiB" }
        return JSONObject().put("msgType", "gateway_cot").put("session",
            session ?: error("Gateway not negotiated")).put("cot", xml).toString()
    }

    @Synchronized fun acceptEvent(uid: String, time: Long, deleted: Boolean = false): Boolean {
        require(uid.isNotBlank() && time > 0L) { "Invalid Gateway event identity or time" }
        val previous = received[uid]
        if (previous != null && (time < previous.time || (time == previous.time && (!deleted || previous.deleted)))) return false
        received[uid] = Version(time, deleted)
        while (received.size > MAX_RECEIVED_EVENTS)
            received.remove(received.keys.iterator().next())
        return true
    }

    companion object {
        fun supported(xml: String): Boolean {
            if (xml.toByteArray(Charsets.UTF_8).size > 32768) return false
            if (CotMessageParser.parseDeletionOrNull(xml) != null) return true
            val marker = CotMessageParser.parseMarkerOrNull(xml)
            if (marker != null) return true
            val position = CotMessageParser.parseUserPositionOrNull(xml)
            if (position != null) {
                return position.uid.isNotBlank() && position.time > 0 && position.start > 0 &&
                    position.stale > 0 && position.point.lat.isFinite() && position.point.lat in -90.0..90.0 &&
                    position.point.lon.isFinite() && position.point.lon in -180.0..180.0 &&
                    position.point.hae.isFinite() && position.point.ce.isFinite() && position.point.le.isFinite()
            }
            if (CotMessageParser.parseEmergencyOrNull(xml) != null) return true
            return CotMessageParser.isValidGatewayChat(xml)
        }

        private const val MAX_RECEIVED_EVENTS = 512
    }
}
