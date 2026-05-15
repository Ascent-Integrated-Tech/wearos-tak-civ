package com.aitech.weartak_civ.integration.plugin

import com.weartak.plugin.sdk.aidl.IWearTakMapLayerControl
import com.weartak.plugin.sdk.model.MapLayerCommandParcel

/**
 * v1 host: map layers remain phase-gated; the contract surface is implemented so plugins
 * receive a stable binder from [com.weartak.plugin.sdk.aidl.IWearTakPluginControl#getMapLayerControl].
 */
class HostMapLayerControlImpl : IWearTakMapLayerControl.Stub() {
    override fun isMapLayerSupported(): Boolean = false

    override fun applyLayerCommand(command: MapLayerCommandParcel?): Int = -1
}
