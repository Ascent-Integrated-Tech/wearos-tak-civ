package com.aitech.weartak_civ.service

import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.UUID

/** Called serially by the companion service; remote changes never generate echo commands. */
internal class CompanionBloodhoundSync(
    private val activeTarget: () -> Target?,
    private val startTracking: (Target) -> Boolean,
    private val stopTracking: (String) -> Boolean,
    private val send: (String) -> Boolean,
    private val reportError: (String) -> Unit,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val updateTracking: (Target) -> Boolean = { false }
) {
    data class Target(val uid: String, val callsign: String, val lat: Double, val lon: Double, val hae: Double) {
        fun isValid(): Boolean = uid.isNotBlank() && lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0 && hae.isFinite()
    }

    private data class Session(val id: String, val target: Target)
    private data class Pending(val id: String, val action: String, val deadline: Long)
    private var connected = false
    private var observedTarget: Target? = null
    private var session: Session? = null
    private val retired = LinkedHashMap<String, String>()
    private val pending = mutableListOf<Pending>()

    fun connectionChanged(ready: Boolean) {
        if (connected == ready) return
        connected = ready
        session?.let { retire(it.id, it.target.uid) }
        session = null
        pending.clear()
        observedTarget = activeTarget()
    }

    fun localTargetChanged() {
        val target = activeTarget()
        if (target == observedTarget) return
        val sameUid = target?.uid == observedTarget?.uid
        observedTarget = target
        if (!connected) return
        if (sameUid) {
            val current = session
            if (target != null && current?.target?.uid == target.uid && target.isValid()) {
                session = current.copy(target = target)
                if (!send(envelope("bloodhound_control", targetPayload(current.id, "update", target)))) {
                    reportError("Could not update companion Bloodhound target")
                }
            }
            return
        }
        session?.let { previous ->
            sendCommand(previous, "stop")
            retire(previous.id, previous.target.uid)
        }
        session = null
        if (target != null) {
            if (!target.isValid()) {
                reportError("Tracking target has no valid location")
                return
            }
            val next = Session(newSessionId(), target)
            if (sendCommand(next, "start")) session = next
        }
    }

    fun receiveControl(payload: JSONObject) {
        if (!connected) {
            reportError("Bloodhound command received without a ready companion connection")
            return
        }
        val id = payload.optString("session_id")
        val action = payload.optString("action")
        val uid = payload.optString("target_uid")
        if (id.isBlank() || uid.isBlank() || action !in listOf("start", "stop", "update")) {
            reportError("Invalid companion Bloodhound command")
            if (id.isNotBlank()) result(id, action, false, "Invalid tracking command")
            return
        }
        if (action == "stop") {
            val current = session
            if (current?.id != id || current.target.uid != uid) {
                val wasRetiredForTarget = retired[id] == uid
                result(
                    id,
                    action,
                    wasRetiredForTarget,
                    if (wasRetiredForTarget) null else "Tracking session is not active"
                )
                return
            }
            // A delayed stop must not affect a local replacement that has not yet been observed.
            if (activeTarget()?.uid != uid) {
                retire(id, current.target.uid)
                session = null
                result(id, action, false, "Tracking target has changed")
                return
            }
            if (!stopTracking(uid)) {
                result(id, action, false, "Could not stop tracking")
                reportError("Could not stop companion Bloodhound tracking")
                return
            }
            retire(id, current.target.uid)
            session = null
            observedTarget = activeTarget()
            result(id, action, true)
            return
        }
        if (id in retired) {
            if (action != "update") result(id, action, false, "Tracking session has ended")
            return
        }
        val target = Target(
            uid, payload.optString("target_callsign", uid),
            payload.optDouble("lat", Double.NaN), payload.optDouble("lon", Double.NaN),
            payload.optDouble("hae", 0.0)
        )
        if (!target.isValid()) {
            if (action != "update") result(id, action, false, "Tracking target has no valid location")
            reportError("Phone Bloodhound target has no valid location")
            return
        }
        val current = session
        if (action == "update") {
            if (current?.id != id || current.target.uid != uid || activeTarget()?.uid != uid) return
            if (!updateTracking(target)) {
                reportError("Could not update phone Bloodhound target on watch")
                return
            }
            observedTarget = activeTarget()
            session = current.copy(target = observedTarget ?: target)
            return
        }
        if (current?.id == id) {
            val matches = current.target.uid == uid && activeTarget()?.uid == uid
            result(id, action, matches, if (matches) null else "Tracking session target mismatch")
            return
        }
        if (current != null && pending.any { it.id == current.id && it.action == "start" } &&
            current.id < id
        ) {
            retire(id, uid)
            result(id, action, false, "Superseded by simultaneous tracking start")
            return
        }
        if (!startTracking(target)) {
            result(id, action, false, "Could not activate tracking target")
            reportError("Could not start phone Bloodhound target on watch")
            return
        }
        current?.let { retire(it.id, it.target.uid) }
        session = Session(id, target)
        observedTarget = activeTarget()
        result(id, action, true)
    }

    fun receiveResult(payload: JSONObject) {
        val id = payload.optString("session_id")
        val action = payload.optString("action")
        val request = pending.firstOrNull { it.id == id && it.action == action } ?: return
        val ok = payload.opt("ok") as? Boolean
        if (ok == null) {
            reportError("Invalid companion Bloodhound acknowledgement")
            return
        }
        pending.remove(request)
        if (!ok) {
            if (action == "start" && session?.id == id) {
                val uid = session?.target?.uid
                session = null
                if (uid != null) retire(id, uid)
            }
            reportError(payload.optString("error", "Companion rejected Bloodhound $action"))
        }
    }

    fun checkTimeouts() {
        val expired = pending.filter { it.deadline <= now() }
        pending.removeAll(expired.toSet())
        expired.forEach {
            if (it.action == "start" && session?.id == it.id) {
                val uid = session?.target?.uid
                session = null
                if (uid != null) retire(it.id, uid)
            }
            reportError("Companion did not confirm Bloodhound ${it.action}; tracking may not be synchronized")
        }
    }

    private fun sendCommand(value: Session, action: String): Boolean {
        val payload = targetPayload(value.id, action, value.target)
        if (!send(envelope("bloodhound_control", payload))) {
            reportError("Could not send companion Bloodhound $action")
            return false
        }
        pending.add(Pending(value.id, action, now() + 10_000L))
        return true
    }

    private fun result(id: String, action: String, ok: Boolean, error: String? = null) {
        val payload = JSONObject().put("session_id", id).put("action", action).put("ok", ok)
        error?.let { payload.put("error", it) }
        if (!send(envelope("bloodhound_control_result", payload))) {
            reportError("Could not acknowledge companion Bloodhound $action")
        }
    }

    private fun retire(id: String, uid: String) {
        pending.removeAll { it.id == id && it.action == "start" }
        retired[id] = uid
        while (retired.size > 256) retired.remove(retired.keys.first())
    }

    private fun envelope(type: String, payload: JSONObject): String =
        JSONObject().put("msg_type", type).put("payload", payload).toString()

    private fun targetPayload(id: String, action: String, target: Target): JSONObject =
        JSONObject().put("action", action).put("session_id", id).put("target_uid", target.uid).apply {
            if (action != "stop") {
                put("target_callsign", target.callsign).put("lat", target.lat).put("lon", target.lon)
                    .put("hae", target.hae)
            }
        }
}
