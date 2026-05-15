package com.aitech.weartak_civ.integration.plugin

import android.util.Log
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.model.CotReadyEventParcel
import com.weartak.plugin.sdk.model.PluginErrorParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SensorEventParcel
import com.weartak.plugin.sdk.model.SharedBufferParcel

class HostCallbackImpl(
    private val ingress: PluginIngressPort,
    private val pluginId: String,
    private val pluginUid: Int,
) : IWearTakHostCallback.Stub() {

    override fun onSensorEvent(event: SensorEventParcel?) {
        if (event == null) return
        runCatching { ingress.onSensorEventFromPlugin(pluginId, pluginUid, event) }
            .onFailure { Log.e(TAG, "onSensorEvent", it) }
    }

    override fun onSensorBatchEvent(events: MutableList<SensorEventParcel>?) {
        if (events == null || events.isEmpty()) return
        runCatching { ingress.onSensorBatchFromPlugin(pluginId, pluginUid, events.toList()) }
            .onFailure { Log.e(TAG, "onSensorBatchEvent", it) }
    }

    override fun onCotReadyEvent(event: CotReadyEventParcel?) {
        Log.i(TAG, "onCotReadyEvent ready=${event?.ready} detail=${event?.detail}")
    }

    override fun onPluginSettingsSchemaChanged() {
        Log.i(TAG, "onPluginSettingsSchemaChanged pluginId=$pluginId")
    }

    override fun onPluginSettingsValuesChanged() {
        Log.i(TAG, "onPluginSettingsValuesChanged pluginId=$pluginId")
    }

    override fun onPluginMainUiSchemaChanged() {
        Log.i(TAG, "onPluginMainUiSchemaChanged pluginId=$pluginId")
    }

    override fun onPluginMainUiStateChanged() {
        Log.i(TAG, "onPluginMainUiStateChanged pluginId=$pluginId")
    }

    override fun onPluginStatusChanged(status: PluginStatusParcel?) {
        Log.i(TAG, "onPluginStatusChanged pluginId=$pluginId status=$status")
    }

    override fun onPluginError(error: PluginErrorParcel?) {
        Log.w(TAG, "onPluginError pluginId=$pluginId code=${error?.code} msg=${error?.message} fatal=${error?.fatal}")
    }

    override fun onSharedBufferAvailable(buffer: SharedBufferParcel?) {
        Log.i(TAG, "onSharedBufferAvailable pluginId=$pluginId buffer=$buffer")
    }

    companion object {
        private const val TAG = "CEP-HostCallback"
    }
}
