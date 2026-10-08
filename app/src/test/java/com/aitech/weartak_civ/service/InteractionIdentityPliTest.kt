package com.aitech.weartak_civ.service

import android.app.Application
import android.location.Location
import android.os.SystemClock
import com.aitech.weartak_civ.cot.CotCompanionPayload
import com.aitech.weartak_civ.cot.CotEndpointDispatcher
import com.aitech.weartak_civ.cot.CotOutboundMessage
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.InteractionPliRefresh
import com.aitech.weartak_civ.events.PliRefreshReason
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.InteractionLocationRcvd
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class InteractionIdentityPliTest {
    @Test fun identityBurstPublishesLatestIdentityWithoutWaitingForNewFixAndPreservesFixTime() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val activity = mockk<MainActivity>()
        every { activity.applicationContext } returns app
        MainActivity.mainActivity = activity
        val realRepo = DataRepository(app)
        realRepo.storeCallsign("Current")
        realRepo.storeTeam("Blue")
        realRepo.storeRole("Medic")
        val fix = Location("gps").apply {
            latitude = 41.0
            longitude = -87.0
            altitude = 100.0
            accuracy = 5f
            time = System.currentTimeMillis() - 1000
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        val repo = mockk<DataRepository>()
        val currentLocation = MutableStateFlow<Location?>(fix)
        every { repo.latestLocation } returns currentLocation
        every { repo.buildHailDetail } returns MutableStateFlow(false)
        every { repo.getEnableCompanionAppService } returns flowOf(false)
        every { repo.getEnableSitxService } returns flowOf(false)
        every { repo.sitxStatus } returns MutableStateFlow(DataRepository.SitxServerStatus.DISCONNECTED)
        every { repo.multicastPublisherRunning } returns MutableStateFlow(false)
        every { repo.anyEndpointConnected } returns MutableStateFlow(true)
        coEvery { repo.snapshotInteractionPli(any()) } coAnswers {
            realRepo.snapshotInteractionPli(firstArg())
        }
        val companion = mockk<CompanionAppRepository>()
        every { companion.companionAppReady } returns MutableStateFlow(false)
        val sent = LinkedBlockingQueue<CotOutboundMessage>()
        val dispatcher = mockk<CotEndpointDispatcher>()
        coEvery { dispatcher.dispatch(any(), any()) } coAnswers {
            sent.add(firstArg())
            true
        }
        val service = CotDispatchService()
        fun inject(name: String, value: Any) {
            CotDispatchService::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(service, value)
            }
        }
        inject("dataRepository", repo)
        inject("endpointDispatcher\$delegate", lazyOf(dispatcher))
        inject("capRepo\$delegate", lazyOf(companion))
        service.onIdentityPliRefresh(InteractionPliRefresh(PliRefreshReason.CALLSIGN_CHANGED))
        realRepo.storeCallsign("Latest")
        service.onIdentityPliRefresh(InteractionPliRefresh(PliRefreshReason.ROLE_CHANGED))
        val message = sent.poll(3, TimeUnit.SECONDS)
        assertNotNull(message)
        val snapshot = (message!!.companionPayload as CotCompanionPayload.Pli).snapshot
        assertEquals("Latest", snapshot.systemData.callsign)
        assertEquals("Medic", snapshot.systemData.role)
        assertEquals("Blue", snapshot.systemData.team)
        assertEquals(fix.time, snapshot.time.time)
        assertTrue(message.xml.contains("callsign='Latest'"))
        assertNull(sent.poll(250, TimeUnit.MILLISECONDS))
        currentLocation.value = Location(fix).apply {
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 4_000_000_000L
        }
        service.onIdentityPliRefresh(InteractionPliRefresh(PliRefreshReason.TEAM_CHANGED))
        assertNull(sent.poll(350, TimeUnit.MILLISECONDS))
        currentLocation.value = null
        service.onIdentityPliRefresh(InteractionPliRefresh(PliRefreshReason.CALLSIGN_CHANGED))
        assertNull(sent.poll(350, TimeUnit.MILLISECONDS))
        val reportingClock = CotDispatchService::class.java.getDeclaredField("lastLocationUpdateRcvd").apply {
            isAccessible = true
            setLong(service, 123L)
        }
        service.onInteractionLocationRcvd(InteractionLocationRcvd(fix, setOf(PliRefreshReason.MAP_OPENED)))
        assertNotNull(sent.poll(3, TimeUnit.SECONDS))
        assertEquals(123L, reportingClock.getLong(service))
        service.onMessageEvent(MessageEvent(MessageEvent.CALLSIGN_TEAM_ROLE_CHANGED, 0))
        assertNull(sent.poll(150, TimeUnit.MILLISECONDS))
        currentLocation.value = fix
        shadowOf(app).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        service.onIdentityPliRefresh(InteractionPliRefresh(PliRefreshReason.CALLSIGN_CHANGED))
        assertNull(sent.poll(350, TimeUnit.MILLISECONDS))
    }
}
