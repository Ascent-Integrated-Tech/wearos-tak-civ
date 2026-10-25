package com.aitech.weartak_civ.presentation

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.aitech.weartak_civ.data.BloodhoundRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BloodhoundPresentationDemandTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun retainedStoppedPickerHasNoDemandAndReentryReacquiresIt() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        val owner = Owner()
        val demand = observeBloodhoundPresentationDemand(owner.lifecycle, repository)
        try {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            assertEquals(emptySet<String>(), repository.presentationTargets.value)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            assertNull(repository.presentationTargets.value)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals(emptySet<String>(), repository.presentationTargets.value)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            assertNull(repository.presentationTargets.value)
        } finally {
            demand.close()
        }
        assertEquals(emptySet<String>(), repository.presentationTargets.value)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(emptySet<String>(), repository.presentationTargets.value)
    }

    @Test
    fun overlappingPickerAndDetailsReleaseOnlyTheirOwnDemand() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        val picker = Owner()
        val first = Owner()
        val second = Owner()
        val pickerDemand = observeBloodhoundPresentationDemand(picker.lifecycle, repository)
        val firstDemand = observeBloodhoundPresentationDemand(first.lifecycle, repository, "first")
        val secondDemand = observeBloodhoundPresentationDemand(second.lifecycle, repository, "second")
        try {
            first.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            second.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            assertEquals(setOf("first", "second"), repository.presentationTargets.value)
            picker.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            assertNull(repository.presentationTargets.value)
            picker.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals(setOf("first", "second"), repository.presentationTargets.value)
            first.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            assertEquals(setOf("second"), repository.presentationTargets.value)
            firstDemand.close()
            assertEquals(setOf("second"), repository.presentationTargets.value)
        } finally {
            pickerDemand.close()
            firstDemand.close()
            secondDemand.close()
        }
        assertEquals(emptySet<String>(), repository.presentationTargets.value)
    }
}
