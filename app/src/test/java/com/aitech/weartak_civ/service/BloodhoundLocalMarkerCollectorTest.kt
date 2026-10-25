package com.aitech.weartak_civ.service

import android.app.Application
import android.content.Context
import android.location.Location
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.provider.LocationVectorCalculator
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    @Test fun reentryUsesCurrentSourceLocationAndHeadingWhileClosedActiveGuidanceContinues() = runBlocking {
        val bloodhounds = BloodhoundRepository(mockk<Context>(relaxed = true))
        bloodhounds.addMarkerTarget("active", "Active", "a-f-G", 38.001, -77.0, 100)
        bloodhounds.addMarkerTarget("hidden", "Hidden", "a-f-G", 38.002, -77.0, 100)
        bloodhounds.activate("active")
        val location = MutableStateFlow(Location("test").apply {
            latitude = 38.0
            longitude = -77.0
        })
        val heading = MutableStateFlow<Double?>(0.0)
        val repository = mockk<DataRepository>()
        every { repository.latestLocation } returns location
        every { repository.azimuthTrueDegSmoothed } returns heading
        val service = BloodhoundService()
        setLazy(service, "repo", repository)
        setLazy(service, "bhRepo", bloodhounds)
        mockkObject(Utils)
        every { Utils.cancelWatchVibration() } returns Unit
        suspend fun awaitDistance(uid: String, expected: Double) {
            withTimeout(5_000) {
                while (bloodhounds.bloodhoundMap.value[uid]?.vector?.distance != expected) delay(10)
            }
        }
        try {
            listOf("collectPresentationDemand", "collectLocation", "collectActiveSelection",
                "collectAzimuthFlow").forEach { method ->
                BloodhoundService::class.java.getDeclaredMethod(method).apply {
                    isAccessible = true
                    invoke(service)
                }
            }
            awaitDistance("active", LocationVectorCalculator.calculate(38.0, -77.0, 38.001, -77.0).distance!!)
            assertNull(bloodhounds.bloodhoundMap.value["hidden"]?.vector?.distance)
            bloodhounds.addMarkerTarget("hidden", "Fresh hidden source", "a-f-G", 38.01, -77.0, 200)
            location.value = Location("test").apply {
                latitude = 38.005
                longitude = -77.0
            }
            heading.value = 90.0
            val activeDistance = LocationVectorCalculator.calculate(38.005, -77.0, 38.001, -77.0).distance!!
            awaitDistance("active", activeDistance)
            assertNull(bloodhounds.bloodhoundMap.value["hidden"]?.vector?.distance)
            withTimeout(5_000) {
                bloodhounds.activeBhItemMap.first { it?.uid == "active" && it.vector.distance == activeDistance }
            }
            val picker = Any()
            bloodhounds.requestPresentation(picker)
            val hiddenVector = LocationVectorCalculator.calculate(38.005, -77.0, 38.01, -77.0)
            awaitDistance("hidden", hiddenVector.distance!!)
            withTimeout(5_000) {
                bloodhounds.bloodhoundItemUi("hidden").first {
                    it?.target == "Fresh hidden source" &&
                        it.orientation == LocationVectorCalculator.needleAngle(hiddenVector.bearing, 90.0)?.toFloat()
                }
            }
            bloodhounds.releasePresentation(picker)
            bloodhounds.activate("hidden")
            location.value = Location("test").apply {
                latitude = 38.006
                longitude = -77.0
            }
            awaitDistance("hidden", LocationVectorCalculator.calculate(38.006, -77.0, 38.01, -77.0).distance!!)
            assertEquals(setOf("hidden"), bloodhounds.guidanceTargets().keys)
            assertEquals("hidden", bloodhounds.activeTrackingItem()?.uid)
        } finally {
            BloodhoundService::class.java.getDeclaredField("scope").apply {
                isAccessible = true
                (get(service) as CoroutineScope).cancel()
            }
            unmockkObject(Utils)
        }
    }

    @Test fun vectorWorkIsZeroWhenIdleOneWhenActiveAndAllOnlyForVisiblePicker() = runBlocking {
        val bloodhounds = BloodhoundRepository(mockk<Context>(relaxed = true))
        repeat(80) { index ->
            bloodhounds.addMarkerTarget("pin-$index", "Pin $index", "a-f-G",
                38.001 + index * 0.001, -77.0, 100)
        }
        val repository = mockk<DataRepository>()
        every { repository.latestLocation } returns MutableStateFlow(Location("test").apply {
            latitude = 38.0
            longitude = -77.0
        })
        every { repository.azimuthTrueDegSmoothed } returns MutableStateFlow(0.0)
        val service = BloodhoundService()
        setLazy(service, "repo", repository)
        setLazy(service, "bhRepo", bloodhounds)
        val scope = BloodhoundService::class.java.getDeclaredField("scope").run {
            isAccessible = true
            get(service) as CoroutineScope
        }
        suspend fun refresh() {
            BloodhoundService::class.java.getDeclaredMethod("updateVectors").apply {
                isAccessible = true
                invoke(service)
            }
            scope.coroutineContext[Job]!!.children.toList().joinAll()
        }
        fun calculated() = bloodhounds.bloodhoundMap.value.values.count { it.vector.distance != null }
        mockkObject(Utils)
        every { Utils.cancelWatchVibration() } returns Unit
        try {
            refresh()
            assertEquals(0, calculated())
            bloodhounds.activate("pin-0")
            refresh()
            assertEquals(1, calculated())
            assertNotNull(bloodhounds.activeTrackingItem()?.vector?.distance)
            val detail = Any()
            bloodhounds.requestPresentation(detail, "pin-1")
            refresh()
            assertEquals(2, calculated())
            bloodhounds.releasePresentation(detail)
            val picker = Any()
            bloodhounds.requestPresentation(picker)
            refresh()
            assertEquals(80, calculated())
            bloodhounds.releasePresentation(picker)
            assertEquals(setOf("pin-0"), bloodhounds.guidanceTargets().keys)
        } finally {
            scope.cancel()
            unmockkObject(Utils)
        }
    }

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
