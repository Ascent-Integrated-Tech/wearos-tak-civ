package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.parsers.TakRouteCotParser
import org.json.JSONObject

/** Self-contained phone-owned navigation snapshots; indices refer to all CoT link vertices. */
data class CompanionRouteNavigation(
    val sessionId: String,
    val sequence: Long,
    val action: String,
    val routeUid: String,
    val route: TakRoute?,
    val targetPointIndex: Int?,
    val completed: Boolean
) {
    companion object {
        fun parse(payload: JSONObject): CompanionRouteNavigation {
            require(payload.getInt("version") == 1) { "Unsupported route navigation version" }
            fun identifier(name: String): String = payload.getString(name).also {
                require(it.isNotBlank() && it.length <= 512 && !it.any(Char::isISOControl)) {
                    "Invalid route navigation $name"
                }
            }
            val sessionId = identifier("sessionId")
            val routeUid = identifier("routeUid")
            val sequence = payload.getLong("sequence")
            require(sequence >= 0) { "Invalid route navigation sequence" }
            val action = payload.getString("action")
            require(action in setOf("start", "progress", "stop")) { "Invalid route navigation action" }
            if (action == "stop") {
                return CompanionRouteNavigation(sessionId, sequence, action, routeUid, null, null,
                    payload.optBoolean("completed", false))
            }
            val xml = payload.getString("routeCot")
            require(xml.length <= 262144) { "Route navigation CoT too large" }
            val route = requireNotNull(TakRouteCotParser.parse(xml, "companion-phone", strictPoints = true)) {
                "Invalid route navigation CoT"
            }
            require(route.uid == routeUid && route.points.size <= 10000) { "Route navigation geometry mismatch" }
            val index = payload.getInt("targetPointIndex")
            require(index in route.points.indices) { "Route navigation target out of range" }
            return CompanionRouteNavigation(sessionId, sequence, action, routeUid, route, index, false)
        }
    }
}
