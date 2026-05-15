package com.aitech.weartak_civ.integration.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
/**
 * Manifest-registered receiver for plugin overlay submissions (design §437–460) and
 * explicit unregister broadcasts (§552–562).
 *
 * Legacy adapter: delegates to [PluginIngressPort] (same path as AIDL-driven overlay changes
 * will use once plugins migrate fully off broadcasts).
 */
class PluginOverlayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val ingress = PluginIngressLocator.resolve(context) ?: return
        ingress.onLegacyPluginOverlayIntent(context, intent)
    }

    companion object {
        const val ACTION_PLUGIN_OVERLAY = "com.aitech.weartak_civ.PLUGIN_OVERLAY"
        const val ACTION_PLUGIN_UNREGISTER = "com.aitech.weartak_civ.PLUGIN_UNREGISTER"
        const val EXTRA_RAW_OVERLAY_JSON = "rawOverlayJson"
        const val EXTRA_PLUGIN_ID = "pluginId"
        /** Plugin-declared version string per design §445; optional, forwarded to diagnostics. */
        const val EXTRA_PLUGIN_VERSION = "pluginVersion"
        fun uniqueWorkNameFor(pluginId: String): String = "cep_plugin_overlay_apply::$pluginId"
    }
}
