package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.data.CompanionRouteNavigation
import com.aitech.weartak_civ.data.RouteFollowSession
import org.json.JSONObject
import java.util.UUID

/** Serialized by the service's companion lock; phone snapshots never echo as commands. */
internal class CompanionRouteSync(
    private val activeRoute: () -> RouteFollowSession?,
    private val prepare: (String) -> Unit,
    private val resumeLocal: (String) -> Unit,
    private val send: (String) -> Boolean,
    private val reportError: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private data class Session(val id: String, val uid: String)
    private data class Pending(val requestId: String, val session: Session, val start: Boolean, val deadline: Long)
    private var connected = false
    private var session: Session? = null
    private var ignoredLocalId: String? = null
    private val pending = mutableListOf<Pending>()

    fun connectionChanged(ready: Boolean) {
        if (connected == ready) return
        if (!ready) pending.filter { it.start }.forEach { resumeLocal(it.session.id) }
        connected = ready
        session = null
        ignoredLocalId = null
        pending.clear()
    }

    fun localRouteChanged() {
        if (!connected) return
        val route = activeRoute()?.takeUnless { it.navigation.completed }
        // A restart resumes only local progress, not a new navigation command on ATAK.
        if (route?.recoveredLocally == true) return
        if (route?.localSessionId == ignoredLocalId && route != null) return
        if (route?.phoneSessionId != null && session?.id == route.phoneSessionId) return
        session?.let { command(it, start = false) }
        session = null
        if (route == null) return
        // A snapshot that arrived before this collector is already phone-owned.
        route.phoneSessionId?.let { session = Session(it, route.route.uid); return }
        val next = Session(newId(), route.route.uid)
        session = next
        prepare(next.id)
        if (!command(next, start = true, route = route)) {
            session = null
            ignoredLocalId = route.localSessionId
            resumeLocal(next.id)
        }
    }

    fun phoneSnapshot(update: CompanionRouteNavigation) {
        session?.takeIf { update.action != "stop" && it.id != update.sessionId &&
            pending.any { request -> request.start && request.session == it } }?.let {
            command(it, start = false)
        }
        if (update.action == "stop") {
            if (session?.id == update.sessionId) session = null
        } else {
            session = Session(update.sessionId, update.routeUid)
        }
        pending.removeAll { it.start && it.session.id == update.sessionId }
    }

    fun receiveAck(payload: JSONObject) {
        val request = pending.firstOrNull { it.requestId == payload.optString("requestId") } ?: return
        require(payload.opt("ok") is Boolean && payload.getString("sessionId") == request.session.id) {
            "Invalid route navigation acknowledgement"
        }
        if (payload.getBoolean("ok")) {
            if (!request.start) pending.remove(request)
            return
        }
        pending.remove(request)
        if (request.start && session == request.session) {
            session = null
            ignoredLocalId = activeRoute()?.localSessionId
            resumeLocal(request.session.id)
        }
        reportError(payload.optString("error", "Phone rejected route navigation"))
    }

    fun checkTimeouts() {
        val expired = pending.filter { it.deadline <= now() }
        pending.removeAll(expired.toSet())
        expired.forEach { request ->
            if (request.start && session == request.session) {
                session = null
                ignoredLocalId = activeRoute()?.localSessionId
                resumeLocal(request.session.id)
                command(request.session, start = false)
            }
            reportError("Phone did not confirm route ${if (request.start) "start" else "stop"}; navigation may not be synchronized")
        }
    }

    private fun command(value: Session, start: Boolean, route: RouteFollowSession? = null): Boolean {
        if (!start) pending.removeAll { it.start && it.session.id == value.id }
        val requestId = newId()
        val payload = JSONObject().put("version", 1).put("requestId", requestId)
            .put("sessionId", value.id).put("routeUid", value.uid).put("action", if (start) "start" else "stop")
        if (route != null) {
            payload.put("targetPointIndex", route.navigation.currentIndex)
                .put("routeCot", com.aitech.weartak_civ.data.parsers.TakRouteCotParser.serialize(route.route))
        }
        val json = JSONObject().put("msg_type", "watch_route_navigation").put("payload", payload).toString()
        if (json.toByteArray(Charsets.UTF_8).size > 60 * 1024 || !send(json)) {
            reportError("Could not send route ${if (start) "start" else "stop"} to phone")
            return false
        }
        pending.add(Pending(requestId, value, start, now() + 8000L))
        return true
    }
}
