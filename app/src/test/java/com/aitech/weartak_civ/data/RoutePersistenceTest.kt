package com.aitech.weartak_civ.data

import android.app.Application
import android.content.Context
import com.aitech.weartak_civ.cot.CotDeletion
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import com.aitech.weartak_civ.data.parsers.TakRouteCotParser
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RoutePersistenceTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val now = System.currentTimeMillis()
    private val route = TakRoute("DESKRTE-1", "DESK ROUTE 1", listOf(
        TakRoutePoint(0.0, 0.0, 10.0, "Start", uid = "a"),
        TakRoutePoint(0.0, 0.0005, isCheckpoint = false, uid = "control"),
        TakRoutePoint(0.0, 0.001, name = "Checkpoint", uid = "b"),
        TakRoutePoint(0.0, 0.002, name = "Finish", uid = "c")
    ), "multicast", "Mission", "Desk", now, "Walking", now + 6 * 60 * 60_000)

    @Before fun setup() { MainActivity.device_uid = "watch" }

    private fun following(repo: DataRepository): BloodhoundRepository = BloodhoundRepository(app).also {
        it.restoreRouteSession(repo.visibleTakRoutes, repo.routePersistence)
    }

    @Test fun originalStaleAndCompleteMetadataSurviveRepositoryRecreation() {
        val parsed = requireNotNull(TakRouteCotParser.parse("""
            <event uid="DESKRTE-1" type="b-m-r" time="${Instant.ofEpochMilli(now)}"
              stale="${Instant.ofEpochMilli(route.staleTime!!)}"><detail>
              <contact callsign="DESK ROUTE 1"/><link_attr method="Walking"/>
              <link point="0,0" type="b-m-p-w"/><link point="0,0.0005" type="b-m-p-c"/>
              <link point="0,0.001" type="b-m-p-w"/><link point="0,0.002" type="b-m-p-w"/>
            </detail></event>
        """.trimIndent(), "multicast"))
        val repo = DataRepository(app)
        repo.upsertTakRoute(parsed)
        repo.upsertTakRoute(route.copy(uid = "metadata"))
        val restarted = DataRepository(app)
        assertEquals(listOf(parsed, route.copy(uid = "metadata")), restarted.takRouteCopies)
        assertEquals(route.staleTime, restarted.takRouteCopies.first().staleTime)
    }

    @Test fun expiredWhileStoppedIsNotRecoveredAndLiveExpiryRemovesOnlyExpiredCopy() {
        val repo = DataRepository(app)
        repo.upsertTakRoute(route)
        repo.upsertTakRoute(route.copy(serverId = "fallback", staleTime = route.staleTime!! + 60_000))
        assertEquals(listOf("fallback"), repo.routePersistence.load(route.staleTime!!).routes.map { it.serverId })
        assertTrue(repo.routePersistence.load(route.staleTime!! + 60_000).routes.isEmpty())
        repo.pruneTakRoutes(route.staleTime!!)
        assertEquals(listOf("fallback"), DataRepository(app).takRouteCopies.map { it.serverId })
        repo.upsertTakRoute(route.copy(uid = "expired", staleTime = now - 1))
        assertEquals(1, repo.takRouteCopies.size)
    }

    @Test fun noStaleRemainsMemoryOnlyAndMalformedStaleIsRejected() {
        val repo = DataRepository(app)
        repo.upsertTakRoute(route.copy(staleTime = null))
        assertEquals(1, repo.takRouteCopies.size)
        assertTrue(DataRepository(app).takRouteCopies.isEmpty())
        assertNull(TakRouteCotParser.parse(
            """<event uid="bad" type="b-m-r" stale="bad"><detail><link point="0,0"/></detail></event>""", "multicast"))
    }

    @Test fun expirySchedulerPrunesLiveRouteWithoutAnotherIngressEvent() = runBlocking {
        val repo = DataRepository(app)
        repo.upsertTakRoute(route.copy(staleTime = System.currentTimeMillis() + 300))
        withTimeout(5000) { repo.takRoutes.first { it.isNotEmpty() } }
        withTimeout(5000) { repo.takRoutes.first { it.isEmpty() } }
        assertTrue(DataRepository(app).takRouteCopies.isEmpty())
    }

    @Test fun localRemoveAllAndMissionRemovalCannotResurrectOnRestart() {
        val repo = DataRepository(app)
        val bh = following(repo)
        listOf(route, route.copy(serverId = "other")).forEach(repo::upsertTakRoute)
        bh.startRoute(route, RouteFollow.StartMode.BEGINNING, null)
        repo.removeRouteFromWatch(route, bh)
        assertTrue(DataRepository(app).takRouteCopies.isEmpty())
        assertNull(following(DataRepository(app)).routeFollow.value)
        repo.upsertTakRoute(route)
        repo.removeTakRoutesForMission(route.serverId, route.missionName!!)
        assertTrue(DataRepository(app).takRouteCopies.isEmpty())
        repo.upsertTakRoute(route)
        bh.startRoute(route, RouteFollow.StartMode.BEGINNING, null)
        repo.clearRoutesFromWatch(bh)
        assertTrue(DataRepository(app).takRouteCopies.isEmpty())
        assertNull(following(DataRepository(app)).activeUid.value)
    }

    @Test fun sourceDeleteWatermarkSurvivesRestartAndAllowsOnlyNewerRevision() = runBlocking {
        val repo = DataRepository(app)
        listOf(route, route.copy(serverId = "fallback")).forEach(repo::upsertTakRoute)
        repo.receiveCotDeletion(CotDeletion(route.uid, now + 1), route.serverId, following(repo))
        val restarted = DataRepository(app)
        restarted.upsertTakRoute(route)
        restarted.upsertTakRoute(route.copy(eventTime = now + 1))
        assertEquals(listOf("fallback"), restarted.takRouteCopies.map { it.serverId })
        restarted.upsertTakRoute(route.copy(eventTime = now + 2))
        assertEquals(2, DataRepository(app).takRouteCopies.size)
        restarted.receiveCotDeletion(CotDeletion("not-yet-seen", now), "multicast", following(restarted))
        val again = DataRepository(app)
        again.upsertTakRoute(route.copy(uid = "not-yet-seen"))
        assertFalse(again.takRouteCopies.any { it.uid == "not-yet-seen" })
    }

    @Test fun activeStandaloneIndexModeAndArrivedCheckpointsResumeWithoutAck() {
        val repo = DataRepository(app)
        repo.upsertTakRoute(route)
        val bh = following(repo)
        assertTrue(bh.startRoute(route, RouteFollow.StartMode.NEAREST, route.points.first()))
        bh.updateRouteLocation(route.points.first())
        bh.updateRouteLocation(route.points[1])
        bh.updateRouteLocation(TakRoutePoint(0.0, 0.00095))
        val saved = bh.routeFollow.value!!
        assertEquals(2, saved.navigation.currentIndex)
        assertEquals(setOf(0, 2), saved.navigation.arrived)
        val resumed = following(DataRepository(app))
        val session = resumed.routeFollow.value!!
        assertEquals(saved.navigation.currentIndex, session.navigation.currentIndex)
        assertEquals(saved.navigation.mode, session.navigation.mode)
        assertEquals(saved.navigation.arrived, session.navigation.arrived)
        assertEquals(saved.localSessionId, session.localSessionId)
        assertEquals(session.uid, resumed.activeUid.value)
        assertNull(session.remainingMeters)
        assertNull(resumed.activeTrackingItem()!!.origin.issuerUid)
        resumed.updateRouteLocation(TakRoutePoint(0.0, 0.0012))
        assertEquals(3, resumed.routeFollow.value!!.navigation.currentIndex)
    }

    @Test fun identicalSourceFallbackResumesButChangedVisibleGeometryDoesNot() {
        val repo = DataRepository(app)
        listOf(route, route.copy(serverId = "fallback")).forEach(repo::upsertTakRoute)
        following(repo).startRoute(route, RouteFollow.StartMode.BEGINNING, null)
        repo.removeTakRoute(route.serverId, route.uid)
        val restarted = DataRepository(app)
        assertEquals("fallback", following(restarted).routeFollow.value!!.route.serverId)
        restarted.upsertTakRoute(route.copy(eventTime = now + 1, points = route.points.reversed()))
        assertNull(following(DataRepository(app)).routeFollow.value)
    }

    @Test fun stopCompletionSwitchAndPhoneOwnershipDoNotResume() {
        val repo = DataRepository(app)
        repo.upsertTakRoute(route)
        val bh = following(repo)
        fun start() { assertTrue(bh.startRoute(route, RouteFollow.StartMode.BEGINNING, null)) }
        fun noResume() { assertNull(following(DataRepository(app)).routeFollow.value) }
        start()
        bh.stopRoute()
        noResume()
        start()
        bh.updateRouteLocation(route.points.last())
        assertTrue(bh.routeFollow.value!!.navigation.completed)
        noResume()
        start()
        bh.addMarkerTarget("other", "Other", "b-m-p-s-m", 1.0, 1.0, now)
        bh.activate("other")
        noResume()
        start()
        bh.preparePhoneRoute("phone")
        noResume()
        bh.resumeLocalRoute("phone")
        assertNotNull(following(DataRepository(app)).routeFollow.value)
    }

    @Test fun expiredOrRemovedOrChangedRouteCannotRestoreSavedSession() {
        val repo = DataRepository(app)
        repo.upsertTakRoute(route)
        following(repo).startRoute(route, RouteFollow.StartMode.BEGINNING, null)
        assertNull(repo.routePersistence.restoreSession(listOf(route), route.staleTime!!))
        assertNull(repo.routePersistence.restoreSession(emptyList(), now))
        assertNull(repo.routePersistence.restoreSession(listOf(route.copy(method = "Driving")), now))
    }

    @Test fun corruptCatalogAndSessionFailClosed() {
        val prefs = app.getSharedPreferences("received-routes-v1", Context.MODE_PRIVATE)
        prefs.edit().putString("catalog", "{").putString("session", "{}").commit()
        val repo = DataRepository(app)
        assertTrue(repo.takRouteCopies.isEmpty())
        assertNull(following(repo).routeFollow.value)
    }
}
