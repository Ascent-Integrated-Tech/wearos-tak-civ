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
    fun headingPublishesOneCompleteEligibleBatchAndStillEvaluatesProximity() {
        val heading = MutableStateFlow<Double?>(null)
        val repository = mockk<DataRepository>()
        val bloodhounds = mockk<BloodhoundRepository>(relaxed = true)
        val first = item("first", 90.0)
        val second = item("second", 270.0)
        val noBearing = item("no-bearing", null)
        val noLocation = item("no-location", 120.0, hasLocation = false)
        every { repository.azimuthTrueDegSmoothed } returns heading
        every { bloodhounds.bloodhoundMap } returns MutableStateFlow(
            listOf(first, second, noBearing, noLocation).associateBy { it.uid }
        )
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
            verify(timeout = 5_000, exactly = 1) { Utils.cancelWatchVibration() }
            verify(exactly = 0) { bloodhounds.updateBloodhoundCompassNeedles(any()) }

            heading.value = 45.0
            verify(timeout = 5_000, exactly = 1) {
                bloodhounds.updateBloodhoundCompassNeedles(mapOf("first" to 45.0, "second" to 225.0))
            }
            verify(timeout = 5_000, exactly = 2) { Utils.cancelWatchVibration() }

            heading.value = 90.0
            verify(timeout = 5_000, exactly = 1) {
                bloodhounds.updateBloodhoundCompassNeedles(mapOf("first" to 0.0, "second" to 180.0))
            }
            verify(timeout = 5_000, exactly = 3) { Utils.cancelWatchVibration() }
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
