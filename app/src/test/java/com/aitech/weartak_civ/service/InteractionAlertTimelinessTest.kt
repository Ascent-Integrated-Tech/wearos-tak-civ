package com.aitech.weartak_civ.service

import android.app.Application
import android.location.Location
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class InteractionAlertTimelinessTest {
    @Test fun safetyActivationAndCancellationPreserveBaselineLocationFreshnessAndOfflineQueueing() {
        val activity = mockk<MainActivity>()
        every { activity.applicationContext } returns RuntimeEnvironment.getApplication()
        MainActivity.mainActivity = activity
        val connected = MutableStateFlow(true)
        val location = MutableStateFlow<Location?>(null)
        val repo = mockk<DataRepository>()
        every { repo.anyEndpointConnected } returns connected
        every { repo.latestLocation } returns location
        val service = CotDispatchService()
        CotDispatchService::class.java.getDeclaredField("dataRepository").apply {
            isAccessible = true
            set(service, repo)
        }
        val ready = CotDispatchService::class.java.getDeclaredMethod("readyToSendAlertEvents").apply {
            isAccessible = true
        }
        assertTrue(ready.invoke(service) as Boolean)
        location.value = Location("gps").apply { time = 1 }
        assertFalse(ready.invoke(service) as Boolean)
        CotDispatchService::class.java.getDeclaredField("lastLocationUpdateRcvd").apply {
            isAccessible = true
            setLong(service, System.currentTimeMillis())
        }
        assertTrue(ready.invoke(service) as Boolean)
        connected.value = false
        assertFalse(ready.invoke(service) as Boolean)
    }
}
