package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.IsDeviceWornEvent
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.greenrobot.eventbus.EventBus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HeartrateLifecycleTest {
    private lateinit var repo: DataRepository
    private lateinit var sensors: ShadowSensorManager
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(3_000) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(5)
            }
        }
    }

    @Before fun setup() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        val activity = mockk<MainActivity>()
        every { activity.applicationContext } returns app
        MainActivity.mainActivity = activity
        repo = DataRepository(app)
        MainActivity.dataRepository = repo
        repo.storeEnablePhysiologicalServices(true)
        repo.storeEnableHeartRateService(true)
        repo.storeLatestIsDeviceWorn(true, "test-sensor")
        shadowOf(app).grantPermissions(Manifest.permission.BODY_SENSORS)
        sensors = shadowOf(app.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_HEART_RATE))
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT))
        Unit
    }

    private fun service(): HeartrateService =
        Robolectric.buildService(HeartrateService::class.java).create().get()

    private fun event(type: Int, value: Float): SensorEvent {
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        return ShadowSensorManager.createSensorEvent(1, type).apply {
            values[0] = value
            timestamp = SystemClock.elapsedRealtimeNanos()
        }
    }

    private fun send(listener: SensorEventListener, event: SensorEvent) {
        listener.onAccuracyChanged(event.sensor, 3)
        listener.onSensorChanged(event)
    }

    @Test fun repositoryWornStateIsAuthoritativeAndRepeatedWakeKeepsTheSameListener() {
        val service = service()
        try {
            await { sensors.listeners.size == 1 }
            val listener = sensors.listeners.single()
            repeat(5) { service.onStartCommand(null, 0, it) }
            EventBus.getDefault().post(IsDeviceWornEvent(false))
            service.onTaskRemoved(null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(listener), sensors.listeners)
            assertTrue(repo.latestIsDeviceWorn.value)
            assertTrue(HeartrateService.isRunning.get())
            runBlocking { repo.storeLatestIsDeviceWorn(false, "actual-sensor") }
            await { sensors.listeners.isEmpty() }
            runBlocking { repo.storeLatestIsDeviceWorn(true, "actual-sensor") }
            await { sensors.listeners.size == 1 }
        } finally { service.onDestroy() }
    }

    @Test fun refusedRegistrationRecoversButMonitoringOffAndRevokedPermissionNeverRestart() {
        sensors.forceListenersToFail = true
        val service = service()
        try {
            await { service.permission }
            assertTrue(sensors.listeners.isEmpty())
            sensors.forceListenersToFail = false
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            await { sensors.listeners.size == 1 }
            runBlocking { repo.storeEnablePhysiologicalServices(false) }
            await { sensors.listeners.isEmpty() }
            repeat(3) { service.onStartCommand(null, 0, it) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            assertTrue(sensors.listeners.isEmpty())
            shadowOf(app).denyPermissions(Manifest.permission.BODY_SENSORS)
            runBlocking { repo.storeEnablePhysiologicalServices(true) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertTrue(sensors.listeners.isEmpty())
            shadowOf(app).grantPermissions(Manifest.permission.BODY_SENSORS)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            await { sensors.listeners.size == 1 }
            runBlocking { repo.storeEnableHeartRateService(false) }
            await { sensors.listeners.isEmpty() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertTrue(sensors.listeners.isEmpty())
        } finally { service.onDestroy() }
    }

    @Test fun destroyedInstanceRejectsLateSamplesAndUnknownIsNotAConfirmedOffWristObservation() {
        val service = service()
        await { sensors.listeners.size == 1 }
        val listener = sensors.listeners.single()
        service.onDestroy()
        val before = repo.latestHeartRate.value
        send(listener, event(Sensor.TYPE_HEART_RATE, 112f))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, repo.latestHeartRate.value)
        assertTrue(sensors.listeners.isEmpty())
        assertFalse(HeartrateService.isRunning.get())
        runBlocking { repo.invalidateDeviceWornObservation() }
        assertNull(repo.latestDeviceWornObservation.value)
        assertFalse(repo.latestIsDeviceWorn.value)
        runBlocking { repo.storeLatestIsDeviceWorn(false, "actual-sensor") }
        assertEquals(false, repo.latestDeviceWornObservation.value)
    }

    @Test fun offBodyRecreationWaitsForRealObservationAndRejectsInvalidOrDestroyedCallbacks() {
        fun create() = Robolectric.buildService(OffBodyDetectService::class.java).create().get()
        fun send(service: OffBodyDetectService, value: Float) =
            send(service.sensorEventListener, event(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, value))
        val first = create()
        assertNull(repo.latestDeviceWornObservation.value)
        send(first, Float.NaN)
        send(first, 2f)
        assertNull(repo.latestDeviceWornObservation.value)
        send(first, 1f)
        assertEquals(true, repo.latestDeviceWornObservation.value)
        first.onTaskRemoved(null)
        assertEquals(true, repo.latestDeviceWornObservation.value)
        first.onDestroy()
        assertNull(repo.latestDeviceWornObservation.value)
        val second = create()
        try {
            send(first, 0f)
            assertNull(repo.latestDeviceWornObservation.value)
            send(second, 1f)
            assertTrue(repo.latestIsDeviceWorn.value)
            send(first, 0f)
            assertTrue(repo.latestIsDeviceWorn.value)
            send(second, 0f)
            assertEquals(false, repo.latestDeviceWornObservation.value)
        } finally { second.onDestroy() }
    }

    @Test fun hrRecreationRejectsOldGenerationSamplesButAcceptsRealCurrentSamples() {
        val first = service()
        await { sensors.listeners.size == 1 }
        val old = sensors.listeners.single()
        first.onDestroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val second = service()
        try {
            await { sensors.listeners.size == 1 }
            val current = sensors.listeners.single()
            val sample = event(Sensor.TYPE_HEART_RATE, 112f)
            val before = repo.latestHeartRate.value
            send(old, sample)
            assertEquals(before, repo.latestHeartRate.value)
            send(current, sample)
            assertEquals(112, repo.latestHeartRate.value)
            runBlocking { repo.storeLatestIsDeviceWorn(false, "actual-sensor") }
            await { sensors.listeners.isEmpty() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            runBlocking { repo.storeLatestIsDeviceWorn(true, "actual-sensor") }
            await { sensors.listeners.size == 1 }
            val invalidated = repo.latestHeartRate.value
            send(current, sample)
            assertEquals(invalidated, repo.latestHeartRate.value)
        } finally { second.onDestroy() }
    }

    @Test fun restartWaitsForActualWornStateButAcceptsFrameworkInitialOnChangeObservation() {
        val retained = event(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, 1f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val offBody = Robolectric.buildService(OffBodyDetectService::class.java).create().get()
        val heartRate = service()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(repo.latestDeviceWornObservation.value)
            assertFalse(repo.latestIsDeviceWorn.value)
            assertEquals(listOf(offBody.sensorEventListener), sensors.listeners)
            send(offBody.sensorEventListener, retained)
            assertEquals(true, repo.latestDeviceWornObservation.value)
            await { sensors.listeners.size == 2 }
            send(offBody.sensorEventListener, event(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, 0f))
            await { sensors.listeners.size == 1 }
            assertFalse(repo.latestIsDeviceWorn.value)
            send(offBody.sensorEventListener, retained)
            assertFalse("An older observation must not overwrite a later off-wrist state", repo.latestIsDeviceWorn.value)
        } finally {
            heartRate.onDestroy()
            offBody.onDestroy()
        }
    }

    @Test fun offBodyRejectsImpossibleTimestampsAndRetainedOffWristDoesNotStartHeartRate() {
        val retained = event(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val offBody = Robolectric.buildService(OffBodyDetectService::class.java).create().get()
        val heartRate = service()
        try {
            send(offBody.sensorEventListener, event(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, 1f)
                .apply { timestamp = -1L })
            send(offBody.sensorEventListener, event(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, 1f)
                .apply { timestamp += 1_000_000_000L })
            assertNull(repo.latestDeviceWornObservation.value)
            send(offBody.sensorEventListener, retained)
            assertEquals(false, repo.latestDeviceWornObservation.value)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertEquals(listOf(offBody.sensorEventListener), sensors.listeners)
        } finally {
            heartRate.onDestroy()
            offBody.onDestroy()
        }
    }
}
