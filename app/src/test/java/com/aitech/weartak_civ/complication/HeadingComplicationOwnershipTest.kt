package com.aitech.weartak_civ.complication

import android.app.Application
import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationType
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HeadingComplicationOwnershipTest {
    @Test fun platformActivationAndRemovalCallbacksKeepIndependentPersistentSlotOwnership() {
        val context = RuntimeEnvironment.getApplication()
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "heading-test"
        context.getSharedPreferences("heading-complications", Context.MODE_PRIVATE).edit().clear().commit()
        val repository = DataRepository(context)
        val application = mockk<WearTakApplication>()
        every { application.repo } returns repository
        val service = spyk(Robolectric.buildService(BloodhoundComplicationService::class.java).get())
        every { service.applicationContext } returns application
        service.onComplicationActivated(1, ComplicationType.LONG_TEXT)
        service.onComplicationActivated(2, ComplicationType.LONG_TEXT)
        assertEquals(setOf("1", "2"), repository.headingComplications.activeIds.value)
        service.onComplicationDeactivated(1)
        assertEquals(setOf("2"), repository.headingComplications.activeIds.value)
        assertEquals(setOf("2"), DataRepository(context).headingComplications.activeIds.value)
        service.onComplicationDeactivated(2)
        assertTrue(DataRepository(context).headingComplications.activeIds.value.isEmpty())
    }
}
