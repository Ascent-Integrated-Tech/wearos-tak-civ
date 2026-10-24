package com.aitech.weartak_civ.data

import android.app.Application
import com.aitech.weartak_civ.data.model.TakRoutePoint
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionRouteNavigationTest {
    private val repo = BloodhoundRepository(RuntimeEnvironment.getApplication())
    private fun payload(sequence: Long = 1, action: String = "start", index: Int = 0,
                        session: String = "session"): JSONObject = JSONObject()
        .put("version", 1).put("sessionId", session).put("sequence", sequence).put("action", action)
        .put("routeUid", "route").put("targetPointIndex", index).put("routeCot", """
            <event type="b-m-r" uid="route" time="2026-10-24T00:00:00Z"><detail>
            <contact callsign="Route"/><link point="0,0" callsign="Start"/>
            <link point="0,0.001"/><link point="0,0.002" callsign="Finish"/>
            </detail></event>
        """.trimIndent())
    private fun apply(payload: JSONObject) = repo.applyPhoneRoute(CompanionRouteNavigation.parse(payload), null)

    @Test fun startProgressAndCompletionFollowPhoneNotWatchArrival() {
        assertTrue(apply(payload(index = 1)))
        assertEquals(1, repo.routeFollow.value!!.navigation.currentIndex)
        repo.updateRouteLocation(TakRoutePoint(0.0, 0.001))
        assertEquals(1, repo.routeFollow.value!!.navigation.currentIndex)
        assertTrue(apply(payload(2, "progress", 2)))
        assertEquals(0.002, repo.activeTrackingItem()!!.target.point.lon, 0.0)
        repo.updateRouteLocation(TakRoutePoint(0.0, 0.002))
        assertFalse(repo.routeFollow.value!!.navigation.completed)
        assertTrue(apply(payload(3, "stop").put("completed", true)))
        assertTrue(repo.routeFollow.value!!.navigation.completed)
        assertNull(repo.activeUid.value)
        assertTrue(repo.bloodhoundMap.value.isEmpty())
    }

    @Test fun staleUpdatesAndStoppedSessionsCannotRestartNavigation() {
        assertTrue(apply(payload(2, index = 1)))
        assertFalse(apply(payload(1)))
        assertTrue(apply(payload(3, "stop")))
        assertFalse(apply(payload(4, "progress")))
        assertNull(repo.routeFollow.value)
        assertTrue(apply(payload(1, session = "new")))
        assertTrue(apply(payload(5, "stop", session = "unrelated")))
        assertEquals("new", repo.routeFollow.value!!.phoneSessionId)
    }

    @Test fun localStopDeleteAndSwitchSuppressPhoneUntilNewSession() {
        assertTrue(apply(payload()))
        repo.stopRoute()
        assertFalse(apply(payload(2, "progress")))
        assertTrue(apply(payload(session = "new")))
        repo.addMarkerTarget("other", "Other", "b-m-p-s-m", 1.0, 1.0, 1L)
        repo.activate("other")
        assertFalse(apply(payload(2, "progress", session = "new")))
        assertEquals("other", repo.activeUid.value)
    }

    @Test fun disconnectMayResumeButDoesNotUndoLocalDismissal() {
        assertTrue(apply(payload()))
        repo.disconnectPhoneRoute()
        assertNull(repo.activeUid.value)
        assertTrue(apply(payload(2)))
        repo.stopRoute()
        repo.disconnectPhoneRoute()
        assertFalse(apply(payload(3)))
    }

    @Test fun geometryEditsKeepRawVertexIndexAndDuplicateOrMismatchedUidIsRejected() {
        assertTrue(apply(payload(index = 1)))
        val edit = payload(2, "progress", 1)
        edit.put("routeCot", edit.getString("routeCot").replace("0,0.001", "0,0.005"))
        assertTrue(apply(edit))
        assertEquals(0.005, repo.activeTrackingItem()!!.target.point.lon, 0.0)
        assertFalse(apply(edit))
        assertTrue(runCatching { CompanionRouteNavigation.parse(payload().put("routeUid", "other")) }.isFailure)
        assertTrue(runCatching { CompanionRouteNavigation.parse(payload(index = 3)) }.isFailure)
        val bad = payload()
        bad.put("routeCot", bad.getString("routeCot").replace("0,0.001", "91,0"))
        assertTrue(runCatching { CompanionRouteNavigation.parse(bad) }.isFailure)
    }
}
