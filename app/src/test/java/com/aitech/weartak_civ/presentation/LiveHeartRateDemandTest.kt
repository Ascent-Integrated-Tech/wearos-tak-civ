package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.aitech.weartak_civ.service.HeartRateDelivery
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LiveHeartRateDemandTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Test fun onlyResumedVisibleDisplaysHoldLiveDemandAndOverlappingScreensReleaseIndependently() {
        val first = Owner()
        val second = Owner()
        val firstDemand = LiveHeartRateDemand(first.lifecycle)
        val secondDemand = LiveHeartRateDemand(second.lifecycle)
        try {
            assertFalse(HeartRateDelivery.live.value)
            first.lifecycle.currentState = Lifecycle.State.STARTED
            assertFalse(HeartRateDelivery.live.value)
            first.lifecycle.currentState = Lifecycle.State.RESUMED
            assertTrue(HeartRateDelivery.live.value)
            second.lifecycle.currentState = Lifecycle.State.RESUMED
            first.lifecycle.currentState = Lifecycle.State.STARTED
            assertTrue(HeartRateDelivery.live.value)
            secondDemand.close()
            assertFalse(HeartRateDelivery.live.value)
            first.lifecycle.currentState = Lifecycle.State.RESUMED
            assertTrue(HeartRateDelivery.live.value)
            first.lifecycle.currentState = Lifecycle.State.DESTROYED
            assertFalse(HeartRateDelivery.live.value)
        } finally {
            firstDemand.close()
            secondDemand.close()
        }
    }

    @Test fun disposalWhileResumedReleasesDemandAndCannotBeReactivatedByLifecycle() {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        val demand = LiveHeartRateDemand(owner.lifecycle)
        assertTrue(HeartRateDelivery.live.value)
        demand.close()
        demand.close()
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertFalse(HeartRateDelivery.live.value)
    }
}
