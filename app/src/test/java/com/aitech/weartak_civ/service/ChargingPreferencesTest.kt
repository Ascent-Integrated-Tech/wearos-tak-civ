package com.aitech.weartak_civ.service

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChargingPreferencesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val store get() = app.getSharedPreferences("debug_stay_awake", 0)

    @Before fun reset() {
        store.edit().clear().commit()
    }

    @Test fun freshInstallDefaultsOff() {
        assertFalse(DebugStayAwakePreferences(app).chargingEnabled)
    }

    @Test fun migratesStayAwakeOnly() = verifyMigration(true, false)

    @Test fun migratesWirelessOnly() = verifyMigration(false, true)

    @Test fun migratesBothOff() = verifyMigration(false, false)

    @Test fun migratesBothOn() = verifyMigration(true, true)

    @Test fun migrationDoesNotOverwriteLaterChoiceOrManualMode() {
        store.edit().putBoolean("when_charging", true)
            .putBoolean("session_active", true).commit()
        val preferences = DebugStayAwakePreferences(app)
        assertTrue(preferences.chargingEnabled)
        preferences.setChargingEnabled(false)
        assertFalse(DebugStayAwakePreferences(app).chargingEnabled)
        assertTrue(DebugStayAwakePreferences(app).enabled)
    }

    private fun verifyMigration(stayAwake: Boolean, wireless: Boolean) {
        store.edit().putBoolean("when_charging", stayAwake)
            .putBoolean("wireless_when_charging", wireless).commit()
        val expected = stayAwake || wireless
        assertEquals(expected, DebugStayAwakePreferences(app).chargingEnabled)
        store.edit().putBoolean("when_charging", !stayAwake)
            .putBoolean("wireless_when_charging", !wireless).commit()
        assertEquals(expected, DebugStayAwakePreferences(app).chargingEnabled)
    }
}
