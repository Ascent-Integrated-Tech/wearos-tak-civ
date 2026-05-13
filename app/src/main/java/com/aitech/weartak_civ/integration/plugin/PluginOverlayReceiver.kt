package com.aitech.weartak_civ.integration.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.aitech.weartak_civ.cep.plugin.PluginOverlaySubmissionLimiter

/**
 * Manifest-registered receiver for plugin overlay submissions (design §437–460) and
 * explicit unregister broadcasts (§552–562).
 *
 * Trust gate (design §477 / 2026-05-11 review Finding C): the receiver MUST resolve the
 * broadcaster's UID via [Binder.getCallingUid] and reject the broadcast if the calling
 * UID does not own the claimed `pluginId`. This binding is captured here and propagated
 * through WorkData so the worker / `CepProvider.applyPluginOverlay` can hand the same
 * resolved identity to [com.aitech.weartak_civ.cep.plugin.PluginTrust.enforce] for
 * defense-in-depth cert pinning.
 */
class PluginOverlayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val senderUid = Binder.getCallingUid()
        val senderPackages: Set<String> = runCatching {
            context.packageManager.getPackagesForUid(senderUid)?.toSet().orEmpty()
        }.getOrDefault(emptySet())

        val data = Data.Builder()
        var pluginIdForWork: String? = null
        when (action) {
            ACTION_PLUGIN_OVERLAY -> {
                val raw = intent.getStringExtra(EXTRA_RAW_OVERLAY_JSON) ?: return
                // pluginId is REQUIRED in the broadcast extras (design §441–445) so we can
                // bind sender→pluginId without parsing the overlay payload first.
                val claimedPluginId = intent.getStringExtra(EXTRA_PLUGIN_ID)
                if (claimedPluginId.isNullOrBlank() || claimedPluginId !in senderPackages) {
                    Log.w(TAG, "rejecting PLUGIN_OVERLAY: sender uid=$senderUid pkgs=$senderPackages claimed=$claimedPluginId")
                    runCatching {
                        CepProvider.recordPluginSenderRejection(
                            action = ACTION_PLUGIN_OVERLAY,
                            claimedPluginId = claimedPluginId,
                            senderUid = senderUid,
                            senderPackages = senderPackages,
                            reasonCode = ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH,
                        )
                    }
                    return
                }
                data.putString(PluginOverlayApplyWorker.KEY_MODE, PluginOverlayApplyWorker.MODE_UPSERT)
                data.putString(PluginOverlayApplyWorker.KEY_RAW_OVERLAY, raw)
                data.putString(PluginOverlayApplyWorker.KEY_PLUGIN_ID, claimedPluginId)
                pluginIdForWork = claimedPluginId
                data.putInt(PluginOverlayApplyWorker.KEY_SENDER_UID, senderUid)
                data.putStringArray(
                    PluginOverlayApplyWorker.KEY_SENDER_PACKAGES,
                    senderPackages.toTypedArray()
                )
                intent.getStringExtra(EXTRA_PLUGIN_VERSION)?.takeIf { it.isNotBlank() }?.let {
                    data.putString(PluginOverlayApplyWorker.KEY_PLUGIN_VERSION, it)
                }
                val now = System.currentTimeMillis()
                if (!PluginOverlaySubmissionLimiter.tryAcquire(claimedPluginId, now)) {
                    Log.w(TAG, "rejecting PLUGIN_OVERLAY rate-limited pluginId=$claimedPluginId senderUid=$senderUid")
                    runCatching {
                        CepProvider.recordPluginSenderRejection(
                            action = ACTION_PLUGIN_OVERLAY,
                            claimedPluginId = claimedPluginId,
                            senderUid = senderUid,
                            senderPackages = senderPackages,
                            reasonCode = ReasonCodes.PLUGIN_OVERLAY_RATE_LIMITED,
                        )
                    }
                    return
                }
            }
            ACTION_PLUGIN_UNREGISTER -> {
                val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID) ?: return
                if (pluginId !in senderPackages) {
                    Log.w(TAG, "rejecting PLUGIN_UNREGISTER: sender uid=$senderUid pkgs=$senderPackages pluginId=$pluginId")
                    runCatching {
                        CepProvider.recordPluginSenderRejection(
                            action = ACTION_PLUGIN_UNREGISTER,
                            claimedPluginId = pluginId,
                            senderUid = senderUid,
                            senderPackages = senderPackages,
                            reasonCode = ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH,
                        )
                    }
                    return
                }
                data.putString(PluginOverlayApplyWorker.KEY_MODE, PluginOverlayApplyWorker.MODE_UNREGISTER)
                data.putString(PluginOverlayApplyWorker.KEY_PLUGIN_ID, pluginId)
                pluginIdForWork = pluginId
                data.putInt(PluginOverlayApplyWorker.KEY_SENDER_UID, senderUid)
                data.putStringArray(
                    PluginOverlayApplyWorker.KEY_SENDER_PACKAGES,
                    senderPackages.toTypedArray()
                )
            }
            else -> return
        }
        val req = OneTimeWorkRequestBuilder<PluginOverlayApplyWorker>()
            .setInputData(data.build())
            .build()
        val workNamePluginId = pluginIdForWork ?: return
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueWorkNameFor(workNamePluginId), ExistingWorkPolicy.REPLACE, req)
    }

    companion object {
        private const val TAG = "CEP-PluginOverlayReceiver"
        const val ACTION_PLUGIN_OVERLAY = "com.aitech.weartak_civ.PLUGIN_OVERLAY"
        const val ACTION_PLUGIN_UNREGISTER = "com.aitech.weartak_civ.PLUGIN_UNREGISTER"
        const val EXTRA_RAW_OVERLAY_JSON = "rawOverlayJson"
        const val EXTRA_PLUGIN_ID = "pluginId"
        /** Plugin-declared version string per design §445; optional, forwarded to diagnostics. */
        const val EXTRA_PLUGIN_VERSION = "pluginVersion"
        fun uniqueWorkNameFor(pluginId: String): String = "cep_plugin_overlay_apply::$pluginId"
    }
}
