package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Application
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.InteractionLocationRcvd
import com.aitech.weartak_civ.events.InteractionPliRefresh
import com.aitech.weartak_civ.events.PliRefreshReason
import com.aitech.weartak_civ.presentation.MainActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.Tasks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
    class Observer {
        val events = mutableListOf<InteractionLocationRcvd>()
        @Subscribe fun receive(event: InteractionLocationRcvd) { events.add(event) }
    }

    private fun service(client: FusedLocationProviderClient): LocationService {
        MainActivity.device_uid = "test-watch"
        MainActivity.dataRepository = DataRepository(RuntimeEnvironment.getApplication())
        val service = Robolectric.buildService(LocationService::class.java).get()
        LocationService::class.java.getDeclaredField("fusedLocationProviderClient").apply {
            isAccessible = true
            set(service, client)
        }
        LocationService.isRunning.set(true)
        return service
    }

    @Test fun deniedPermissionDoesNotStartAcquisition() {
        val client = mockk<FusedLocationProviderClient>(relaxed = true)
        val service = service(client)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        )
        service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
        verify(exactly = 0) { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) }
        service.onDestroy()
    }

    @Test fun triggersCoalesceFreshFixDispatchesOnceAndDestroyRemovesAcquisition() {
        val client = mockk<FusedLocationProviderClient>(relaxed = true)
        val callback = slot<LocationCallback>()
        val request = slot<LocationRequest>()
        every { client.requestLocationUpdates(capture(request), capture(callback), any<Looper>()) } returns Tasks.forResult(null)
        val service = service(client)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val observer = Observer()
        EventBus.getDefault().register(observer)
        try {
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.CHAT_SENT))
            verify(exactly = 1) { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) }
            assertEquals(5_000L, request.captured.durationMillis)
            assertEquals(0L, request.captured.maxUpdateAgeMillis)
            val invalid = Location("gps")
            callback.captured.onLocationResult(LocationResult.create(listOf(invalid)))
            assertTrue(observer.events.isEmpty())
            val fresh = Location("gps").apply {
                latitude = 41.0
                longitude = -87.0
                altitude = 100.0
                accuracy = 5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            callback.captured.onLocationResult(LocationResult.create(listOf(fresh)))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, observer.events.size)
            assertEquals(setOf(PliRefreshReason.MAP_OPENED, PliRefreshReason.CHAT_SENT), observer.events.single().reasons)
            assertEquals(fresh.time, observer.events.single().location.time)
            callback.captured.onLocationResult(LocationResult.create(listOf(fresh)))
            assertEquals(1, observer.events.size)
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.ROLE_CHANGED))
            val pendingCallback = callback.captured
            service.onDestroy()
            verify { client.removeLocationUpdates(pendingCallback) }
            pendingCallback.onLocationResult(LocationResult.create(listOf(fresh)))
            assertEquals(1, observer.events.size)
        } finally {
            EventBus.getDefault().unregister(observer)
            LocationService.isRunning.set(false)
        }
    }

    @Test fun timeoutAndProviderFailureCleanUpAndAllowLaterInteractionWithoutSendingPli() {
        val client = mockk<FusedLocationProviderClient>(relaxed = true)
        val callback = slot<LocationCallback>()
        every { client.requestLocationUpdates(any<LocationRequest>(), capture(callback), any<Looper>()) } returns Tasks.forResult(null)
        val service = service(client)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val observer = Observer()
        EventBus.getDefault().register(observer)
        try {
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.MAP_OPENED))
            val timedOut = callback.captured
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            verify { client.removeLocationUpdates(timedOut) }
            assertTrue(observer.events.isEmpty())
            every { client.requestLocationUpdates(any<LocationRequest>(), capture(callback), any<Looper>()) } returns
                Tasks.forException(SecurityException("permission revoked"))
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.CHAT_SENT))
            val failed = callback.captured
            shadowOf(Looper.getMainLooper()).idle()
            verify { client.removeLocationUpdates(failed) }
            every { client.requestLocationUpdates(any<LocationRequest>(), capture(callback), any<Looper>()) } returns Tasks.forResult(null)
            service.onInteractionPliRefresh(InteractionPliRefresh(PliRefreshReason.ROLE_CHANGED))
            verify(exactly = 3) { client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>()) }
            assertTrue(observer.events.isEmpty())
        } finally {
            service.onDestroy()
            EventBus.getDefault().unregister(observer)
        }
    }
}
