package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.data.CompanionRouteNavigation
import com.aitech.weartak_civ.data.RouteFollow
import com.aitech.weartak_civ.data.RouteFollowSession
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompanionRouteSyncTest {
    private val route = TakRoute("route", "Rally", listOf(TakRoutePoint(0.0, 0.0)), "server")
    private var active: RouteFollowSession? = null
    private var now = 0L
    private var ids = 0
    private var sending = true
    private val sent = mutableListOf<JSONObject>()
    private val errors = mutableListOf<String>()
    private val sync = CompanionRouteSync(
        { active }, { active = active?.copy(phoneSessionId = it) },
        { id -> if (active?.phoneSessionId == id) active = active?.copy(phoneSessionId = null) },
        { sent += JSONObject(it); sending }, { errors += it }, { now }, { "id-${++ids}" })
    private fun start() {
        active = RouteFollowSession(route, RouteFollow.start(route.points, RouteFollow.StartMode.BEGINNING), null)
        sync.localRouteChanged()
    }
    private fun lastPayload() = sent.last().getJSONObject("payload")

    @Test fun bondedWatchStartSendsFullRouteAndPhoneSnapshotDoesNotEcho() {
        sync.connectionChanged(true)
        start()
        assertEquals("watch_route_navigation", sent.single().getString("msg_type"))
        assertEquals("start", lastPayload().getString("action"))
        assertTrue(lastPayload().getString("routeCot").contains("b-m-r"))
        val id = active!!.phoneSessionId!!
        sync.phoneSnapshot(CompanionRouteNavigation(id, 1, "start", route.uid, route, 0, false))
        sync.localRouteChanged()
        assertEquals(1, sent.size)
        active = null
        sync.localRouteChanged()
        assertEquals("stop", lastPayload().getString("action"))
        assertEquals(id, lastPayload().getString("sessionId"))
    }

    @Test fun offlineStartRemainsLocal() {
        start()
        assertTrue(sent.isEmpty())
        assertNull(active!!.phoneSessionId)
    }

    @Test fun recoveredStandaloneRouteNeverStartsPhoneNavigationOnReconnect() {
        active = RouteFollowSession(route, RouteFollow.start(route.points, RouteFollow.StartMode.BEGINNING),
            null, recoveredLocally = true)
        sync.connectionChanged(true)
        sync.localRouteChanged()
        sync.connectionChanged(false)
        sync.connectionChanged(true)
        sync.localRouteChanged()
        assertTrue(sent.isEmpty())
        assertNull(active!!.phoneSessionId)
        start()
        assertEquals("start", lastPayload().getString("action"))
    }

    @Test fun rejectionResumesLocalOnceWithoutRetryLoop() {
        sync.connectionChanged(true)
        start()
        val request = lastPayload()
        sync.receiveAck(JSONObject().put("requestId", request.getString("requestId"))
            .put("sessionId", request.getString("sessionId")).put("ok", false).put("error", "Missing route"))
        sync.localRouteChanged()
        assertNull(active!!.phoneSessionId)
        assertEquals(1, sent.size)
        assertEquals(listOf("Missing route"), errors)
        start()
        assertEquals(2, sent.size)
    }

    @Test fun timeoutCancelsPhoneStartAndResumesLocalEvenAfterAckWithoutSnapshot() {
        sync.connectionChanged(true)
        start()
        val request = lastPayload()
        sync.receiveAck(JSONObject().put("requestId", request.getString("requestId"))
            .put("sessionId", request.getString("sessionId")).put("ok", true))
        now = 8000
        sync.checkTimeouts()
        assertNull(active!!.phoneSessionId)
        assertEquals("stop", lastPayload().getString("action"))
        assertEquals(1, errors.size)
        sync.localRouteChanged()
        assertEquals(2, sent.size)
    }

    @Test fun stopBeforeAckStillScopesCancellationToProposedSession() {
        sync.connectionChanged(true)
        start()
        val id = active!!.phoneSessionId
        active = null
        sync.localRouteChanged()
        assertEquals(id, lastPayload().getString("sessionId"))
        assertEquals("stop", lastPayload().getString("action"))
    }

    @Test fun queueFailureAndDisconnectResumePendingLocalRoute() {
        sync.connectionChanged(true)
        sending = false
        start()
        assertNull(active!!.phoneSessionId)
        sync.localRouteChanged()
        assertEquals(1, sent.size)
        sending = true
        start()
        sync.connectionChanged(false)
        assertNull(active!!.phoneSessionId)
    }
}
