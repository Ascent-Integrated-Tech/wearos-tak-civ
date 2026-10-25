package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.LocationInput
import com.aitech.weartak_civ.data.LocationInputStatus
import com.aitech.weartak_civ.events.InteractionLocationRcvd
import com.aitech.weartak_civ.events.InteractionPliRefresh
import com.aitech.weartak_civ.events.LocationUpdateRequired
import com.aitech.weartak_civ.events.PliRefreshReason
import com.aitech.weartak_civ.presentation.MainActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.Tasks
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.Assert.*
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
class InteractionLocationRefreshTest {
    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(3_000) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(5)
            }
        }
    }

    class Observer {
        val events = mutableListOf<InteractionLocationRcvd>()
        @Subscribe fun receive(event: InteractionLocationRcvd) { events.add(event) }
    }

    private fun service(
        manager: LocationManager,
        input: LocationInput = LocationInput.INTERNAL_GNSS,
        fusedClient: FusedLocationProviderClient? = null,
    ): LocationService {
        MainActivity.device_uid = "test-watch"
        MainActivity.dataRepository = DataRepository(RuntimeEnvironment.getApplication())
        runBlocking {
            MainActivity.dataRepository.storeEnableLocationService(true)
            MainActivity.dataRepository.storeLocationInput(input)
        }
        val service = Robolectric.buildService(LocationService::class.java).get()
        fun inject(name: String, value: Any) {
            LocationService::class.java.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
        }
        inject("locationManager", manager)
        inject("currentInput", input)
        if (fusedClient != null) inject("fusedClient", fusedClient)
        every { manager.allProviders } returns listOf("gps", "network")
        every { manager.isProviderEnabled(any()) } returns true
        every { manager.isLocationEnabled } returns true
        LocationService.isRunning.set(true)
        return service
    }

    private fun fix(provider: String = "gps") = Location(provider).apply {
        latitude = 41.0; longitude = -87.0; altitude = 100.0; accuracy = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    @Test fun fusedUsesRealClientCoalescesFreshRequestsAndNeverUsesNetworkProvider() {
        val manager = mockk<LocationManager>(relaxed = true)
        val client = mockk<FusedLocationProviderClient>()
        val callback = slot<LocationCallback>()
        val request = slot<LocationRequest>()
        every { client.requestLocationUpdates(capture(request), capture(callback), any<Looper>()) } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<LocationCallback>()) } returns Tasks.forResult(null)
        val service = service(manager, LocationInput.FUSED, client)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val observer = Observer()
        EventBus.getDefault().register(observer)
        try {
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.CHAT_SENT))
            await { callback.isCaptured }
            verify(exactly = 1) { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) }
            verify(exactly = 0) { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
                any<LocationListener>(), any<Looper>()) }
            assertEquals(0L, request.captured.maxUpdateAgeMillis)
            callback.captured.onLocationResult(LocationResult.create(listOf(fix("network"))))
            assertTrue(observer.events.isEmpty())
            val fresh = fix("fused")
            val completed = callback.captured
            completed.onLocationResult(LocationResult.create(listOf(fresh)))
            await { observer.events.size == 1 }
            assertEquals(setOf(PliRefreshReason.MAP_OPENED, PliRefreshReason.CHAT_SENT), observer.events.single().reasons)
            assertEquals("fused", MainActivity.dataRepository.bestKnownLocation()!!.provider)
            assertEquals(fresh.time, runBlocking { MainActivity.dataRepository.snapshotLocation()!!.timeMillis })
            verify { client.removeLocationUpdates(completed) }
            completed.onLocationResult(LocationResult.create(listOf(fresh)))
            assertEquals(1, observer.events.size)
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.ROLE_CHANGED))
            await { callback.captured !== completed }
            val pending = callback.captured
            service.onDestroy()
            verify { client.removeLocationUpdates(pending) }
            pending.onLocationResult(LocationResult.create(listOf(fresh)))
            assertEquals(1, observer.events.size)
        } finally {
            EventBus.getDefault().unregister(observer)
            LocationService.isRunning.set(false)
        }
    }

    @Test fun fusedFailureAndMissingClientAreExplicitWithoutGnssOrNetworkFallback() {
        val manager = mockk<LocationManager>(relaxed = true)
        val client = mockk<FusedLocationProviderClient>()
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } returns
            Tasks.forException(IllegalStateException("Unavailable test service"))
        every { client.removeLocationUpdates(any<LocationCallback>()) } returns Tasks.forResult(null)
        val service = service(manager, LocationInput.FUSED, client)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        try {
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            await { MainActivity.dataRepository.locationInputStatus.value == LocationInputStatus.REQUEST_FAILED }
            assertNull(MainActivity.dataRepository.bestKnownLocation())
            verify(exactly = 0) { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
                any<LocationListener>(), any<Looper>()) }
        } finally { service.onDestroy() }
        val missing = service(manager, LocationInput.FUSED)
        try {
            missing.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            await { MainActivity.dataRepository.locationInputStatus.value == LocationInputStatus.UNAVAILABLE }
        } finally { missing.onDestroy() }
    }

    @Test fun fusedPeriodicAndOneShotKeepProvenanceCancelOnSwitchAndRejectLateCallbacks() {
        val manager = mockk<LocationManager>(relaxed = true)
        val client = mockk<FusedLocationProviderClient>()
        val callback = slot<LocationCallback>()
        val request = slot<LocationRequest>()
        val gnss = slot<LocationListener>()
        every { client.requestLocationUpdates(capture(request), capture(callback), any<Looper>()) } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<LocationCallback>()) } returns Tasks.forResult(null)
        every { manager.requestLocationUpdates("gps", any<Long>(), any<Float>(), capture(gnss), any<Looper>()) } just Runs
        val service = service(manager, LocationInput.FUSED, client)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val oldInterval = CotDispatchService.actualReportingIntervalSecs
        io.mockk.mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(any()) } just Runs
        try {
            CotDispatchService.actualReportingIntervalSecs = 1
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callback.isCaptured }
            assertEquals(1_000L, request.captured.intervalMillis)
            val periodic = callback.captured
            periodic.onLocationResult(LocationResult.create(listOf(fix("fused"))))
            await { MainActivity.dataRepository.latestLocation.value?.provider == "fused" }
            runBlocking { MainActivity.dataRepository.storeLocationInput(LocationInput.INTERNAL_GNSS) }
            LocationService::class.java.getDeclaredField("currentInput").apply {
                isAccessible = true; set(service, LocationInput.INTERNAL_GNSS)
            }
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { gnss.isCaptured }
            verify { client.removeLocationUpdates(periodic) }
            periodic.onLocationResult(LocationResult.create(listOf(fix("fused"))))
            assertNull(MainActivity.dataRepository.bestKnownLocation())
            gnss.captured.onLocationChanged(fix("gps"))
            await { MainActivity.dataRepository.latestLocation.value?.provider == "gps" }
            runBlocking { MainActivity.dataRepository.storeLocationInput(LocationInput.FUSED) }
            LocationService::class.java.getDeclaredField("currentInput").apply {
                isAccessible = true; set(service, LocationInput.FUSED)
            }
            val cached = fix("fused")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            CotDispatchService.actualReportingIntervalSecs = 30
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callback.captured !== periodic }
            verify { manager.removeUpdates(gnss.captured) }
            val oneShot = callback.captured
            assertEquals(6_000L, request.captured.durationMillis)
            oneShot.onLocationResult(LocationResult.create(listOf(cached)))
            assertNull(MainActivity.dataRepository.bestKnownLocation())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            verify { client.removeLocationUpdates(oneShot) }
            assertEquals(LocationInputStatus.REQUEST_FAILED, MainActivity.dataRepository.locationInputStatus.value)
            oneShot.onLocationResult(LocationResult.create(listOf(fix("fused"))))
            assertNull(MainActivity.dataRepository.bestKnownLocation())
        } finally {
            CotDispatchService.actualReportingIntervalSecs = oldInterval
            service.onDestroy()
            io.mockk.unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun deniedPermissionDoesNotStartAcquisition() {
        val manager = mockk<LocationManager>(relaxed = true)
        val service = service(manager)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 0) { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
            any<LocationListener>(), any<Looper>()) }
        assertEquals(LocationInputStatus.PERMISSION_REQUIRED, MainActivity.dataRepository.locationInputStatus.value)
        service.onDestroy()
    }

    @Test fun triggersCoalesceFreshFixDispatchesOnceAndDestroyRemovesAcquisition() {
        val manager = mockk<LocationManager>(relaxed = true)
        val callback = slot<LocationListener>()
        every { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
            capture(callback), any<Looper>()) } just Runs
        val service = service(manager)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val observer = Observer()
        EventBus.getDefault().register(observer)
        try {
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.CHAT_SENT))
            await { callback.isCaptured }
            verify(exactly = 1) { manager.requestLocationUpdates("gps", 0L, 0f,
                any<LocationListener>(), Looper.getMainLooper()) }
            callback.captured.onLocationChanged(Location("gps"))
            assertTrue(observer.events.isEmpty())
            val fresh = fix()
            callback.captured.onLocationChanged(fresh)
            await { observer.events.size == 1 }
            assertEquals(1, observer.events.size)
            assertEquals(setOf(PliRefreshReason.MAP_OPENED, PliRefreshReason.CHAT_SENT), observer.events.single().reasons)
            assertEquals(fresh.time, observer.events.single().location.time)
            callback.captured.onLocationChanged(fresh)
            assertEquals(1, observer.events.size)
            val completed = callback.captured
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.ROLE_CHANGED))
            await { callback.captured !== completed }
            val pending = callback.captured
            service.onDestroy()
            verify { manager.removeUpdates(pending) }
            pending.onLocationChanged(fresh)
            assertEquals(1, observer.events.size)
        } finally {
            EventBus.getDefault().unregister(observer)
            LocationService.isRunning.set(false)
        }
    }

    @Test fun timeoutAndProviderFailureCleanUpAndAllowLaterInteractionWithoutSendingPli() {
        val manager = mockk<LocationManager>(relaxed = true)
        val callback = slot<LocationListener>()
        every { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
            capture(callback), any<Looper>()) } just Runs
        val service = service(manager)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val observer = Observer()
        EventBus.getDefault().register(observer)
        try {
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            await { callback.isCaptured }
            val timedOut = callback.captured
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            verify { manager.removeUpdates(timedOut) }
            assertTrue(observer.events.isEmpty())
            every { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
                capture(callback), any<Looper>()) } throws SecurityException("permission revoked")
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.CHAT_SENT))
            await { callback.captured !== timedOut }
            verify { manager.removeUpdates(callback.captured) }
            every { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
                capture(callback), any<Looper>()) } just Runs
            val failed = callback.captured
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.ROLE_CHANGED))
            await { callback.captured !== failed }
            verify(exactly = 3) { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
                any<LocationListener>(), any<Looper>()) }
            assertTrue(observer.events.isEmpty())
        } finally {
            service.onDestroy()
            EventBus.getDefault().unregister(observer)
        }
    }

    @Test fun networkInputUsesOnlyNetworkAndCoarsePermissionButUnavailableInputNeverFallsBack() {
        val manager = mockk<LocationManager>(relaxed = true)
        val callback = slot<LocationListener>()
        every { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
            capture(callback), any<Looper>()) } just Runs
        val service = service(manager, LocationInput.NETWORK)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        try {
            every { manager.allProviders } returns listOf("gps")
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.OUTBOUND_ROUTE_READY))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(LocationInputStatus.UNAVAILABLE, MainActivity.dataRepository.locationInputStatus.value)
            verify(exactly = 0) { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
                any<LocationListener>(), any<Looper>()) }
            every { manager.allProviders } returns listOf("gps", "network")
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.OUTBOUND_ROUTE_READY))
            await { callback.isCaptured }
            verify(exactly = 1) { manager.requestLocationUpdates("network", 0L, 0f,
                any<LocationListener>(), Looper.getMainLooper()) }
            callback.captured.onLocationChanged(fix("gps"))
            assertNull(MainActivity.dataRepository.latestLocation.value)
            callback.captured.onLocationChanged(fix("network"))
            await { MainActivity.dataRepository.latestLocation.value != null }
            assertEquals("network", MainActivity.dataRepository.latestLocation.value?.provider)
            assertEquals(LocationInput.NETWORK, runBlocking { MainActivity.dataRepository.getLocationInput.first() })
        } finally { service.onDestroy() }
    }

    @Test fun changingInputCancelsOldAcquisitionsAndPeriodicUpdatesUseNewProvider() {
        val manager = mockk<LocationManager>(relaxed = true)
        val callback = slot<LocationListener>()
        every { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
            capture(callback), any<Looper>()) } just Runs
        val service = service(manager)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val oldInterval = CotDispatchService.actualReportingIntervalSecs
        try {
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            await { callback.isCaptured }
            val previous = callback.captured
            runBlocking { MainActivity.dataRepository.storeLocationInput(LocationInput.NETWORK) }
            LocationService::class.java.getDeclaredField("currentInput").apply {
                isAccessible = true; set(service, LocationInput.NETWORK)
            }
            CotDispatchService.actualReportingIntervalSecs = 1
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callback.captured !== previous }
            verify { manager.removeUpdates(previous) }
            verify { manager.requestLocationUpdates("network", 1_000L, 0f,
                any<LocationListener>(), Looper.getMainLooper()) }
            previous.onLocationChanged(fix("gps"))
            assertNull(MainActivity.dataRepository.latestLocation.value)
            callback.captured.onLocationChanged(fix("network"))
            await { MainActivity.dataRepository.latestLocation.value != null }
            assertEquals("network", MainActivity.dataRepository.latestLocation.value?.provider)
        } finally {
            CotDispatchService.actualReportingIntervalSecs = oldInterval
            service.onDestroy()
        }
    }

    @Test fun disabledProviderAndAvailabilityPermissionRaceRemainExplicitWithoutRequestingAnotherSource() {
        val manager = mockk<LocationManager>(relaxed = true)
        val service = service(manager)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        try {
            every { manager.isProviderEnabled("gps") } returns false
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(LocationInputStatus.DISABLED, MainActivity.dataRepository.locationInputStatus.value)
            every { manager.allProviders } throws SecurityException("revoked during availability check")
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.CHAT_SENT))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(LocationInputStatus.PERMISSION_REQUIRED, MainActivity.dataRepository.locationInputStatus.value)
            verify(exactly = 0) { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
                any<LocationListener>(), any<Looper>()) }
        } finally { service.onDestroy() }
    }

    @Test fun scheduledOneShotUsesSelectedNetworkRejectsCachedFixAndCancelsAfterSixSeconds() {
        val manager = mockk<LocationManager>(relaxed = true)
        val callback = slot<LocationListener>()
        every { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
            capture(callback), any<Looper>()) } just Runs
        val service = service(manager, LocationInput.NETWORK)
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val previousInterval = CotDispatchService.actualReportingIntervalSecs
        io.mockk.mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(any()) } just Runs
        try {
            val old = fix("network")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            CotDispatchService.actualReportingIntervalSecs = 30
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callback.isCaptured }
            verify { manager.requestLocationUpdates("network", 0L, 0f,
                any<LocationListener>(), Looper.getMainLooper()) }
            callback.captured.onLocationChanged(old)
            assertNull(MainActivity.dataRepository.latestLocation.value)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            verify { manager.removeUpdates(callback.captured) }
            assertEquals(LocationInputStatus.REQUEST_FAILED, MainActivity.dataRepository.locationInputStatus.value)
            callback.captured.onLocationChanged(fix("network"))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(MainActivity.dataRepository.latestLocation.value)
        } finally {
            CotDispatchService.actualReportingIntervalSecs = previousInterval
            service.onDestroy()
            io.mockk.unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }
}
