package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.PluginInfoParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel

/**
 * Minimal in-process [IWearTakPlugin] for host bind/lifecycle tests (no plugin APK).
 */
class FakeWearTakPlugin(
    private val packageName: String,
) : IWearTakPlugin.Stub() {

    private val caps = PluginCapabilitiesParcel()

    override fun getPluginInfo(): PluginInfoParcel =
        PluginInfoParcel(
            pluginId = packageName,
            displayName = "Fake",
            versionName = "1.0",
            packageName = packageName,
        )

    override fun getCapabilities(): PluginCapabilitiesParcel = caps

    override fun getApiVersion(): Int = WearTakPluginContract.CONTRACT_API_VERSION

    override fun getStatus(): PluginStatusParcel =
        PluginStatusParcel(state = PluginLifecycleState.RUNNING)

    override fun initializePlugin(init: com.weartak.plugin.sdk.model.PluginInitParcel?, control: IWearTakPluginControl?) {
        // no-op
    }

    override fun startPlugin() {}

    override fun stopPlugin() {}

    override fun shutdownPlugin() {}

    override fun registerHostCallback(callback: IWearTakHostCallback?) {}

    override fun unregisterHostCallback(callback: IWearTakHostCallback?) {}

    override fun getSettingsSchema(): SettingsSchemaParcel = SettingsSchemaParcel()

    override fun getSettingsValues(): Bundle = Bundle()

    override fun applySettings(values: Bundle?) {}

    override fun getMainUiSchema(): PluginMainUiSchemaParcel = PluginMainUiSchemaParcel()

    override fun getMainUiState(): Bundle = Bundle()

    override fun invokeMainUiAction(actionId: String?, args: Bundle?) {}
}
