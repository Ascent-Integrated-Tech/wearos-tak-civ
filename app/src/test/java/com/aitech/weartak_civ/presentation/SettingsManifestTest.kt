package com.aitech.weartak_civ.presentation

import android.app.Application
import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsManifestTest {
    @Test fun legacySettingsComponentResolvesToCurrentSettingsActivity() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getActivityInfo(
            ComponentName(app.packageName, "com.aitech.weartak_civ.presentation.SettingsActivity"), 0
        )
        assertEquals(WearTAKSettingsActivity::class.java.name, info.targetActivity)
    }
}
