package com.aitech.weartak_civ.service

import android.app.Application
import android.location.Location
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.BloodhoundRepository.BloodhoundItem
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BloodhoundHeadingCollectorTest {
    @Test
    fun closedPickerDoesNoNeedleWorkAndReentryOrActiveSelectionUsesCurrentHeading() {
        val heading = MutableStateFlow<Double?>(45.0)
        val demand = MutableStateFlow<Set<String>?>(emptySet())
        val active = MutableStateFlow<String?>(null)
        val repository = mockk<DataRepository>()
        val bloodhounds = mockk<BloodhoundRepository>(relaxed = true)
        val targets = listOf(item("first", 90.0), item("second", 270.0)).associateBy { it.uid }
        every { repository.azimuthTrueDegSmoothed } returns heading
        every { bloodhounds.presentationTargets } returns demand
        every { bloodhounds.activeUid } returns active
        every { bloodhounds.guidanceTargets() } answers {
            if (demand.value == null) targets
            else targets.filterKeys { it in demand.value.orEmpty() || it == active.value }
        }
        val service = BloodhoundService()
        setField(service, "repo\$delegate", lazy { repository })
        setField(service, "bhRepo\$delegate", lazy { bloodhounds })
        try {
            BloodhoundService::class.java.getDeclaredMethod("collectAzimuthFlow").apply {
                isAccessible = true
                invoke(service)
            }
            verify(timeout = 5_000, atLeast = 1) { bloodhounds.guidanceTargets() }
            verify(exactly = 0) { bloodhounds.updateBloodhoundCompassNeedles(any()) }
            demand.value = null
            verify(timeout = 5_000, exactly = 1) {
                bloodhounds.updateBloodhoundCompassNeedles(mapOf("first" to 45.0, "second" to 225.0))
            }
            demand.value = emptySet()
            heading.value = 90.0
            active.value = "second"
            verify(timeout = 5_000, exactly = 1) {
                bloodhounds.updateBloodhoundCompassNeedles(mapOf("second" to 180.0))
            }
            active.value = "first"
            verify(timeout = 5_000, exactly = 1) {
                bloodhounds.updateBloodhoundCompassNeedles(mapOf("first" to 0.0))
            }
            active.value = null
            demand.value = null
            verify(timeout = 5_000, exactly = 1) {
                bloodhounds.updateBloodhoundCompassNeedles(mapOf("first" to 0.0, "second" to 180.0))
            }
        } finally {
            BloodhoundService::class.java.getDeclaredField("scope").apply {
                isAccessible = true
                (get(service) as CoroutineScope).cancel()
            }
        }
    }

    @Test
    fun visiblePickerPublishesOneCompleteEligibleBatchWithoutIdleVibrationCleanup() {
        val heading = MutableStateFlow<Double?>(null)
        val repository = mockk<DataRepository>()
        val bloodhounds = mockk<BloodhoundRepository>(relaxed = true)
        val first = item("first", 90.0)
        val second = item("second", 270.0)
        val noBearing = item("no-bearing", null)
        val noLocation = item("no-location", 120.0, hasLocation = false)
        every { repository.azimuthTrueDegSmoothed } returns heading
        val targets = listOf(first, second, noBearing, noLocation).associateBy { it.uid }
        every { bloodhounds.bloodhoundMap } returns MutableStateFlow(targets)
        every { bloodhounds.guidanceTargets() } returns targets
        every { bloodhounds.presentationTargets } returns MutableStateFlow<Set<String>?>(null)
        every { bloodhounds.activeUid } returns MutableStateFlow(null)
        val service = BloodhoundService()
        setField(service, "repo\$delegate", lazy { repository })
        setField(service, "bhRepo\$delegate", lazy { bloodhounds })
        setField(service, "currLoc", mockk<Location>())
        mockkObject(Utils)
        every { Utils.cancelWatchVibration() } returns Unit
        try {
            BloodhoundService::class.java.getDeclaredMethod("collectAzimuthFlow").apply {
                isAccessible = true
                invoke(service)
            }
            verify(timeout = 5_000, atLeast = 1) { bloodhounds.guidanceTargets() }
            verify(exactly = 0) { bloodhounds.updateBloodhoundCompassNeedles(any()) }

            heading.value = 45.0
            verify(timeout = 5_000, exactly = 1) {
                bloodhounds.updateBloodhoundCompassNeedles(mapOf("first" to 45.0, "second" to 225.0))
            }

            heading.value = 90.0
            verify(timeout = 5_000, exactly = 1) {
                bloodhounds.updateBloodhoundCompassNeedles(mapOf("first" to 0.0, "second" to 180.0))
            }
            verify(exactly = 0) { Utils.cancelWatchVibration() }
            verify(exactly = 2) { bloodhounds.updateBloodhoundCompassNeedles(any()) }
            verify(exactly = 0) { bloodhounds.updateBloodhoundCompassNeedle(any(), any()) }
        } finally {
            BloodhoundService::class.java.getDeclaredField("scope").apply {
                isAccessible = true
                (get(service) as CoroutineScope).cancel()
            }
            unmockkObject(Utils)
        }
    }

    private fun item(uid: String, bearing: Double?, hasLocation: Boolean = true): BloodhoundItem =
        mockk<BloodhoundItem>(relaxed = true).also {
            every { it.uid } returns uid
            every { it.vector.bearing } returns bearing
            if (hasLocation) every { it.bhEvent.remoteAlert } returns null
            else every { it.bhEvent.remoteAlert?.hasLocation } returns false
        }

    private fun setField(service: BloodhoundService, name: String, value: Any) {
        BloodhoundService::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(service, value)
        }
    }
}
