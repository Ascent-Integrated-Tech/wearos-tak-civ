package com.aitech.weartak_civ.service

import android.app.Application
import android.content.Context
import android.location.Location
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BloodhoundLocalMarkerCollectorTest {
    @Test fun localMoveUpdatesServiceVectorAndMapTargetWithoutNewActivation() = runBlocking {
        val bloodhounds = BloodhoundRepository(mockk<Context>(relaxed = true))
        bloodhounds.addMarkerTarget("pin", "My pin", "a-f-G", 38.001, -77.0, 100,
            tracksLocalMarker = true)
        bloodhounds.activate("pin")
        val marker = DataRepository.DroppedMarker(DataRepository.MarkerCotType.EDIT, "pin",
            DataRepository.MarkerType.FRIENDLY, "My pin", "", 100,
            DataRepository.LocationSnapshot(200, 38.00001, -77.0, 0.0, null, null, null, null),
            true, false)
        val markers = MutableStateFlow<List<DataRepository.DroppedMarker>>(emptyList())
        val repository = mockk<DataRepository>()
        every { repository.droppedMarkerList } returns markers
        every { repository.latestLocation } returns MutableStateFlow(Location("test").apply {
            latitude = 38.0
            longitude = -77.0
        })
        every { repository.azimuthTrueDegSmoothed } returns MutableStateFlow(0.0)
        val service = BloodhoundService()
        setLazy(service, "repo", repository)
        setLazy(service, "bhRepo", bloodhounds)
        try {
            BloodhoundService::class.java.getDeclaredMethod("collectLocalMarkerTargets").apply {
                isAccessible = true
                invoke(service)
            }
            withTimeout(5_000) {
                while (markers.subscriptionCount.value == 0) delay(10)
            }
            markers.value = listOf(marker)
            withTimeout(5_000) {
                while (bloodhounds.activeTrackingItem()?.vector?.distance?.let { it < 0.002 } != true)
                    delay(10)
            }
            val active = bloodhounds.activeTrackingItem()!!
            assertEquals(38.00001, active.target.point.lat, 0.0)
            assertTrue(active.state.active)
            assertTrue(active.state.acknowledged)
            assertEquals("pin", bloodhounds.activeUid.value)
            assertEquals(1, bloodhounds.bloodhoundMap.value.size)
        } finally {
            BloodhoundService::class.java.getDeclaredField("scope").apply {
                isAccessible = true
                (get(service) as CoroutineScope).cancel()
            }
        }
    }

    private fun setLazy(service: BloodhoundService, name: String, value: Any) {
        BloodhoundService::class.java.getDeclaredField("$name\$delegate").apply {
            isAccessible = true
            set(service, lazy { value })
        }
    }
}
