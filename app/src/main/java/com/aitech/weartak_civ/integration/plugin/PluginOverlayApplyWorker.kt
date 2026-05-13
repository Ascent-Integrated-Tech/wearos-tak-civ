package com.aitech.weartak_civ.integration.plugin

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aitech.weartak_civ.cep.di.CepProvider

class PluginOverlayApplyWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            when (inputData.getString(KEY_MODE)) {
                MODE_UPSERT -> {
                    val raw = inputData.getString(KEY_RAW_OVERLAY).orEmpty()
                    val senderUid = inputData.getInt(KEY_SENDER_UID, -1)
                    val senderPackages = inputData.getStringArray(KEY_SENDER_PACKAGES)
                        ?.toSet().orEmpty()
                    val r = CepProvider.applyPluginOverlay(raw, senderUid, senderPackages)
                    logOutcome("plugin_upsert", r)
                }
                MODE_UNREGISTER -> {
                    val pluginId = inputData.getString(KEY_PLUGIN_ID).orEmpty()
                    val r = CepProvider.unregisterPluginOverlay(pluginId)
                    logOutcome("plugin_unregister", r)
                }
                else -> Result.success()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "PluginOverlayApplyWorker failed", t)
            Result.failure()
        }
    }

    private fun logOutcome(kind: String, outcome: CepProvider.ApplyResult): Result {
        when (outcome) {
            is CepProvider.ApplyResult.Applied -> Log.i(TAG, "$kind applied hash=${outcome.effectiveHash}")
            CepProvider.ApplyResult.NoOp -> Log.i(TAG, "$kind no-op")
            CepProvider.ApplyResult.Superseded -> Log.i(TAG, "$kind superseded")
            is CepProvider.ApplyResult.Rejected -> Log.w(TAG, "$kind rejected: ${outcome.reasonCode}")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "CEP-PluginWorker"
        const val KEY_MODE = "mode"
        const val KEY_RAW_OVERLAY = "raw_overlay_json"
        const val KEY_PLUGIN_ID = "plugin_id"
        /** Plugin-declared version forwarded from broadcast extras (design §494). */
        const val KEY_PLUGIN_VERSION = "plugin_version"
        /** Resolved sender UID captured by the receiver from [android.os.Binder.getCallingUid]. */
        const val KEY_SENDER_UID = "sender_uid"
        /** Resolved sender package set from `PackageManager.getPackagesForUid`. */
        const val KEY_SENDER_PACKAGES = "sender_packages"

        const val MODE_UPSERT = "upsert"
        const val MODE_UNREGISTER = "unregister"
    }
}
