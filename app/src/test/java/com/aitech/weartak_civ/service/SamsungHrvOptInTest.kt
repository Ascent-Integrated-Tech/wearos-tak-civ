package com.aitech.weartak_civ.service

import android.app.Application
import android.os.Handler
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.presentation.MainActivity
import com.samsung.android.service.health.tracking.HealthTracker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SamsungHrvOptInTest {
    private lateinit var service: SkinTempService
    private val tracker = mockk<HealthTracker>(relaxed = true)
    private val handler = mockk<Handler>(relaxed = true)

    @Before fun prepare() {
        MainActivity.mainActivity = mockk {
            every { applicationContext } returns RuntimeEnvironment.getApplication()
        }
        service = Robolectric.buildService(SkinTempService::class.java).get()
        set("hrTracker", tracker)
        set("backgroundHandler", handler)
        set("deviceWorn", true)
        every { handler.post(any<Runnable>()) } answers { firstArg<Runnable>().run(); true }
    }

    @Test fun releaseDefaultIsOff() {
        assertFalse(BuildConfig.SAMSUNG_HRV_ENABLED)
    }

    @Test fun configuredFlagControlsListenerAndScreenOffFlushWithoutTouchingOtherTrackers() {
        call("startHrTracker")
        call("enableScreenOffFlushLoop")
        call("flushHr", String::class.java, "test")
        val expected = if (BuildConfig.SAMSUNG_HRV_ENABLED) 1 else 0
        verify(exactly = expected) { tracker.setEventListener(any()) }
        verify(exactly = expected) { tracker.flush() }
        verify(exactly = expected) { handler.postDelayed(any<Runnable>(), 5_000L) }
        call("stopHrTracker")
        verify(exactly = 0) { handler.removeCallbacksAndMessages(null) }
    }

    @Test fun cachedRmssdRequiresOptInRunningWornAndFreshAndStopAlwaysClearsIt() {
        set("lastNonZeroRmssdMs", 42.0)
        set("lastRmssdElapsedMs", 1_000L)
        set("lastRmssdWallMs", 20_000L)
        set("isHrRunning", true)
        val current = service.latestHrvPayload(121_000L)
        if (BuildConfig.SAMSUNG_HRV_ENABLED) {
            assertEquals(42.0, current!!.rmssd_ms!!, 0.0)
            assertEquals(20_000L, current.ts)
        } else assertNull(current)
        assertNull(service.latestHrvPayload(121_001L))
        set("deviceWorn", false)
        assertNull(service.latestHrvPayload(2_000L))
        call("stopHrTracker")
        assertEquals(0.0, field("lastNonZeroRmssdMs").getDouble(service), 0.0)
        assertEquals(0L, field("lastRmssdElapsedMs").getLong(service))
        assertNull(service.latestHrvPayload(2_000L))
    }

    @Test fun listenerCannotRefreshCachedRmssdWithoutNewValidIntervals() {
        set("isHrRunning", true)
        set("lastNonZeroRmssdMs", 42.0)
        set("lastRmssdElapsedMs", 1_000L)
        val listener = field("hrEventListener").get(service) as HealthTracker.TrackerEventListener
        listener.onDataReceived(emptyList())
        assertEquals(1_000L, field("lastRmssdElapsedMs").getLong(service))
        set("lastHrvEmittedElapsedMs", 1_000L)
        assertNull(service.latestHrvPayload(2_000L))
    }

    @Test fun delayedStartCannotAttachAfterStop() {
        val pending = mutableListOf<Runnable>()
        every { handler.post(any<Runnable>()) } answers { pending.add(firstArg()); true }
        call("startHrTracker")
        call("stopHrTracker")
        pending.toList().forEach(Runnable::run)
        verify(exactly = 0) { tracker.setEventListener(any()) }
        assertNull(service.latestHrvPayload(1_000L))
    }

    private fun field(name: String) = SkinTempService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any) = field(name).set(service, value)
    private fun call(name: String) =
        SkinTempService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    private fun call(name: String, type: Class<*>, value: Any) =
        SkinTempService::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(service, value)
}
