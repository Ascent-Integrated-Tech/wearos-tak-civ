package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34],
    shadows = [FailingGlobalSettings::class])
class ChargingWirelessDebuggingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val resolver get() = app.contentResolver
    private val preferences get() = DebugStayAwakePreferences(app)
    private lateinit var debugging: ChargingWirelessDebugging
    private var writes = 0
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { writes++ }
    }

    @Before fun setUp() {
        FailingGlobalSettings.rejectWireless = false
        app.getSharedPreferences("debug_stay_awake", 0).edit().clear().commit()
        app.getSharedPreferences("debug_wireless_originals", 0).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Global.putString(resolver, "adb_enabled", "0")
        Settings.Global.putString(resolver, "adb_wifi_enabled", "0")
        debugging = ChargingWirelessDebugging(app)
        resolver.registerContentObserver(Settings.Global.CONTENT_URI, true, observer)
        idle()
        writes = 0
    }

    @After fun tearDown() {
        FailingGlobalSettings.rejectWireless = false
        resolver.unregisterContentObserver(observer)
        debugging.stop()
        idle()
    }

    @Test fun defaultsOffEvenWhenPlugged() {
        assertFalse(preferences.wirelessDebuggingEnabled)
        debugging.reconcile(true)
        assertSettings("0", "0")
        assertNoWrites()
    }

    @Test fun permissionMissingDoesNotWriteEvenWithSavedOptIn() {
        preferences.setWirelessDebuggingEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        assertFalse(ChargingWirelessDebugging.hasPermission(app))
        assertFalse(ChargingWirelessDebugging.requested(app))
        debugging.reconcile(true)
        debugging.stop()
        assertSettings("0", "0")
        assertNoWrites()
    }

    @Test fun enabledOffChargerDoesNothing() {
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(false)
        assertSettings("0", "0")
        assertNoWrites()
    }

    @Test fun pluggedEnablesBothAndUnplugRestores() {
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        assertSettings("1", "1")
        debugging.reconcile(false)
        assertSettings("0", "0")
        assertTrue(preferences.wirelessDebuggingEnabled)
        assertTrue(app.getSharedPreferences("debug_wireless_originals", 0).all.isEmpty())
    }

    @Test fun toggleOffWhilePluggedRestores() {
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        preferences.setWirelessDebuggingEnabled(false)
        debugging.reconcile(true)
        assertSettings("0", "0")
    }

    @Test fun managerStopRestoresWithoutErasingOptIn() {
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        debugging.stop()
        assertSettings("0", "0")
        assertTrue(preferences.wirelessDebuggingEnabled)
    }

    @Test fun existingAdbEnabledIsNotOwnedOrWritten() {
        Settings.Global.putString(resolver, "adb_enabled", "1")
        idle()
        writes = 0
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        idle()
        assertEquals(1, writes)
        debugging.stop()
        assertSettings("1", "0")
    }

    @Test fun alreadyEnabledWirelessIsNotDisabled() {
        Settings.Global.putString(resolver, "adb_wifi_enabled", "1")
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        debugging.stop()
        assertSettings("0", "1")
    }

    @Test fun exactAbsentAndNonBooleanOriginalsAreRestored() {
        Settings.Global.putString(resolver, "adb_enabled", null)
        Settings.Global.putString(resolver, "adb_wifi_enabled", "2")
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        assertSettings("1", "1")
        debugging.stop()
        assertSettings(null, "2")
    }

    @Test fun persistedOriginalsRestoreOnNextUnpluggedStart() {
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        ChargingWirelessDebugging(app).reconcile(false)
        assertSettings("0", "0")
    }

    @Test fun restartWhilePluggedDoesNotReplaceSavedOriginalsWithOnes() {
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        val restarted = ChargingWirelessDebugging(app)
        try {
            restarted.reconcile(true)
            assertSettings("1", "1")
            restarted.reconcile(false)
            assertSettings("0", "0")
        } finally {
            restarted.stop()
        }
    }

    @Test fun pendingOriginalsRestoreOnDisabledStart() {
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        preferences.setWirelessDebuggingEnabled(false)
        ChargingWirelessDebugging(app).reconcile(true)
        assertSettings("0", "0")
    }

    @Test fun externalChangeIsPreservedAndNotReenabledDuringSameCycle() {
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        Settings.Global.putString(resolver, "adb_wifi_enabled", "2")
        idle()
        debugging.reconcile(true)
        assertSettings("1", "2")
        debugging.stop()
        assertSettings("0", "2")
    }

    @Test fun observedExternalChangeBackToOneIsNotOverwritten() {
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        Settings.Global.putString(resolver, "adb_wifi_enabled", "0")
        idle()
        Settings.Global.putString(resolver, "adb_wifi_enabled", "1")
        idle()
        debugging.stop()
        assertSettings("0", "1")
    }

    @Test fun revokedPermissionKeepsJournalForNextGrantedStart() {
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        debugging.stop()
        assertSettings("1", "1")
        assertFalse(app.getSharedPreferences("debug_wireless_originals", 0).all.isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        ChargingWirelessDebugging(app).reconcile(false)
        assertSettings("0", "0")
    }

    @Test fun applicationStartupRestoresPendingBeforeManagerStartsEvenWhenPlugged() {
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        ChargingWirelessDebugging.restorePending(app)
        assertSettings("0", "0")
        assertTrue(preferences.wirelessDebuggingEnabled)
    }

    @Test fun toggleOffWithoutManagerRestoresPendingOriginals() {
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        ChargingWirelessDebugging.setEnabled(app, false)
        assertSettings("0", "0")
        assertFalse(preferences.wirelessDebuggingEnabled)
    }

    @Test fun deniedEnableDoesNotPersistChoiceOrWrite() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        ChargingWirelessDebugging.setEnabled(app, true)
        assertFalse(preferences.wirelessDebuggingEnabled)
        assertNoWrites()
    }

    @Test fun secureWriteFailureRollsBackPartialEnableAndLogs() {
        preferences.setWirelessDebuggingEnabled(true)
        FailingGlobalSettings.rejectWireless = true
        debugging.reconcile(true)
        assertSettings("0", "0")
        assertTrue(ShadowLog.getLogsForTag("WearTAK-ChargingADB")
            .any { it.throwable is SecurityException })
    }

    @Test fun restoreFailureRetainsOriginalForRetryOnNextStart() {
        preferences.setWirelessDebuggingEnabled(true)
        debugging.reconcile(true)
        FailingGlobalSettings.rejectWireless = true
        debugging.stop()
        assertSettings("0", "1")
        assertTrue(app.getSharedPreferences("debug_wireless_originals", 0)
            .contains("adb_wifi_enabled.pending"))
        FailingGlobalSettings.rejectWireless = false
        ChargingWirelessDebugging.restorePending(app)
        assertSettings("0", "0")
        assertTrue(app.getSharedPreferences("debug_wireless_originals", 0).all.isEmpty())
    }

    private fun assertSettings(adb: String?, wireless: String?) {
        assertEquals(adb, Settings.Global.getString(resolver, "adb_enabled"))
        assertEquals(wireless, Settings.Global.getString(resolver, "adb_wifi_enabled"))
    }

    private fun assertNoWrites() {
        idle()
        assertEquals(0, writes)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
