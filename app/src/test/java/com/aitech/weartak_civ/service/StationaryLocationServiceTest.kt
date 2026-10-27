package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Application
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.LocationInput
import com.aitech.weartak_civ.events.PowerManagerEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StationaryLocationServiceTest {
    @Before fun initializeIdentity() {
        MainActivity.device_uid = "stationary-test"
        MainActivity.versionNumber = "test"
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(3_000) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(5)
            }
        }
    }

    private fun stationaryCollector(screenOff: Boolean = false, assertions: (DataRepository) -> Unit) {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        runBlocking {
            repo.storeEnableLocationService(true)
            repo.storeLocationInput(LocationInput.INTERNAL_GNSS)
            repo.storeEnableActivityRecognitionService(true)
            repo.storeLatestActivityRecognized(ActivityRecognized.STILL)
            repo.storeLatestActivityConfidence(80)
        }
        repo.stationaryMotion.hold()
        val controller = Robolectric.buildService(ReportingIntervalStrategyService::class.java)
        val service = controller.get()
        ReportingIntervalStrategyService::class.java.getDeclaredField("dataRepository").apply {
            isAccessible = true; set(service, repo)
        }
        if (screenOff) {
            @Suppress("UNCHECKED_CAST")
            val interactive = ReportingIntervalStrategyService::class.java.getDeclaredField("interactive").run {
                isAccessible = true; get(service) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
            }
            interactive.value = false
        }
        val oldInterval = CotDispatchService.actualReportingIntervalSecs
        val oldObservation = CotDispatchService.desiredLocationObservationIntervalSecs
        try {
            CotDispatchService.actualReportingIntervalSecs = 5
            CotDispatchService.desiredLocationObservationIntervalSecs = 5
            ReportingIntervalStrategyService::class.java.getDeclaredMethod("startStationaryLocationCollector").apply {
                isAccessible = true; invoke(service)
            }
            val policy = ReportingIntervalStrategyService::class.java.getDeclaredField("stationaryLocationPolicy").run {
                isAccessible = true; get(service) as StationaryLocationPolicy
            }
            await { policy.nextEvaluationNanos != null }
            assertFalse(CotDispatchService.stationaryLocation)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
            await { CotDispatchService.stationaryLocation }
            assertEquals(60, CotDispatchService.actualReportingIntervalSecs)
            assertEquals(60, CotDispatchService.actualReportingIntervalSecsFlow.value)
            assertEquals(60, PowerManagerService.locationObservationIntervalSeconds(false))
            assertEquals(60, PowerManagerService.locationObservationIntervalSeconds(true))
            assertions(repo)
        } finally {
            (ReportingIntervalStrategyService::class.java.getDeclaredField("serviceScope").run {
                isAccessible = true; get(service) as CoroutineScope
            }).cancel()
            CotDispatchService.stationaryLocation = false
            CotDispatchService.actualReportingIntervalSecs = oldInterval
            CotDispatchService.desiredLocationObservationIntervalSecs = oldObservation
        }
    }

    @Test fun stationaryCadencesFollowDwellAndInteractionRestoresBothImmediately() = stationaryCollector { repo ->
        repo.noteLocationMotion()
        await { !CotDispatchService.stationaryLocation }
        assertEquals(5, CotDispatchService.actualReportingIntervalSecs)
        assertEquals(5, PowerManagerService.locationObservationIntervalSeconds(false))
        assertEquals(5, PowerManagerService.locationObservationIntervalSeconds(true))
    }

    @Test fun activityMotionRestoresBothWithoutWaitingForGpsConfirmation() = stationaryCollector { repo ->
        runBlocking { repo.storeLatestActivityRecognized(ActivityRecognized.WALKING) }
        await { !CotDispatchService.stationaryLocation }
        assertEquals(5, CotDispatchService.actualReportingIntervalSecs)
        assertEquals(5, PowerManagerService.locationObservationIntervalSeconds(false))
    }

    @Test fun alertBypassesStationaryAndStaleCoversSlowerPliBeforeRepoBridgeCatchesUp() = stationaryCollector { repo ->
        repo.setAlmightyReportingInterval(5)
        val time = runBlocking { repo.snapshotPli().time }
        assertEquals(135_000L, time.stale - time.time)
        runBlocking { repo.appendManualAlert(DataRepository.CustomEmergencyAlert(
            "sos", DataRepository.AlertState.ALERT, "SOS", "test", 1, false,
            System.currentTimeMillis(), false)) }
        await { !CotDispatchService.stationaryLocation }
        assertEquals(5, CotDispatchService.actualReportingIntervalSecs)
    }

    @Test fun screenOffProgressionUpdatesAcquisitionReportingAndStaleTogether() = stationaryCollector(screenOff = true) { repo ->
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        await { CotDispatchService.actualReportingIntervalSecs == 120 }
        assertEquals(120, PowerManagerService.locationObservationIntervalSeconds(true))
        assertEquals(120, PowerManagerService.locationObservationIntervalSeconds(false))
        repo.setAlmightyReportingInterval(5)
        val extended = runBlocking { repo.snapshotPli().time }
        assertEquals(255_000L, extended.stale - extended.time)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3))
        await { CotDispatchService.actualReportingIntervalSecs == 300 }
        assertEquals(300, PowerManagerService.locationObservationIntervalSeconds(true))
        val idle = runBlocking { repo.snapshotPli().time }
        assertEquals(615_000L, idle.stale - idle.time)
        repo.noteLocationMotion()
        await { CotDispatchService.actualReportingIntervalSecs == 5 }
    }

    @Test fun offBodyScreenOffNeedsNoActivityEvidenceAndScreenWristNavigationOrAlertRestoresCadence() {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        runBlocking {
            repo.storeEnableLocationService(true)
            repo.storeEnableActivityRecognitionService(false)
            repo.storeLatestIsDeviceWorn(false)
        }
        val service = Robolectric.buildService(ReportingIntervalStrategyService::class.java).get()
        ReportingIntervalStrategyService::class.java.getDeclaredField("dataRepository").apply {
            isAccessible = true; set(service, repo)
        }
        @Suppress("UNCHECKED_CAST")
        fun state(name: String) = ReportingIntervalStrategyService::class.java.getDeclaredField(name).run {
            isAccessible = true; get(service) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
        }
        val interactive = state("interactive")
        val navigating = state("navigating")
        val old = CotDispatchService.actualReportingIntervalSecs
        try {
            CotDispatchService.actualReportingIntervalSecs = 5
            interactive.value = false
            ReportingIntervalStrategyService::class.java.getDeclaredMethod("startStationaryLocationCollector").apply {
                isAccessible = true; invoke(service)
            }
            await { CotDispatchService.actualReportingIntervalSecs == 300 }
            interactive.value = true
            await { CotDispatchService.actualReportingIntervalSecs == 5 }
            interactive.value = false
            await { CotDispatchService.actualReportingIntervalSecs == 300 }
            runBlocking { repo.storeLatestIsDeviceWorn(true) }
            await { CotDispatchService.actualReportingIntervalSecs == 5 }
            runBlocking { repo.storeLatestIsDeviceWorn(false) }
            await { CotDispatchService.actualReportingIntervalSecs == 300 }
            navigating.value = true
            await { CotDispatchService.actualReportingIntervalSecs == 5 }
            navigating.value = false
            await { CotDispatchService.actualReportingIntervalSecs == 300 }
            runBlocking { repo.appendManualAlert(DataRepository.CustomEmergencyAlert(
                "idle-sos", DataRepository.AlertState.ALERT, "SOS", "test", 1, false,
                System.currentTimeMillis(), false)) }
            await { CotDispatchService.actualReportingIntervalSecs == 5 }
        } finally {
            (ReportingIntervalStrategyService::class.java.getDeclaredField("serviceScope").run {
                isAccessible = true; get(service) as CoroutineScope
            }).cancel()
            CotDispatchService.stationaryLocation = false
            CotDispatchService.actualReportingIntervalSecs = old
        }
    }

    @Test fun intervalBridgeAnnouncesLongerStaleThenAllowsImmediatePliWhenMotionReturns() {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val service = spyk(Robolectric.buildService(CotDispatchService::class.java).get())
        CotDispatchService::class.java.getDeclaredField("dataRepository").apply {
            isAccessible = true; set(service, repo)
        }
        every { service.buildAndSendPhysioLocationCotMsg(any()) } just Runs
        val gate = CotDispatchService::class.java.getDeclaredField("periodicReportingGate").run {
            isAccessible = true; get(service) as PeriodicReportingGate
        }
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        gate.reported(now)
        val old = CotDispatchService.actualReportingIntervalSecs
        try {
            CotDispatchService.actualReportingIntervalSecs = 5
            repo.setAlmightyReportingInterval(5)
            CotDispatchService::class.java.getDeclaredMethod("startReportingIntervalRepoBridge").apply {
                isAccessible = true; invoke(service)
            }
            shadowOf(Looper.getMainLooper()).idle()
            CotDispatchService.stationaryLocation = true
            await { repo.almightyReportingIntervalSecs.value == 60 }
            verify(exactly = 1) { service.buildAndSendPhysioLocationCotMsg(false) }
            assertFalse(gate.isDue(now + 1_000_000_000L, 60))
            CotDispatchService.stationaryLocation = false
            await { repo.almightyReportingIntervalSecs.value == 5 }
            assertTrue(gate.isDue(now + 1_000_000_000L, 5))
        } finally {
            (CotDispatchService::class.java.getDeclaredField("serviceScope").run {
                isAccessible = true; get(service) as CoroutineScope
            }).cancel()
            CotDispatchService.stationaryLocation = false
            CotDispatchService.actualReportingIntervalSecs = old
        }
    }

    @Test fun slowerPowerIntervalsSurviveStationaryAndWristMotionRestoresTheirBase() {
        val old = CotDispatchService.actualReportingIntervalSecs
        try {
            CotDispatchService.actualReportingIntervalSecs = 180
            CotDispatchService.stationaryLocation = true
            assertEquals(180, CotDispatchService.actualReportingIntervalSecs)
            assertEquals(180, PowerManagerService.locationObservationIntervalSeconds(false))
            CotDispatchService.actualReportingIntervalSecs = 300
            assertEquals(300, CotDispatchService.actualReportingIntervalSecsFlow.value)
            CotDispatchService.stationaryLocation = false
            assertEquals(300, CotDispatchService.actualReportingIntervalSecs)
        } finally {
            CotDispatchService.stationaryLocation = false
            CotDispatchService.actualReportingIntervalSecs = old
        }
    }

    @Test fun constantLocationReprofilesWithoutClearingItsStationaryEvidenceOrDelayingInteraction() {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        MainActivity.dataRepository = repo
        runBlocking {
            repo.storeEnableLocationService(true)
            repo.storeEnableDynamicReportingStrategy(false)
            repo.storeLocationInput(LocationInput.INTERNAL_GNSS)
        }
        val manager = mockk<LocationManager>(relaxed = true)
        val intervals = mutableListOf<Long>()
        every { manager.requestLocationUpdates("gps", capture(intervals), any<Float>(),
            any<LocationListener>(), any<Looper>()) } just Runs
        every { manager.allProviders } returns listOf("gps")
        every { manager.isProviderEnabled("gps") } returns true
        val service = Robolectric.buildService(LocationService::class.java).get()
        fun inject(name: String, value: Any) = LocationService::class.java.getDeclaredField(name).run {
            isAccessible = true; set(service, value)
        }
        inject("locationManager", manager)
        inject("currentInput", LocationInput.INTERNAL_GNSS)
        inject("acquisitionIntervalMillis", 5_000L)
        inject("periodic", true)
        LocationService.isRunning.set(true)
        val old = CotDispatchService.actualReportingIntervalSecs
        mockkObject(Utils)
        every { Utils.runTimerJob(any()) } just Runs
        every { Utils.startGlobalTimer(any()) } just Runs
        try {
            CotDispatchService.actualReportingIntervalSecs = 5
            repo.stationaryMotion.hold()
            CotDispatchService.stationaryLocation = true
            service.onPowerManagerEvent(PowerManagerEvent(PowerManagerEvent.CHANGED_UPDATE_INTERVAL))
            shadowOf(Looper.getMainLooper()).idle()
            verify { Utils.runTimerJob(60_000L) }
            assertTrue(repo.stationaryMotion.state.value.stationary)
            service.onInteractionPliRefresh(com.aitech.weartak_civ.events.InteractionPliRefresh(
                com.aitech.weartak_civ.events.PliRefreshReason.POSITION_VIEWED))
            await { intervals.contains(0L) }
            assertTrue(repo.locationMotionNanos.value > 0)
            verify { manager.requestLocationUpdates("gps", 0L, 0f, any<LocationListener>(), any<Looper>()) }
            CotDispatchService.stationaryLocation = false
            service.onPowerManagerEvent(PowerManagerEvent(PowerManagerEvent.CHANGED_UPDATE_INTERVAL))
            await { intervals.contains(5_000L) }
            verify { manager.requestLocationUpdates("gps", 5_000L, 0f, any<LocationListener>(), any<Looper>()) }
        } finally {
            service.onDestroy()
            CotDispatchService.stationaryLocation = false
            CotDispatchService.actualReportingIntervalSecs = old
            unmockkObject(Utils)
        }
    }
}
