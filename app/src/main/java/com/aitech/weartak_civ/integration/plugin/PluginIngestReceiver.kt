package com.aitech.weartak_civ.integration.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
/**
 * Plugin sample ingest receiver (design §414–432).
 *
 * Legacy broadcast path: delegates validation and routing to [PluginIngressPort] so AIDL
 * sensor ingress and broadcast ingest share one façade into [com.aitech.weartak_civ.cep.di.CepProvider].
 */
class PluginIngestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val ingress = PluginIngressLocator.resolve(context) ?: return
        ingress.onLegacyPluginSampleIntent(context, intent)
    }

    companion object {
        /** Design-canonical action name (design §419). */
        const val ACTION_PLUGIN_SAMPLE = "com.aitech.weartak_civ.PLUGIN_SAMPLE"
        /**
         * Legacy action retained until external plugins are rebuilt against the
         * canonical action; emit a deprecation note via logs when received.
         */
        const val ACTION_PLUGIN_INGEST_LEGACY = "com.aitech.weartak_civ.PLUGIN_INGEST"

        const val EXTRA_PLUGIN_ID = "pluginId"
        const val EXTRA_TOPIC = "topic"
        const val EXTRA_TS = "ts"
        const val EXTRA_VALUE_DOUBLE = "valueDouble"
        const val EXTRA_VALUE_BOOL = "valueBool"

        // Drift window per design §919.
        const val DRIFT_PAST_MS = 30_000L
        const val DRIFT_FUTURE_MS = 5_000L
    }
}
