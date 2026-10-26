package com.aitech.weartak_civ.service

import android.app.Application
import android.os.Handler
import android.os.SystemClock
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.presentation.MainActivity
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

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

    @Test fun startAndStopCompleteSynchronouslySoSourceHandoverCannotOverlap() {
        call("startHrTracker")
        call("stopHrTracker")
        val expected = if (BuildConfig.SAMSUNG_HRV_ENABLED) 1 else 0
        verify(exactly = expected) { tracker.setEventListener(any()) }
        verify(exactly = expected) { tracker.unsetEventListener() }
        verify(exactly = 0) { handler.post(any<Runnable>()) }
        assertNull(service.latestHrvPayload(1_000L))
    }

    private fun point(timestamp: Long, bpm: Int, intervals: List<Int>, statuses: List<Int>) =
        mockk<DataPoint> {
            every { this@mockk.timestamp } returns timestamp
            every { getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) } returns 1
            every { getValue(ValueKey.HeartRateSet.HEART_RATE) } returns bpm
            every { getValue(ValueKey.HeartRateSet.IBI_LIST) } returns intervals
            every { getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) } returns statuses
        }

    @Test fun optedInSdkPreservesAllBatchHrPointsAndIbiIntervalsWithMeasurementTime() {
        assumeTrue(BuildConfig.SAMSUNG_HRV_ENABLED)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        val source = field("hrSource").get(service) as SamsungHeartRateSource
        val received = mutableListOf<HeartRateSample>()
        assertTrue(source.start(received::add) { fail("Unexpected SDK failure") })
        val listener = field("hrEventListener").get(service) as HealthTracker.TrackerEventListener
        val now = System.currentTimeMillis()
        val first = point(now - 30_000L, 75, List(8) { 800 + it * 10 }, List(8) { 0 })
        val second = point(now - 20_000L, 76, List(8) { 850 - it * 10 }, List(8) { 0 })
        listener.onDataReceived(listOf(first, second))
        assertEquals(listOf(HeartRateSample(75, now - 30_000L), HeartRateSample(76, now - 20_000L)), received)
        val intervals = field("nnBuffer").get(service) as ArrayDeque<*>
        assertEquals(16, intervals.size)
        assertNotNull(service.latestHrvPayload(SystemClock.elapsedRealtime()))
        assertEquals(now - 20_000L, service.latestHrvPayload(SystemClock.elapsedRealtime())!!.ts)
        val lastElapsed = field("lastRmssdElapsedMs").getLong(service)
        assertTrue(SystemClock.elapsedRealtime() - lastElapsed >= 20_000L)
        listener.onDataReceived(listOf(point(now - 10_000L, 77, listOf(800), listOf(1))))
        assertEquals(lastElapsed, field("lastRmssdElapsedMs").getLong(service))
        assertEquals(3, received.size)
        assertTrue(source.stop())
        listener.onDataReceived(listOf(second))
        assertEquals(3, received.size)
        assertNull(service.latestHrvPayload(SystemClock.elapsedRealtime()))
    }

    @Test fun sdkFailureDetachesTrackerAndNotifiesOwnerOnceAndRejectsOldGeneration() {
        assumeTrue(BuildConfig.SAMSUNG_HRV_ENABLED)
        val source = field("hrSource").get(service) as SamsungHeartRateSource
        var failures = 0
        val samples = mutableListOf<HeartRateSample>()
        assertTrue(source.start(samples::add) { failures++ })
        val oldListener = field("hrEventListener").get(service) as HealthTracker.TrackerEventListener
        val error = HealthTracker.TrackerError.values().first()
        oldListener.onError(error)
        oldListener.onError(error)
        assertEquals(1, failures)
        verify(exactly = 1) { tracker.unsetEventListener() }
        assertTrue(source.start(samples::add) { failures++ })
        oldListener.onDataReceived(listOf(point(System.currentTimeMillis(), 180, emptyList(), emptyList())))
        assertTrue(samples.isEmpty())
        source.stop()
    }

    @Test fun failedSdkDetachIsReportedInsteadOfPermittingAnOverlappingSource() {
        assumeTrue(BuildConfig.SAMSUNG_HRV_ENABLED)
        val source = field("hrSource").get(service) as SamsungHeartRateSource
        assertTrue(source.start({}) {})
        every { tracker.unsetEventListener() } throws IllegalStateException("SDK busy")
        assertFalse(source.stop())
        assertFalse(source.start({}) {})
        every { tracker.unsetEventListener() } returns Unit
        assertTrue(source.stop())
    }

    @Test fun retainedSourceCannotAttachAfterItsServiceIsDestroyed() {
        val source = field("hrSource").get(service) as SamsungHeartRateSource
        set("destroyed", true)
        assertFalse(source.start({}) {})
        verify(exactly = 0) { tracker.setEventListener(any()) }
    }

    private fun field(name: String) = SkinTempService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any) = field(name).set(service, value)
    private fun call(name: String) =
        SkinTempService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    private fun call(name: String, type: Class<*>, value: Any) =
        SkinTempService::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(service, value)
}
