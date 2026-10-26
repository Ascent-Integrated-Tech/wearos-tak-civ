package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.LocationInput
import com.aitech.weartak_civ.events.LocationRcvd
import com.aitech.weartak_civ.events.LocationUpdateRequired
import com.aitech.weartak_civ.events.InteractionPliRefresh
import com.aitech.weartak_civ.events.PliRefreshReason
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MotionConfirmationTest {
    private lateinit var service: LocationService
    private lateinit var repo: DataRepository
    private val manager = mockk<LocationManager>(relaxed = true)
    private val sensors = mockk<SensorManager>(relaxed = true)
    private val sensor = mockk<Sensor>(relaxed = true)
    private val fixes = mutableListOf<LocationListener>()
    private val triggers = mutableListOf<TriggerEventListener>()
    private val schedules = mutableListOf<Long>()
    private var oldObservation = 0
    private var oldReport = 0
    class Observer {
        var normal = 0
        @Subscribe fun receive(event: LocationRcvd) { normal++ }
    }
    private val observer = Observer()

    @Before fun setup() {
        MainActivity.device_uid = "motion-test"
        repo = DataRepository(RuntimeEnvironment.getApplication())
        MainActivity.dataRepository = repo
        runBlocking {
            repo.storeEnableLocationService(true)
            repo.storeLocationInput(LocationInput.INTERNAL_GNSS)
            repo.storeEnableDynamicReportingStrategy(true)
            repo.storeOnFootReportingInterval(60)
            repo.storeVehicleReportingInterval(60)
            repo.storeStationaryReportingInterval(3600)
        }
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION)
        every { manager.allProviders } returns listOf("gps")
        every { manager.isProviderEnabled(any()) } returns true
        every { manager.isLocationEnabled } returns true
        every { manager.requestLocationUpdates("gps", any<Long>(), any<Float>(), capture(fixes), any<Looper>()) } just Runs
        every { sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) } returns sensor
        every { sensors.requestTriggerSensor(capture(triggers), sensor) } returns true
        every { sensors.cancelTriggerSensor(any(), sensor) } returns true
        service = Robolectric.buildService(LocationService::class.java).get()
        inject("locationManager", manager)
        inject("motionSensorManager", sensors)
        inject("currentInput", LocationInput.INTERNAL_GNSS)
        inject("dynamicReporting", true)
        inject("activity", ActivityRecognized.STILL)
        LocationService.isRunning.set(true)
        LocationService.speedObservation.value = null
        oldObservation = CotDispatchService.desiredLocationObservationIntervalSecs
        oldReport = CotDispatchService.actualReportingIntervalSecs
        CotDispatchService.desiredLocationObservationIntervalSecs = 3600
        mockkObject(Utils)
        every { Utils.runTimerJob(capture(schedules)) } just Runs
        every { Utils.startGlobalTimer(any()) } just Runs
        EventBus.getDefault().register(observer)
    }

    @After fun cleanup() {
        if (LocationService.isRunning.get()) service.onDestroy()
        CotDispatchService.desiredLocationObservationIntervalSecs = oldObservation
        CotDispatchService.actualReportingIntervalSecs = oldReport
        EventBus.getDefault().unregister(observer)
        unmockkObject(Utils)
    }

    private fun inject(name: String, value: Any) {
        LocationService::class.java.getDeclaredField(name).apply { isAccessible = true; set(service, value) }
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(5000) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(5)
            }
        }
    }

    private fun fix(speed: Float? = 0f, accuracy: Float = 5f, latitude: Double = 41.0) =
        Location("gps").apply {
            this.latitude = latitude
            longitude = -87.0
            altitude = 100.0
            this.accuracy = accuracy
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (speed != null) {
                this.speed = speed
                speedAccuracyMetersPerSecond = 0.1f
            }
        }

    private fun stationary() {
        service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
        await { fixes.size == 1 }
        fixes.single().onLocationChanged(fix())
        await { triggers.size == 1 && repo.stationaryMotion.state.value.stationary && schedules.isNotEmpty() }
    }

    private fun trigger(): LocationListener {
        val before = fixes.size
        triggers.last().onTrigger(null)
        await { fixes.size == before + 1 }
        return fixes.last()
    }

    @Test fun deskGestureConsumesOneFixWithoutSpeedDeadlineBackoffOrReportChanges() {
        stationary()
        val deadline = LocationService.nextObservationDeadlineNanos.value
        val speed = LocationService.speedObservation.value
        val normal = observer.normal
        val scheduleCount = schedules.size
        val callback = trigger()
        callback.onLocationChanged(fix(0.6f, latitude = 41.00001))
        await { triggers.size == 2 }
        callback.onLocationChanged(fix(20f, latitude = 42.0))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(repo.stationaryMotion.state.value.stationary)
        assertEquals(deadline, LocationService.nextObservationDeadlineNanos.value)
        assertSame(speed, LocationService.speedObservation.value)
        assertEquals(normal, observer.normal)
        assertEquals(scheduleCount, schedules.size)
        assertEquals(3600, CotDispatchService.desiredLocationObservationIntervalSecs)
        verify(exactly = 1) { manager.removeUpdates(callback) }
    }

    @Test fun confirmedSpeedSelectsMovingCadenceAndAllowsAnImmediatePli() {
        stationary()
        val normal = observer.normal
        trigger().onLocationChanged(fix(1.2f))
        await { repo.stationaryMotion.state.value.movingUntilNanos > 0 && schedules.last() == 60_000L }
        assertFalse(repo.stationaryMotion.state.value.stationary)
        assertEquals(1.2, repo.stationaryMotion.state.value.confirmedSpeedMps!!, 0.001)
        assertEquals(normal + 1, observer.normal)
        assertEquals(1, triggers.size)
    }

    @Test fun displacementWithoutSpeedOverridesOldStillAndNeverInventsSpeed() {
        stationary()
        trigger().onLocationChanged(fix(null, latitude = 41.001))
        await { repo.stationaryMotion.state.value.movingUntilNanos > 0 }
        assertNull(repo.stationaryMotion.state.value.confirmedSpeedMps)
        assertEquals(ActivityRecognized.UNKNOWN, repo.stationaryMotion.activity(ActivityRecognized.STILL,
            SystemClock.elapsedRealtimeNanos()))
        assertEquals(60, CotDispatchService.desiredLocationObservationIntervalSecs)
    }

    @Test fun poorAccuracyConsumesFirstCandidateInsteadOfHuntingForAnother() {
        stationary()
        val callback = trigger()
        callback.onLocationChanged(fix(20f, accuracy = 100f))
        await { triggers.size == 2 }
        callback.onLocationChanged(fix(20f))
        assertTrue(repo.stationaryMotion.state.value.stationary)
        assertEquals(2, fixes.size)
    }

    @Test fun preRequestAndFutureFixesCannotConfirmAndTimeoutRearmsWithoutRescheduling() {
        stationary()
        val oldFix = fix(20f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        val deadline = LocationService.nextObservationDeadlineNanos.value
        val count = schedules.size
        val callback = trigger()
        callback.onLocationChanged(oldFix)
        callback.onLocationChanged(fix(20f).apply { elapsedRealtimeNanos += 10_000_000_000L })
        assertEquals(1, triggers.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        await { triggers.size == 2 }
        callback.onLocationChanged(fix(20f))
        assertTrue(repo.stationaryMotion.state.value.stationary)
        assertEquals(deadline, LocationService.nextObservationDeadlineNanos.value)
        assertEquals(count, schedules.size)
    }

    @Test fun duplicateAndStaleTriggerCallbacksCannotCreateExtraAcquisitions() {
        stationary()
        val first = triggers.single()
        trigger().onLocationChanged(fix())
        await { triggers.size == 2 }
        first.onTrigger(null)
        first.onTrigger(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, fixes.size)
        triggers.last().onTrigger(null)
        triggers.last().onTrigger(null)
        await { fixes.size == 3 }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, fixes.size)
    }

    @Test fun absentSensorRetainsIncumbentSpeedAndNeverLatches() {
        every { sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) } returns null
        service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
        await { fixes.size == 1 }
        fixes.single().onLocationChanged(fix())
        await { LocationService.speedObservation.value != null }
        assertEquals(StationaryMotionPolicy.State(), repo.stationaryMotion.state.value)
        assertTrue(triggers.isEmpty())
    }

    @Test fun initialArmingFailureRetainsIncumbentBehavior() {
        every { sensors.requestTriggerSensor(any(), sensor) } returns false
        service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
        await { fixes.size == 1 }
        fixes.single().onLocationChanged(fix())
        await { LocationService.speedObservation.value != null }
        assertEquals(StationaryMotionPolicy.State(), repo.stationaryMotion.state.value)
    }

    @Test fun rearmFailureReleasesLatchAndFallsBackWithoutAnotherFix() {
        stationary()
        val incumbentSpeed = requireNotNull(LocationService.speedObservation.value)
        every { sensors.requestTriggerSensor(any(), sensor) } returns false
        trigger().onLocationChanged(fix())
        await { !repo.stationaryMotion.state.value.stationary }
        assertEquals(2, fixes.size)
        assertSame(incumbentSpeed, LocationService.speedObservation.value)
        assertEquals(0f, incumbentSpeed.location.speed)
    }

    @Test fun destroyInvalidatesTriggerAndFixAndReleasesTimedLock() {
        stationary()
        val callback = trigger()
        val stale = triggers.single()
        service.onDestroy()
        callback.onLocationChanged(fix(20f))
        stale.onTrigger(null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(StationaryMotionPolicy.State(), repo.stationaryMotion.state.value)
        assertEquals(2, fixes.size)
    }

    @Test fun manualSosWinsOverQueuedMotionResult() {
        stationary()
        val callback = trigger()
        runBlocking {
            repo.appendManualAlert(DataRepository.CustomEmergencyAlert(
                "test-alert", DataRepository.AlertState.ALERT, "SOS", "test",
                1, false, System.currentTimeMillis(), false))
            withTimeout(5000) { repo.isAlerting.first { it } }
        }
        callback.onLocationChanged(fix(20f))
        await { !repo.stationaryMotion.state.value.stationary }
        assertEquals(0, repo.stationaryMotion.state.value.movingUntilNanos)
    }

    @Test fun explicitInteractionStillCompletesWhileStationary() {
        stationary()
        service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.CALLSIGN_CHANGED))
        await { fixes.size == 2 }
        fixes.last().onLocationChanged(fix())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(repo.stationaryMotion.state.value.stationary)
        verify { manager.removeUpdates(fixes.last()) }
    }

    @Test fun triggerSharesAlreadyPendingFreshInteractionWithoutExtendingIt() {
        stationary()
        service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
        await { fixes.size == 2 }
        triggers.last().onTrigger(null)
        await {
            LocationService::class.java.getDeclaredField("sharedMotionRequest").apply {
                isAccessible = true
            }.getBoolean(service)
        }
        assertEquals(2, fixes.size)
        fixes.last().onLocationChanged(fix())
        await { triggers.size == 2 }
        assertTrue(repo.stationaryMotion.state.value.stationary)
        assertEquals(2, fixes.size)
    }

    @Test fun lateFixBeforeTimeoutRunnableCannotConfirmAfterElapsedDeadline() {
        stationary()
        val callback = trigger()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(7))
        callback.onLocationChanged(fix(20f))
        shadowOf(Looper.getMainLooper()).idle()
        await { triggers.size == 2 }
        assertTrue(repo.stationaryMotion.state.value.stationary)
    }

    @Test fun winningFixCancelsTimeoutRatherThanRearmingAfterConfirmation() {
        stationary()
        trigger().onLocationChanged(fix(1.2f))
        await { !repo.stationaryMotion.state.value.stationary }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(1, triggers.size)
        assertTrue(repo.stationaryMotion.state.value.movingUntilNanos > SystemClock.elapsedRealtimeNanos())
    }

    @Test fun constantModeStillAcceptsMotionEvidenceBeforeItsCollectorRuns() {
        stationary()
        val callback = trigger()
        runBlocking { repo.storeEnableDynamicReportingStrategy(false) }
        callback.onLocationChanged(fix(20f))
        await { !repo.stationaryMotion.state.value.stationary }
        assertTrue(repo.stationaryMotion.state.value.movingUntilNanos > SystemClock.elapsedRealtimeNanos())
        assertEquals(1, triggers.size)
    }

    @Test fun phoneManagedOverrideInvalidatesPendingConfirmation() {
        stationary()
        val callback = trigger()
        runBlocking { repo.enablePhoneManagedSettings("test-companion") }
        repo.setPhoneManagementSessionActive(true)
        callback.onLocationChanged(fix(20f))
        await { !repo.stationaryMotion.state.value.stationary }
        assertEquals(0, repo.stationaryMotion.state.value.movingUntilNanos)
        assertEquals(1, triggers.size)
    }

    @Test fun sourceRestartRejectsBothOldTriggerAndPendingFix() {
        stationary()
        val callback = trigger()
        val stale = triggers.single()
        service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
        await { fixes.size == 3 }
        callback.onLocationChanged(fix(20f))
        stale.onTrigger(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(StationaryMotionPolicy.State(), repo.stationaryMotion.state.value)
        assertEquals(3, fixes.size)
    }

    @Test fun revokedLocationPermissionPreventsTriggerAcquisitionAndRearm() {
        stationary()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        triggers.single().onTrigger(null)
        await { !repo.stationaryMotion.state.value.stationary }
        assertEquals(1, fixes.size)
        assertEquals(1, triggers.size)
    }

    @Test fun repeatedFalseTriggersKeepOriginalAnchorForCumulativeDisplacement() {
        stationary()
        trigger().onLocationChanged(fix(null, latitude = 41.0001))
        await { triggers.size == 2 }
        trigger().onLocationChanged(fix(null, latitude = 41.0002))
        await { !repo.stationaryMotion.state.value.stationary }
        assertNull(repo.stationaryMotion.state.value.confirmedSpeedMps)
    }

    @Test fun registrationExceptionAndRemovalFailureRemainSafe() {
        stationary()
        every { sensors.cancelTriggerSensor(any(), sensor) } returns false
        val stale = triggers.single()
        service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
        await { fixes.size == 2 }
        stale.onTrigger(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, fixes.size)
        every { sensors.requestTriggerSensor(any(), sensor) } throws SecurityException("denied")
        fixes.last().onLocationChanged(fix())
        await { LocationService.speedObservation.value != null }
        assertEquals(StationaryMotionPolicy.State(), repo.stationaryMotion.state.value)
    }

    @Test fun fusedMotionRequestIsFreshHighAccuracyEvenWhileStationary() {
        val client = mockk<FusedLocationProviderClient>()
        val callbacks = mutableListOf<LocationCallback>()
        val requests = mutableListOf<LocationRequest>()
        every { client.requestLocationUpdates(capture(requests), capture(callbacks), any<Looper>()) } returns Tasks.forResult(null)
        every { client.removeLocationUpdates(any<LocationCallback>()) } returns Tasks.forResult(null)
        runBlocking { repo.storeLocationInput(LocationInput.FUSED) }
        inject("currentInput", LocationInput.FUSED)
        inject("fusedClient", client)
        service.onLocationUpdateRequiredEvent(LocationUpdateRequired())
        await { callbacks.size == 1 }
        callbacks.single().onLocationResult(LocationResult.create(listOf(fix().apply { provider = "fused" })))
        await { triggers.size == 1 }
        triggers.single().onTrigger(null)
        await { callbacks.size == 2 }
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, requests.last().priority)
        assertEquals(0L, requests.last().maxUpdateAgeMillis)
        assertTrue(requests.last().durationMillis <= LocationService.ACQUISITION_TIMEOUT_MILLIS)
        callbacks.last().onLocationResult(LocationResult.create(listOf(fix(0.6f).apply { provider = "fused" })))
        await { triggers.size == 2 }
        assertTrue(repo.stationaryMotion.state.value.stationary)
        assertEquals(2, requests.size)
    }
}
