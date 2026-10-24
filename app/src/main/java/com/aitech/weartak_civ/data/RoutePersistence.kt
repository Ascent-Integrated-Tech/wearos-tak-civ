package com.aitech.weartak_civ.data

import android.content.SharedPreferences
import android.util.Log
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Stable on-disk schema, independent of release obfuscation and CoT export timestamps. */
internal class RoutePersistence(private val preferences: SharedPreferences) {
    data class Catalog(
        val routes: List<TakRoute> = emptyList(),
        val removals: Map<Pair<String, String>, Long> = emptyMap()
    )

    fun load(now: Long): Catalog = read("catalog") { json ->
        val routes = json.getJSONArray("routes")
        val removals = json.getJSONArray("removals")
        Catalog(
            (0 until routes.length()).map { decodeRoute(routes.getJSONObject(it)) }
                .filter { it.staleTime == null || it.staleTime > now },
            (0 until removals.length()).associate {
                val entry = removals.getJSONObject(it)
                (entry.getString("source") to entry.getString("uid")) to entry.getLong("time")
            }
        )
    } ?: Catalog()

    fun save(routes: Collection<TakRoute>, removals: Map<Pair<String, String>, Long>) {
        val json = JSONObject().put("routes", JSONArray().also { array ->
            // A missing CoT stale is not permission to keep a received route forever.
            routes.filter { it.staleTime != null }.forEach { array.put(encodeRoute(it)) }
        }).put("removals", JSONArray().also { array ->
            removals.forEach { (key, time) ->
                array.put(JSONObject().put("source", key.first).put("uid", key.second).put("time", time))
            }
        })
        write("catalog", json.toString())
    }

    fun saveSession(session: RouteFollowSession?) {
        val resumable = session?.takeIf {
            it.phoneSessionId == null && !it.navigation.completed && it.route.staleTime != null
        }
        write("session", resumable?.let {
            JSONObject().put("route", encodeRoute(it.route))
                .put("index", it.navigation.currentIndex)
                .put("mode", it.navigation.mode.name)
                .put("arrived", JSONArray(it.navigation.arrived.toList()))
                .put("localSessionId", it.localSessionId).toString()
        })
    }

    fun restoreSession(routes: List<TakRoute>, now: Long): RouteFollowSession? = read("session") { json ->
        val saved = decodeRoute(json.getJSONObject("route"))
        val current = routes.firstOrNull {
            it.uid == saved.uid && (it.serverId == saved.serverId || (it.eventTime != null && saved.eventTime != null)) &&
                it.points == saved.points && it.method == saved.method
        } ?: return@read null
        if (current.staleTime == null || current.staleTime <= now) return@read null
        val index = json.getInt("index")
        val arrived = json.getJSONArray("arrived")
        require(index in current.points.indices && (current.points[index].isCheckpoint || index == current.points.lastIndex))
        // NEAREST needs a fix only when choosing the initial index, not when recovering it.
        val navigation = RouteFollow.start(current.points, RouteFollow.StartMode.valueOf(json.getString("mode")),
            current.points[index], current.method).atIndex(index,
            arrivedCheckpoints = (0 until arrived.length()).map { arrived.getInt(it) }.toSet())
        RouteFollowSession(current, navigation, null, localSessionId = json.getString("localSessionId"),
            recoveredLocally = true)
    }

    private fun write(key: String, value: String?) {
        if (preferences.getString(key, null) == value) return
        if (!preferences.edit().putString(key, value).commit()) {
            Log.e("RoutePersistence", "Unable to persist route $key")
            throw IllegalStateException("Unable to persist route $key")
        }
    }

    private fun <T> read(key: String, decode: (JSONObject) -> T): T? {
        val raw = preferences.getString(key, null) ?: return null
        return try {
            decode(JSONObject(raw))
        } catch (e: JSONException) {
            Log.e("RoutePersistence", "Invalid persisted route $key", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.e("RoutePersistence", "Invalid persisted route $key", e)
            null
        }
    }

    private fun encodeRoute(route: TakRoute): JSONObject = JSONObject()
        .put("uid", route.uid).put("name", route.name).put("source", route.serverId)
        .put("mission", route.missionName).put("sender", route.sender)
        .put("time", route.eventTime).put("stale", route.staleTime).put("method", route.method)
        .put("points", JSONArray().also { array ->
            route.points.forEach { point ->
                array.put(JSONObject().put("lat", point.latitude).put("lon", point.longitude)
                    .put("alt", point.altitude).put("name", point.name)
                    .put("checkpoint", point.isCheckpoint).put("uid", point.uid))
            }
        })

    private fun decodeRoute(json: JSONObject): TakRoute {
        fun JSONObject.optionalString(key: String) = if (has(key) && !isNull(key)) getString(key) else null
        val points = json.getJSONArray("points")
        return TakRoute(
            json.getString("uid"), json.getString("name"),
            (0 until points.length()).map { index ->
                val point = points.getJSONObject(index)
                TakRoutePoint(point.getDouble("lat"), point.getDouble("lon"),
                    if (point.has("alt")) point.getDouble("alt") else null,
                    point.optionalString("name"), point.getBoolean("checkpoint"), point.optionalString("uid"))
            },
            json.getString("source"), json.optionalString("mission"), json.optionalString("sender"),
            if (json.has("time")) json.getLong("time") else null, json.getString("method"),
            json.getLong("stale")
        ).also { route ->
            require(route.uid.isNotBlank() && route.points.isNotEmpty() && route.points.all(RouteFollow::validLocation))
        }
    }
}
