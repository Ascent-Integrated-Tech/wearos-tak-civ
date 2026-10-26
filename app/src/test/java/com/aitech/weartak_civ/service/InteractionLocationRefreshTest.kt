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
import com.aitech.weartak_civ.events.PowerManagerEvent
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
    private fun await(stage: String = "condition", condition: () -> Boolean) = runBlocking {
        try { withTimeout(3_000) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(5)
            }
        } } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $stage", timeout)
        }
    }

    class Observer {
        val events = mutableListOf<InteractionLocationRcvd>()
        val normalEvents = mutableListOf<com.aitech.weartak_civ.events.LocationRcvd>()
        @Subscribe fun receive(event: InteractionLocationRcvd) { events.add(event) }
        @Subscribe fun receive(event: com.aitech.weartak_civ.events.LocationRcvd) { normalEvents.add(event) }
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

    private fun inject(service: LocationService, name: String, value: Any) {
        LocationService::class.java.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
    }

    private fun policy(service: LocationService): LocationAcquisitionPolicy =
        LocationService::class.java.getDeclaredField("acquisitionPolicy").let {
            it.isAccessible = true
            it.get(service) as LocationAcquisitionPolicy
        }

    @Test fun constantOneShotTimeoutsBackOffAndFixAndConfigurationRestoreNormalCadence() =
        oneShotBackoff(dynamic = false)

    @Test fun dynamicOneShotTimeoutsBackOffAndFixAndConfigurationRestoreNormalCadence() =
        oneShotBackoff(dynamic = true)

    private fun oneShotBackoff(dynamic: Boolean) {
        val manager = mockk<LocationManager>(relaxed = true)
        val callbacks = mutableListOf<LocationListener>()
        every { manager.requestLocationUpdates("gps", any<Long>(), any<Float>(),
            capture(callbacks), any<Looper>()) } just Runs
        val service = service(manager)
        inject(service, "dynamicReporting", dynamic)
        val previousInterval = CotDispatchService.actualReportingIntervalSecs
        val previousObservation = CotDispatchService.desiredLocationObservationIntervalSecs
        val schedules = mutableListOf<Long>()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(capture(schedules)) } just Runs
        every { com.aitech.weartak_civ.Utils.startGlobalTimer(any()) } just Runs
        try {
            CotDispatchService.actualReportingIntervalSecs = 30
            CotDispatchService.desiredLocationObservationIntervalSecs = 30
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callbacks.size == 1 }
            listOf(30_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)
                .forEachIndexed { index, interval ->
                    if (index > 0) {
                        service.acquireWakelockAndRequestLocation()
                        await { callbacks.size == index + 1 }
                    }
                    val before = schedules.size
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
                    await { schedules.size > before }
                    assertEquals(interval, schedules.last())
                }
            service.acquireWakelockAndRequestLocation()
            await { callbacks.size == 8 }
            callbacks.last().onLocationChanged(fix())
            await { MainActivity.dataRepository.locationInputStatus.value == LocationInputStatus.READY }
            assertEquals(0, policy(service).backoffLevel)
            await { schedules.last() == 30_000L }

            repeat(4) { policy(service).failed() }
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callbacks.size == 9 }
            assertEquals(0, policy(service).backoffLevel)
            val stale = callbacks.last()
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callbacks.size == 10 }
            stale.onLocationChanged(fix())
            assertEquals(0, policy(service).backoffLevel)
            // Cancelling a live generation removes its timeout rather than counting it.
            service.onDestroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            assertEquals(0, policy(service).backoffLevel)
        } finally {
            CotDispatchService.actualReportingIntervalSecs = previousInterval
            CotDispatchService.desiredLocationObservationIntervalSecs = previousObservation
            if (LocationService.isRunning.get()) service.onDestroy()
            unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun stationaryFusedUsesRecentHonestFixButInteractionRequiresFreshHighAccuracy() {
        val manager = mockk<LocationManager>(relaxed = true)
        val client = mockk<FusedLocationProviderClient>()
        val callbacks = mutableListOf<LocationCallback>()
        val requests = mutableListOf<LocationRequest>()
        every { client.requestLocationUpdates(capture(requests), capture(callbacks), any<Looper>()) } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<LocationCallback>()) } returns Tasks.forResult(null)
        val service = service(manager, LocationInput.FUSED, client)
        inject(service, "activity", ActivityRecognitionService.Companion.ActivityRecognized.STILL)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION)
        val previousInterval = CotDispatchService.actualReportingIntervalSecs
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(any()) } just Runs
        every { com.aitech.weartak_civ.Utils.startGlobalTimer(any()) } just Runs
        val observer = Observer()
        EventBus.getDefault().register(observer)
        try {
            CotDispatchService.actualReportingIntervalSecs = 30
            // Let the selected input generation age before obtaining a cached fix.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callbacks.size == 1 }
            val scheduled = requests[0]
            assertEquals(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, scheduled.priority)
            assertFalse(scheduled.isWaitForAccurateLocation)
            assertEquals(30_000L, scheduled.maxUpdateAgeMillis)
            repeat(4) { policy(service).failed() }
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            await { callbacks.size == 2 }
            assertEquals(0, policy(service).backoffLevel)
            assertEquals(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, requests[1].priority)
            assertTrue(requests[1].isWaitForAccurateLocation)
            assertEquals(0L, requests[1].maxUpdateAgeMillis)
            val cached = fix("fused").apply {
                time -= 20_000L
                elapsedRealtimeNanos -= 20_000_000_000L
                accuracy = 75f
                speed = 0f
            }
            callbacks[0].onLocationResult(LocationResult.create(listOf(Location(cached).apply {
                elapsedRealtimeNanos -= 11_000_000_000L
            })))
            callbacks[0].onLocationResult(LocationResult.create(listOf(Location(cached).apply {
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() + 1
            })))
            assertTrue(observer.normalEvents.isEmpty())
            callbacks[0].onLocationResult(LocationResult.create(listOf(cached)))
            await { observer.normalEvents.size == 1 }
            assertTrue(observer.events.isEmpty())
            val stored = requireNotNull(MainActivity.dataRepository.latestLocation.value)
            assertEquals(cached.time, stored.time)
            assertEquals(cached.elapsedRealtimeNanos, stored.elapsedRealtimeNanos)
            assertEquals(75f, stored.accuracy)
            assertTrue(requireNotNull(LocationService.speedObservation.value).validUntilElapsedNanos <=
                cached.elapsedRealtimeNanos + 36_000_000_000L)
            inject(service, "activity", ActivityRecognitionService.Companion.ActivityRecognized.UNKNOWN)
            service.acquireWakelockAndRequestLocation()
            await { callbacks.size == 3 }
            assertEquals(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, requests[2].priority)
            callbacks[1].onLocationResult(LocationResult.create(listOf(fix("fused"))))
            await { observer.events.size == 1 }
        } finally {
            CotDispatchService.actualReportingIntervalSecs = previousInterval
            service.onDestroy()
            EventBus.getDefault().unregister(observer)
            unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun fusedFailuresCountButStaleFailuresAndRevokedPermissionDoNot() {
        val manager = mockk<LocationManager>(relaxed = true)
        val client = mockk<FusedLocationProviderClient>()
        val tasks = mutableListOf<com.google.android.gms.tasks.TaskCompletionSource<Void>>()
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } answers {
            com.google.android.gms.tasks.TaskCompletionSource<Void>().also { tasks.add(it) }.task
        }
        every { client.removeLocationUpdates(any<LocationCallback>()) } returns Tasks.forResult(null)
        val service = service(manager, LocationInput.FUSED, client)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val previousInterval = CotDispatchService.actualReportingIntervalSecs
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(any()) } just Runs
        every { com.aitech.weartak_civ.Utils.startGlobalTimer(any()) } just Runs
        try {
            CotDispatchService.actualReportingIntervalSecs = 30
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { tasks.size == 1 }
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { tasks.size == 2 }
            tasks[0].setException(IllegalStateException("stale"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, policy(service).backoffLevel)
            repeat(3) { index ->
                if (index > 0) {
                    service.acquireWakelockAndRequestLocation()
                    await { tasks.size == index + 2 }
                }
                tasks.last().setException(IllegalStateException("unavailable"))
                await { MainActivity.dataRepository.locationInputStatus.value == LocationInputStatus.REQUEST_FAILED }
            }
            assertEquals(1, policy(service).backoffLevel)
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            await { tasks.size == 5 }
            assertEquals(0, policy(service).backoffLevel)
            service.acquireWakelockAndRequestLocation()
            await { tasks.size == 6 }
            shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            assertEquals(LocationInputStatus.PERMISSION_REQUIRED, MainActivity.dataRepository.locationInputStatus.value)
            assertEquals(0, policy(service).backoffLevel)
        } finally {
            CotDispatchService.actualReportingIntervalSecs = previousInterval
            service.onDestroy()
            unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun activityMovementResetsBackoffAndReprofilesPeriodicFusedWithoutSkippingObservations() {
        val manager = mockk<LocationManager>(relaxed = true)
        val client = mockk<FusedLocationProviderClient>()
        val requests = mutableListOf<LocationRequest>()
        val callbacks = mutableListOf<LocationCallback>()
        every { client.requestLocationUpdates(capture(requests), capture(callbacks), any<Looper>()) } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<LocationCallback>()) } returns Tasks.forResult(null)
        val service = service(manager, LocationInput.FUSED, client)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION)
        val previousInterval = CotDispatchService.actualReportingIntervalSecs
        val still = ActivityRecognitionService.Companion.ActivityRecognized.STILL
        try {
            runBlocking {
                MainActivity.dataRepository.storeEnableActivityRecognitionService(true)
                MainActivity.dataRepository.storeLatestActivityRecognized(still)
            }
            LocationService::class.java.getDeclaredMethod("startAcquisitionActivityCollector").apply {
                isAccessible = true; invoke(service)
            }
            await {
                LocationService::class.java.getDeclaredField("activity").let {
                    it.isAccessible = true; it.get(service) == still
                }
            }
            CotDispatchService.actualReportingIntervalSecs = 1
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { requests.size == 1 }
            assertEquals(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, requests.last().priority)
            callbacks[0].onLocationResult(LocationResult.create(listOf(fix("fused").apply { speed = 0f })))
            await { LocationService.speedObservation.value != null }
            repeat(4) { policy(service).failed() }
            runBlocking { MainActivity.dataRepository.storeLatestActivityRecognized(
                ActivityRecognitionService.Companion.ActivityRecognized.WALKING) }
            await { requests.size == 2 }
            assertEquals(0, policy(service).backoffLevel)
            assertEquals(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, requests.last().priority)
            assertEquals(0L, requests.last().maxUpdateAgeMillis)
            verify { client.removeLocationUpdates(callbacks[0]) }
            runBlocking { MainActivity.dataRepository.storeLatestActivityRecognized(still) }
            await { requests.size == 3 }
            assertEquals(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, requests.last().priority)
            LocationService.speedObservation.value = null
            runBlocking { MainActivity.dataRepository.storeEnableActivityRecognitionService(false) }
            await { requests.size == 4 }
            assertEquals(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, requests.last().priority)
        } finally {
            CotDispatchService.actualReportingIntervalSecs = previousInterval
            service.onDestroy()
        }
    }

    @Test fun identicalCachedFixStillSchedulesNextDynamicObservationWithoutRenewingSpeed() {
        val manager = mockk<LocationManager>(relaxed = true)
        val client = mockk<FusedLocationProviderClient>()
        val callbacks = mutableListOf<LocationCallback>()
        every { client.requestLocationUpdates(any<LocationRequest>(), capture(callbacks), any<Looper>()) } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<LocationCallback>()) } returns Tasks.forResult(null)
        val service = service(manager, LocationInput.FUSED, client)
        inject(service, "dynamicReporting", true)
        inject(service, "activity", ActivityRecognitionService.Companion.ActivityRecognized.STILL)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION)
        val oldObservation = CotDispatchService.desiredLocationObservationIntervalSecs
        val schedules = mutableListOf<Long>()
        val observer = Observer()
        EventBus.getDefault().register(observer)
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(capture(schedules)) } just Runs
        try {
            CotDispatchService.desiredLocationObservationIntervalSecs = 10
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callbacks.size == 1 }
            val cached = fix("fused").apply {
                time -= 1_000
                elapsedRealtimeNanos -= 1_000_000_000L
                speed = 0f
            }
            callbacks[0].onLocationResult(LocationResult.create(listOf(cached)))
            await { observer.normalEvents.size == 1 && schedules.size == 1 }
            val firstObservation = requireNotNull(LocationService.speedObservation.value)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            service.acquireWakelockAndRequestLocation()
            await { callbacks.size == 2 }
            callbacks[1].onLocationResult(LocationResult.create(listOf(cached)))
            await { schedules.size == 2 }
            assertEquals(10_000L, schedules.last())
            assertEquals(1, observer.normalEvents.size)
            assertSame(firstObservation, LocationService.speedObservation.value)
        } finally {
            CotDispatchService.desiredLocationObservationIntervalSecs = oldObservation
            service.onDestroy()
            EventBus.getDefault().unregister(observer)
            unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun powerPolicyChangeResetsBackoffWithoutCancellingPendingDynamicObservation() {
        val manager = mockk<LocationManager>(relaxed = true)
        val callbacks = mutableListOf<LocationListener>()
        every { manager.requestLocationUpdates("gps", any<Long>(), any<Float>(),
            capture(callbacks), any<Looper>()) } just Runs
        val service = service(manager)
        inject(service, "dynamicReporting", true)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val oldObservation = CotDispatchService.desiredLocationObservationIntervalSecs
        val oldPower = PowerManagerService.observationPowerThrottled
        val powerField = PowerManagerService::class.java.getDeclaredField("observationPowerThrottled")
            .apply { isAccessible = true }
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(any()) } just Runs
        try {
            CotDispatchService.desiredLocationObservationIntervalSecs = 30
            powerField.setBoolean(null, false)
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callbacks.size == 1 }
            repeat(4) { policy(service).failed() }
            powerField.setBoolean(null, true)
            service.onPowerManagerEvent(PowerManagerEvent(PowerManagerEvent.CHANGED_UPDATE_INTERVAL))
            assertEquals(0, policy(service).backoffLevel)
            verify(exactly = 0) { manager.removeUpdates(callbacks[0]) }
            assertEquals(1, callbacks.size)
        } finally {
            CotDispatchService.desiredLocationObservationIntervalSecs = oldObservation
            powerField.setBoolean(null, oldPower)
            service.onDestroy()
            unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun cancelledFusedRegistrationsNeverBecomeTimeoutFailures() {
        val manager = mockk<LocationManager>(relaxed = true)
        val client = mockk<FusedLocationProviderClient>()
        val cancellations = mutableListOf<com.google.android.gms.tasks.CancellationTokenSource>()
        every { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) } answers {
            com.google.android.gms.tasks.CancellationTokenSource().let {
                cancellations.add(it)
                com.google.android.gms.tasks.TaskCompletionSource<Void>(it.token).task
            }
        }
        every { client.removeLocationUpdates(any<LocationCallback>()) } returns Tasks.forResult(null)
        val service = service(manager, LocationInput.FUSED, client)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val previousInterval = CotDispatchService.actualReportingIntervalSecs
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(any()) } just Runs
        every { com.aitech.weartak_civ.Utils.startGlobalTimer(any()) } just Runs
        try {
            CotDispatchService.actualReportingIntervalSecs = 30
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { cancellations.size == 1 }
            repeat(4) { index ->
                if (index > 0) {
                    service.acquireWakelockAndRequestLocation()
                    await { cancellations.size == index + 1 }
                }
                cancellations.last().cancel()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            }
            assertEquals(0, policy(service).backoffLevel)
            assertNotEquals(LocationInputStatus.REQUEST_FAILED, MainActivity.dataRepository.locationInputStatus.value)
        } finally {
            CotDispatchService.actualReportingIntervalSecs = previousInterval
            service.onDestroy()
            unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun disablingLocationDuringAcquisitionDoesNotCountTimeoutAsFailure() {
        val manager = mockk<LocationManager>(relaxed = true)
        val callbacks = mutableListOf<LocationListener>()
        every { manager.requestLocationUpdates("gps", any<Long>(), any<Float>(),
            capture(callbacks), any<Looper>()) } just Runs
        val service = service(manager)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val previousInterval = CotDispatchService.actualReportingIntervalSecs
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(any()) } just Runs
        try {
            CotDispatchService.actualReportingIntervalSecs = 30
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callbacks.size == 1 }
            repeat(2) { policy(service).failed() }
            runBlocking { MainActivity.dataRepository.storeEnableLocationService(false) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            await { MainActivity.dataRepository.locationInputStatus.value == LocationInputStatus.OFF }
            assertEquals(0, policy(service).backoffLevel)
            verify { manager.removeUpdates(callbacks[0]) }
        } finally {
            CotDispatchService.actualReportingIntervalSecs = previousInterval
            service.onDestroy()
            unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun oneShotFixCompletesBothPurposesWithoutSkippingTheirNativeRequests() =
        sharedGnssCompletion(periodic = false, interactionDeliversFirst = false)

    @Test fun interactionFixCompletesPeriodicPurposeOnceButDoesNotDropImprovedSameTimestampFix() =
        sharedGnssCompletion(periodic = true, interactionDeliversFirst = true)

    private fun sharedGnssCompletion(periodic: Boolean, interactionDeliversFirst: Boolean) {
        val manager = mockk<LocationManager>(relaxed = true)
        val callbacks = mutableListOf<LocationListener>()
        every { manager.requestLocationUpdates("gps", any<Long>(), any<Float>(),
            capture(callbacks), any<Looper>()) } just Runs
        val service = service(manager)
        val originalRepository = MainActivity.dataRepository
        val repository = spyk(originalRepository)
        coEvery { repository.storeLatestLocation(any()) } coAnswers {
            originalRepository.storeLatestLocation(firstArg())
        }
        MainActivity.dataRepository = repository
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val previousInterval = CotDispatchService.actualReportingIntervalSecs
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(any()) } just Runs
        val observer = Observer()
        EventBus.getDefault().register(observer)
        try {
            CotDispatchService.actualReportingIntervalSecs = if (periodic) 1 else 30
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callbacks.size == 1 }
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            await { callbacks.size == 2 }
            verify(exactly = 2) { manager.requestLocationUpdates("gps", any<Long>(), any<Float>(),
                any<LocationListener>(), any<Looper>()) }
            val fresh = fix()
            callbacks[if (interactionDeliversFirst) 1 else 0].onLocationChanged(fresh)
            await { observer.events.size == 1 && observer.normalEvents.size == 1 }
            coVerify(exactly = 1) { repository.storeLatestLocation(any()) }
            assertEquals(setOf(PliRefreshReason.MAP_OPENED), observer.events.single().reasons)
            verify { manager.removeUpdates(callbacks[1]) }
            if (!periodic) verify { manager.removeUpdates(callbacks[0]) }
            callbacks[1].onProviderDisabled("gps")
            callbacks[1].onProviderEnabled("gps")
            callbacks[1].onLocationChanged(fix())
            callbacks[0].onLocationChanged(Location(fresh))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, callbacks.size)
            coVerify(exactly = 1) { repository.storeLatestLocation(any()) }
            assertEquals(1, observer.events.size)
            assertEquals(1, observer.normalEvents.size)
            if (periodic) {
                val improved = Location(fresh).apply { speed = 2f }
                callbacks[0].onLocationChanged(improved)
                await { observer.normalEvents.size == 2 }
                coVerify(exactly = 2) { repository.storeLatestLocation(any()) }
                assertEquals(2f, repository.latestLocation.value!!.speed)
            } else {
                service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.ROLE_CHANGED))
                await { callbacks.size == 3 }
                callbacks[2].onLocationChanged(Location(fresh))
                await { observer.events.size == 2 }
                coVerify(exactly = 1) { repository.storeLatestLocation(any()) }
                assertEquals(1, observer.normalEvents.size)
                assertEquals(setOf(PliRefreshReason.ROLE_CHANGED), observer.events.last().reasons)
            }
        } finally {
            CotDispatchService.actualReportingIntervalSecs = previousInterval
            service.onDestroy()
            EventBus.getDefault().unregister(observer)
            unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun sharedFixMustFollowLatestActionAndCannotExtendInteractionTimeoutOrReviveOldCallbacks() {
        val manager = mockk<LocationManager>(relaxed = true)
        val callbacks = mutableListOf<LocationListener>()
        every { manager.requestLocationUpdates("gps", any<Long>(), any<Float>(),
            capture(callbacks), any<Looper>()) } just Runs
        val service = service(manager)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val previousInterval = CotDispatchService.actualReportingIntervalSecs
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(any()) } just Runs
        val observer = Observer()
        EventBus.getDefault().register(observer)
        try {
            CotDispatchService.actualReportingIntervalSecs = 30
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callbacks.size == 1 }
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            await { callbacks.size == 2 }
            val beforeLatestAction = fix()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.CHAT_SENT))
            callbacks[0].onLocationChanged(beforeLatestAction)
            await { observer.normalEvents.size == 1 }
            assertTrue(observer.events.isEmpty())
            verify(exactly = 0) { manager.removeUpdates(callbacks[1]) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            verify { manager.removeUpdates(callbacks[1]) }
            assertTrue(observer.events.isEmpty())
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.ROLE_CHANGED))
            await { callbacks.size == 3 }
            callbacks[0].onLocationChanged(fix())
            callbacks[1].onLocationChanged(fix())
            assertTrue(observer.events.isEmpty())
            callbacks[2].onLocationChanged(fix())
            await { observer.events.size == 1 }
            assertEquals(setOf(PliRefreshReason.ROLE_CHANGED), observer.events.single().reasons)
            assertEquals(1, observer.normalEvents.size)
        } finally {
            CotDispatchService.actualReportingIntervalSecs = previousInterval
            service.onDestroy()
            EventBus.getDefault().unregister(observer)
            unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun fusedFixFansOutOnlyToEligibleSameProviderPurposesWithOriginalDeadlines() {
        val manager = mockk<LocationManager>(relaxed = true)
        val client = mockk<FusedLocationProviderClient>()
        val callbacks = mutableListOf<LocationCallback>()
        val requests = mutableListOf<LocationRequest>()
        every { client.requestLocationUpdates(capture(requests), capture(callbacks), any<Looper>()) } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<LocationCallback>()) } returns Tasks.forResult(null)
        val service = service(manager, LocationInput.FUSED, client)
        val originalRepository = MainActivity.dataRepository
        val repository = spyk(originalRepository)
        coEvery { repository.storeLatestLocation(any()) } coAnswers {
            originalRepository.storeLatestLocation(firstArg())
        }
        MainActivity.dataRepository = repository
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val previousInterval = CotDispatchService.actualReportingIntervalSecs
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(any()) } just Runs
        val observer = Observer()
        EventBus.getDefault().register(observer)
        try {
            CotDispatchService.actualReportingIntervalSecs = 30
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callbacks.size == 1 }
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.SELF_MARKER))
            await { callbacks.size == 2 }
            assertEquals(listOf(6_000L, 5_000L), requests.map { it.durationMillis })
            assertTrue(requests.all { it.maxUpdateAgeMillis == 0L })
            callbacks[0].onLocationResult(LocationResult.create(listOf(fix("network"))))
            assertTrue(observer.events.isEmpty())
            assertTrue(observer.normalEvents.isEmpty())
            callbacks[0].onLocationResult(LocationResult.create(listOf(fix("fused"))))
            await { observer.events.size == 1 && observer.normalEvents.size == 1 }
            coVerify(exactly = 1) { repository.storeLatestLocation(any()) }
            callbacks.forEach { verify { client.removeLocationUpdates(it) } }
            callbacks[1].onLocationResult(LocationResult.create(listOf(fix("fused"))))
            assertEquals(1, observer.events.size)
            coVerify(exactly = 1) { repository.storeLatestLocation(any()) }
            verify(exactly = 0) { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
                any<LocationListener>(), any<Looper>()) }
        } finally {
            CotDispatchService.actualReportingIntervalSecs = previousInterval
            service.onDestroy()
            EventBus.getDefault().unregister(observer)
            unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun delayedMainQueueCannotUseSixSecondFixToExtendFiveSecondInteractionDeadline() {
        val manager = mockk<LocationManager>(relaxed = true)
        val callbacks = mutableListOf<LocationListener>()
        every { manager.requestLocationUpdates("gps", any<Long>(), any<Float>(),
            capture(callbacks), any<Looper>()) } just Runs
        val service = service(manager)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val previousInterval = CotDispatchService.actualReportingIntervalSecs
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(any()) } just Runs
        val observer = Observer()
        EventBus.getDefault().register(observer)
        try {
            CotDispatchService.actualReportingIntervalSecs = 30
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { callbacks.size == 1 }
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            await { callbacks.size == 2 }
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(5_100))
            callbacks[0].onLocationChanged(fix())
            await { observer.normalEvents.size == 1 }
            assertTrue(observer.events.isEmpty())
            verify { manager.removeUpdates(callbacks[1]) }
            assertEquals(2, callbacks.size)
        } finally {
            CotDispatchService.actualReportingIntervalSecs = previousInterval
            service.onDestroy()
            EventBus.getDefault().unregister(observer)
            unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun sourceChangeWhileSharedStoreIsSuspendedCannotPublishOldCompletion() {
        val manager = mockk<LocationManager>(relaxed = true)
        val callbacks = mutableListOf<LocationListener>()
        every { manager.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(),
            capture(callbacks), any<Looper>()) } just Runs
        val service = service(manager)
        val originalRepository = MainActivity.dataRepository
        val repository = spyk(originalRepository)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { repository.storeLatestLocation(any()) } coAnswers {
            val received = firstArg<Location>()
            entered.complete(Unit)
            release.await()
            originalRepository.storeLatestLocation(received)
        }
        MainActivity.dataRepository = repository
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val observer = Observer()
        EventBus.getDefault().register(observer)
        try {
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            await { callbacks.size == 1 }
            callbacks[0].onLocationChanged(fix())
            await { entered.isCompleted }
            runBlocking { originalRepository.storeLocationInput(LocationInput.NETWORK) }
            LocationService::class.java.getDeclaredField("currentInput").apply {
                isAccessible = true; set(service, LocationInput.NETWORK)
            }
            release.complete(Unit)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(originalRepository.latestLocation.value)
            assertTrue(observer.events.isEmpty())
            assertTrue(observer.normalEvents.isEmpty())
        } finally {
            release.complete(Unit)
            service.onDestroy()
            EventBus.getDefault().unregister(observer)
        }
    }

    @Test fun dynamicActivityCadenceDoesNotRestartPendingFixOnSpeedOrUnknown() {
        val manager = mockk<LocationManager>(relaxed = true)
        val listener = slot<LocationListener>()
        every { manager.requestLocationUpdates("gps", any<Long>(), any<Float>(),
            capture(listener), any<Looper>()) } just Runs
        val service = service(manager)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        LocationService::class.java.getDeclaredField("dynamicReporting").apply {
            isAccessible = true; set(service, true)
        }
        val oldDesired = CotDispatchService.desiredReportingIntervalSecs
        val oldActual = CotDispatchService.actualReportingIntervalSecs
        val oldObservation = CotDispatchService.desiredLocationObservationIntervalSecs
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(any()) } just Runs
        try {
            CotDispatchService.desiredReportingIntervalSecs = 3600
            CotDispatchService.actualReportingIntervalSecs = 3600
            CotDispatchService.desiredLocationObservationIntervalSecs = 3600
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await("initial dynamic request") { listener.isCaptured }
            val first = listener.captured
            CotDispatchService.desiredReportingIntervalSecs = 60
            CotDispatchService.actualReportingIntervalSecs = 60
            CotDispatchService.desiredLocationObservationIntervalSecs = 60
            service.onPowerManagerEvent(PowerManagerEvent(PowerManagerEvent.CHANGED_UPDATE_INTERVAL))
            verify(exactly = 1) { manager.requestLocationUpdates("gps", any<Long>(), any<Float>(),
                any<LocationListener>(), any<Looper>()) }
            verify(exactly = 0) { manager.removeUpdates(first) }
            first.onLocationChanged(fix().apply { speed = 0f })
            await("accepted dynamic fix") { LocationService.speedObservation.value != null }
            val observation = requireNotNull(LocationService.speedObservation.value)
            verify { com.aitech.weartak_civ.Utils.runTimerJob(60_000L) }
            assertTrue(observation.validUntilElapsedNanos > observation.location.elapsedRealtimeNanos)
            assertTrue(observation.validUntilElapsedNanos - SystemClock.elapsedRealtimeNanos() <= 66_000_000_000L)

            // Unknown/expiry affects reporting only; it must not renew the old speed's lifetime.
            service.acquireWakelockAndRequestLocation()
            await("next scheduled observation") { listener.captured !== first }
            val pending = listener.captured
            service.acquireWakelockAndRequestLocation()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(pending, listener.captured)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            await("pending observation timeout") { MainActivity.dataRepository.locationInputStatus.value == LocationInputStatus.REQUEST_FAILED }
            assertSame(observation, LocationService.speedObservation.value)
            runBlocking { MainActivity.dataRepository.storeEnableLocationService(false) }
            val before = listener.captured
            service.acquireWakelockAndRequestLocation()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(before, listener.captured)
        } finally {
            CotDispatchService.desiredReportingIntervalSecs = oldDesired
            CotDispatchService.actualReportingIntervalSecs = oldActual
            CotDispatchService.desiredLocationObservationIntervalSecs = oldObservation
            service.onDestroy()
            unmockkObject(com.aitech.weartak_civ.Utils)
        }
    }

    @Test fun activityReschedulesFromLastObservationWithoutRenewingSpeedOrRequestingAnotherFreshFix() {
        val manager = mockk<LocationManager>(relaxed = true)
        val listener = slot<LocationListener>()
        every { manager.requestLocationUpdates("gps", any<Long>(), any<Float>(),
            capture(listener), any<Looper>()) } just Runs
        val service = service(manager)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        LocationService::class.java.getDeclaredField("dynamicReporting").apply {
            isAccessible = true; set(service, true)
        }
        val oldObservation = CotDispatchService.desiredLocationObservationIntervalSecs
        mockkObject(com.aitech.weartak_civ.Utils)
        every { com.aitech.weartak_civ.Utils.runTimerJob(any()) } just Runs
        try {
            CotDispatchService.desiredLocationObservationIntervalSecs = 3600
            service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
            await { listener.isCaptured }
            listener.captured.onLocationChanged(fix().apply { speed = 0f })
            await { LocationService.speedObservation.value != null }
            val stationaryDue = requireNotNull(LocationService.nextObservationDeadlineNanos.value)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            CotDispatchService.desiredLocationObservationIntervalSecs = 60
            service.onPowerManagerEvent(PowerManagerEvent(PowerManagerEvent.CHANGED_UPDATE_INTERVAL))
            val movingDue = stationaryDue - 3_600_000_000_000L + 60_000_000_000L
            await { LocationService.nextObservationDeadlineNanos.value == movingDue }
            verify(exactly = 1) { manager.requestLocationUpdates("gps", any<Long>(), any<Float>(),
                any<LocationListener>(), any<Looper>()) }
            val expires = requireNotNull(LocationService.speedObservation.value).validUntilElapsedNanos
            assertEquals(movingDue + 6_000_000_000L, expires)
            service.onPowerManagerEvent(PowerManagerEvent(PowerManagerEvent.CHANGED_UPDATE_INTERVAL))
            assertEquals(movingDue, LocationService.nextObservationDeadlineNanos.value)
            CotDispatchService.desiredLocationObservationIntervalSecs = 3600
            service.onPowerManagerEvent(PowerManagerEvent(PowerManagerEvent.CHANGED_UPDATE_INTERVAL))
            await { LocationService.nextObservationDeadlineNanos.value == stationaryDue }
            assertEquals(expires, requireNotNull(LocationService.speedObservation.value).validUntilElapsedNanos)
        } finally {
            CotDispatchService.desiredLocationObservationIntervalSecs = oldObservation
            service.onDestroy()
            unmockkObject(com.aitech.weartak_civ.Utils)
        }
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
            await { MainActivity.dataRepository.locationInputStatus.value == LocationInputStatus.REQUEST_FAILED }
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
            await { MainActivity.dataRepository.locationInputStatus.value == LocationInputStatus.REQUEST_FAILED }
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
