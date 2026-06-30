package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.weartak.plugin.sdk.HostSettingsContract
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.SettingsFieldParcel
import com.weartak.plugin.sdk.model.SettingsFieldTypes
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.validation.SettingsSchemaContractValidator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginSettingsCoordinatorTest {

    private lateinit var app: WearTakApplication
    private lateinit var coordinator: PluginSettingsCoordinator
    private lateinit var fakePlugin: FakeWearTakPlugin

    @Before
    fun setUp() {
        PluginDiagnostics.resetForTest()
        app = mockk(relaxed = true)
        val repo = mockk<DataRepository>(relaxed = true)
        every { app.repo } returns repo
        every { repo.getCallsign } returns flowOf("TEST")
        every { repo.getTeam } returns flowOf("Cyan")
        every { repo.getRole } returns flowOf("Member")
        every { repo.reportingUpdateIntervalSecs } returns MutableStateFlow(60)
        every { repo.sitxStatus } returns MutableStateFlow(DataRepository.SitxServerStatus.DISCONNECTED)
        coordinator = PluginSettingsCoordinator(
            app,
            HostSettingsSnapshotBuilder(app),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        fakePlugin = FakeWearTakPlugin("com.plugin.settings")
        fakePlugin.pluginCapabilities = PluginCapabilitiesParcel(hostedSettingsUi = true)
        val schema = when (
            val v = SettingsSchemaContractValidator.validate(
                SettingsSchemaParcel(
                    fields = listOf(
                        SettingsFieldParcel(
                            fieldId = "callsign_mirror",
                            label = "Callsign",
                            fieldType = SettingsFieldTypes.STRING,
                            hostSettingKey = HostSettingsContract.KEY_CALLSIGN,
                            readOnly = true,
                        ),
                        SettingsFieldParcel(
                            fieldId = "enable_stream",
                            label = "Stream",
                            fieldType = SettingsFieldTypes.TOGGLE,
                        ),
                    ),
                ),
            )
        ) {
            is com.weartak.plugin.sdk.validation.ContractValidationResult.Ok -> v.value
            else -> SettingsSchemaParcel()
        }
        fakePlugin.setSettingsSchemaForTest(schema)
    }

    @Test
    fun onPluginConnected_cachesSchemaAndPushesHostSettings() = runBlocking {
        coordinator.onPluginConnected("com.plugin.settings", fakePlugin)

        val state = coordinator.getState("com.plugin.settings")
        assertNotNull(state)
        assertTrue(state!!.settingsSupported)
        assertTrue(state.hostedSettingsUi)
        assertEquals(2, state.schema.fields.size)
        assertTrue(fakePlugin.hostSettingsReceived.isNotEmpty())
        assertTrue(state.hostSettingsRevision > 0L)
    }

    @Test
    fun onPluginSettingsValuesChanged_refreshesCache() = runBlocking {
        coordinator.onPluginConnected("com.plugin.settings", fakePlugin)
        fakePlugin.applySettings(Bundle().apply { putString("callsign_mirror", "ignored") })

        coordinator.onPluginSettingsValuesChanged("com.plugin.settings")

        val state = coordinator.getState("com.plugin.settings")
        assertNotNull(state)
    }

    @Test
    fun applyPluginSettings_validatesLocally() = runBlocking {
        coordinator.onPluginConnected("com.plugin.settings", fakePlugin)
        val result = coordinator.applyPluginSettings(
            "com.plugin.settings",
            Bundle().apply { putBoolean("enable_stream", true) },
        )
        assertTrue(result.success)
    }
}
