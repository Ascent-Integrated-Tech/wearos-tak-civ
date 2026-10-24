package com.aitech.weartak_civ.data

import android.app.Application
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SavedCustomAlertPersistenceTest {
    @Test fun loadingLegacyAlertsDoesNotNormalizeDropOrCapSavedRecords() {
        val legacy = listOf("x".repeat(80), "  Original   spacing ", "Old\nrecord") +
            (1..21).map { "Existing $it" }
        val store = SavedCustomAlertsStore(RuntimeEnvironment.getApplication())
        assertEquals(legacy, store.decode(Gson().toJson(legacy)))
    }
}
