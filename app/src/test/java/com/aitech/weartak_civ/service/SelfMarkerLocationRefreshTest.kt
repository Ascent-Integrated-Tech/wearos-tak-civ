package com.aitech.weartak_civ.service

import android.app.Application
import android.location.Location
import android.os.SystemClock
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cot.CotCompanionPayload
import com.aitech.weartak_civ.cot.CotEndpointDispatcher
import com.aitech.weartak_civ.cot.CotOutboundMessage
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.InteractionLocationRcvd
import com.aitech.weartak_civ.events.PliRefreshReason
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SelfMarkerLocationRefreshTest {
    @Test fun selfDropPublishesBestFixImmediatelyThenUpdatesSameUidButExplicitDropNeverMoves() = runTest {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.device_uid = "self-test"
        MainActivity.versionNumber = "test"
        MainActivity.mainActivity = mockk { every { applicationContext } returns app }
        val repo = DataRepository(app)
        repo.storeLocationInput(com.aitech.weartak_civ.data.LocationInput.INTERNAL_GNSS)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(2))
        repo.setEndpointConnected(DataRepository.Endpoint.TAK_SERVER)
        val old = Location("gps").apply {
            latitude = 41.0; longitude = -87.0; accuracy = 5f; time = System.currentTimeMillis() - 60_000
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 60_000_000_000L
        }
        repo.storeLatestLocation(old)
        val companion = mockk<CompanionAppRepository>(relaxed = true)
        every { companion.gateway } returns com.aitech.weartak_civ.cot.CompanionGatewaySession()
        val host = mockk<WearTakApplication>(relaxed = true)
        every { host.pluginManager.settingsCoordinator.anyPluginHasSettingEnabled(any()) } returns true
        val service = spyk(CotDispatchService())
        every { service.applicationContext } returns host
        val messages = java.util.concurrent.CopyOnWriteArrayList<CotOutboundMessage>()
        val dispatcher = mockk<CotEndpointDispatcher>()
        coEvery { dispatcher.dispatch(any(), any()) } coAnswers { messages += firstArg<CotOutboundMessage>(); true }
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        fun inject(name: String, value: Any) {
            CotDispatchService::class.java.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
        }
        inject("dataRepository", repo)
        inject("serviceScope", scope)
        inject("capRepo\$delegate", lazyOf(companion))
        inject("endpointDispatcher\$delegate", lazyOf(dispatcher))
        CotDispatchService::class.java.getDeclaredMethod("startMarkerDropCollector").apply { isAccessible = true }.invoke(service)
        runCurrent()
        try {
            repo.requestMarkerDrop("friendly", "Self", "", System.currentTimeMillis(), uid = "self")
            repo.requestMarkerDrop("friendly", "Explicit", "", System.currentTimeMillis(), 42.0, -88.0, "explicit")
            runCurrent()
            withContext(Dispatchers.IO) {
                val ready = withTimeoutOrNull(3000) {
                    while (messages.count { it.companionPayload is CotCompanionPayload.Marker } < 2) delay(10)
                    true
                }
                assertTrue("Initial self and explicit markers not dispatched", ready == true)
            }
            val initial = messages.mapNotNull { (it.companionPayload as? CotCompanionPayload.Marker)?.snapshot }
            assertEquals(2, initial.size)
            assertEquals(41.0, initial.first().loc!!.lat, 0.0)
            assertEquals(old.time, initial.first().loc!!.timeMillis)
            assertFalse(repo.droppedMarkerList.value.first().waitingForUpdatedLocation)
            val fresh = Location(old).apply {
                latitude = 41.5
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            repo.storeLatestLocation(fresh)
            service.onInteractionLocationRcvd(InteractionLocationRcvd(fresh, setOf(PliRefreshReason.SELF_MARKER)))
            runCurrent()
            withContext(Dispatchers.IO) {
                val ready = withTimeoutOrNull(3000) {
                    while (messages.count { it.companionPayload is CotCompanionPayload.Marker } < 3) delay(10)
                    true
                }
                assertTrue("Fresh self-marker update not dispatched", ready == true)
            }
            val markers = messages.mapNotNull { (it.companionPayload as? CotCompanionPayload.Marker)?.snapshot }
            assertEquals(3, markers.size)
            assertEquals("self", markers.last().uid)
            assertEquals(41.5, markers.last().loc!!.lat, 0.0)
            assertEquals(42.0, repo.droppedMarkerList.value.single { it.uid == "explicit" }.location!!.lat, 0.0)
            service.onInteractionLocationRcvd(InteractionLocationRcvd(fresh, setOf(PliRefreshReason.SELF_MARKER)))
            runCurrent()
            assertEquals(3, messages.count { it.companionPayload is CotCompanionPayload.Marker })

            repo.requestMarkerDrop("friendly", "Delete before fix", "", System.currentTimeMillis(), uid = "deleted")
            runCurrent()
            withContext(Dispatchers.IO) {
                assertTrue(withTimeoutOrNull(3000) {
                    while (messages.count { it.companionPayload is CotCompanionPayload.Marker } < 4) delay(10)
                    true
                } == true)
            }
            repo.deleteDroppedPointLocally("deleted", BloodhoundRepository(app))
            fresh.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            fresh.time = System.currentTimeMillis()
            service.onInteractionLocationRcvd(InteractionLocationRcvd(fresh, setOf(PliRefreshReason.SELF_MARKER)))
            runCurrent()
            assertEquals(4, messages.count { it.companionPayload is CotCompanionPayload.Marker })
            assertTrue(repo.droppedMarkerList.value.none { it.uid == "deleted" })
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }
}
