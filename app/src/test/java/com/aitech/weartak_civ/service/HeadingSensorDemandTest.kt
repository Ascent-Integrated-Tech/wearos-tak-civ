package com.aitech.weartak_civ.service

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.PowerManager
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.HeadingComplicationDemand
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensorManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HeadingSensorDemandTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun complicationSlotsSurviveProcessRestartAndOnlyTheirOwnerCanRemoveThem() {
        val preferences = app.getSharedPreferences("heading-demand-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val demand = HeadingComplicationDemand(preferences)
        demand.setActive(7, true)
        demand.setActive(8, true)
        demand.setActive(7, true)
        assertEquals(setOf("7", "8"), demand.activeIds.value)
        val restored = HeadingComplicationDemand(preferences)
        assertEquals(setOf("7", "8"), restored.activeIds.value)
        restored.setActive(7, false)
        restored.setActive(99, false)
        assertEquals(setOf("8"), restored.activeIds.value)
        restored.setActive(8, false)
        assertTrue(HeadingComplicationDemand(preferences).activeIds.value.isEmpty())
    }

    @Test fun displayOffAndAmbientIdleButInteractiveOverlapAndBackgroundGuidanceKeepTheirSensor() {
        val fixture = Fixture()
        try {
            fixture.start()
            fixture.idle()
            fixture.registrations(0)
            fixture.subscribers.value = 1
            fixture.idle()
            fixture.registrations(0)
            fixture.screen(true)
            fixture.registrations(1)
            fixture.subscribers.value = 2
            fixture.idle()
            fixture.registrations(1)
            fixture.subscribers.value = 1
            fixture.idle()
            fixture.registrations(1)
            fixture.screen(false)
            fixture.unregistrations(1)
            // Ambient/AOD remains noninteractive even though UI collectors may still exist.
            fixture.screen(false)
            fixture.registrations(1)
            fixture.active.value = "guidance"
            fixture.idle()
            fixture.registrations(2)
            fixture.active.value = "other-guidance"
            fixture.idle()
            fixture.registrations(2)
            fixture.subscribers.value = 0
            fixture.idle()
            fixture.unregistrations(1)
            fixture.active.value = null
            fixture.idle()
            fixture.unregistrations(2)
            fixture.screen(true)
            fixture.registrations(2)
            fixture.slots.setActive(1, true)
            fixture.idle()
            fixture.registrations(3)
            assertEquals(1, fixture.heading.subscriptionCount.value)
            fixture.slots.setActive(2, true)
            fixture.slots.setActive(1, false)
            fixture.idle()
            fixture.registrations(3)
            fixture.screen(false)
            assertEquals(0, fixture.heading.subscriptionCount.value)
            fixture.unregistrations(3)
            fixture.screen(true)
            fixture.registrations(4)
            fixture.slots.setActive(2, false)
            fixture.idle()
            fixture.unregistrations(4)
            assertEquals(0, fixture.heading.subscriptionCount.value)
        } finally {
            fixture.close()
        }
    }

    @Test fun registrationFailureDoesNotPretendSuccessAndReentryResetsSmoothing() {
        val fixture = Fixture()
        try {
            every { fixture.manager.registerListener(fixture.service, fixture.sensor, any<Int>()) } returns false
            fixture.start()
            fixture.subscribers.value = 1
            fixture.screen(true)
            assertFalse(field(fixture.service, "rotationRegistered") as Boolean)
            setField(fixture.service, "lastAzimuthTrueDeg", 42.0)
            fixture.screen(false)
            every { fixture.manager.registerListener(fixture.service, fixture.sensor, any<Int>()) } returns true
            fixture.screen(true)
            assertTrue(field(fixture.service, "rotationRegistered") as Boolean)
            assertNull(field(fixture.service, "lastAzimuthTrueDeg"))
            assertEquals(0L, field(fixture.service, "lastPublishAtMs"))
        } finally {
            fixture.close()
        }
    }

    @Test fun screenReceiverUsesCurrentPowerStateAndLateSensorCallbacksCannotPublish() {
        val fixture = Fixture()
        try {
            fixture.start()
            fixture.subscribers.value = 1
            fixture.screen(true)
            val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val receiver = field(fixture.service, "screenReceiver") as BroadcastReceiver
            shadowOf(power).setIsInteractive(false)
            receiver.onReceive(app, Intent(Intent.ACTION_SCREEN_OFF))
            fixture.idle()
            fixture.unregistrations(1)
            val stale = ShadowSensorManager.createSensorEvent(3, Sensor.TYPE_ROTATION_VECTOR).apply {
                timestamp = 0
            }
            fixture.service.onSensorChanged(stale)
            shadowOf(power).setIsInteractive(true)
            receiver.onReceive(app, Intent(Intent.ACTION_SCREEN_ON))
            fixture.idle()
            fixture.registrations(2)
            fixture.service.onSensorChanged(stale)
            verify(exactly = 0) { fixture.repository.updateMagAzimuthTrue(any()) }
        } finally {
            fixture.close()
        }
    }

    private inner class Fixture {
        val service = Robolectric.buildService(MovementDetectionService::class.java).get()
        val manager = mockk<SensorManager>(relaxed = true)
        val sensor = mockk<Sensor>()
        val subscribers = MutableStateFlow(0)
        val active = MutableStateFlow<String?>(null)
        val heading = MutableStateFlow<Double?>(35.0)
        val slots = HeadingComplicationDemand(
            app.getSharedPreferences("heading-fixture", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        )
        val repository = mockk<DataRepository>()
        init {
            every { repository.headingComplications } returns slots
            every { repository.magneticHeadingSubscriptions } returns subscribers
            every { repository.azimuthTrueDegSmoothed } returns heading
            every { manager.registerListener(service, sensor, any<Int>()) } returns true
            setField(service, "repo\$delegate", lazy { repository })
            setField(service, "sensorManager", manager)
            setField(service, "rotationVector", sensor)
        }
        fun start() {
            MovementDetectionService::class.java.getDeclaredMethod("collectHeadingDemand", StateFlow::class.java)
                .apply { isAccessible = true }.invoke(service, active)
        }
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        @Suppress("UNCHECKED_CAST")
        fun screen(interactive: Boolean) {
            (field(service, "interactive") as MutableStateFlow<Boolean>).value = interactive
            idle()
        }
        fun registrations(count: Int) = verify(exactly = count) {
            manager.registerListener(service, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        fun unregistrations(count: Int) = verify(exactly = count) { manager.unregisterListener(service) }
        fun close() {
            (field(service, "scope") as CoroutineScope).cancel()
            idle()
        }
    }

    private fun field(service: MovementDetectionService, name: String): Any? =
        MovementDetectionService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setField(service: MovementDetectionService, name: String, value: Any?) {
        MovementDetectionService::class.java.getDeclaredField(name).apply {
            isAccessible = true; set(service, value)
        }
    }
}
