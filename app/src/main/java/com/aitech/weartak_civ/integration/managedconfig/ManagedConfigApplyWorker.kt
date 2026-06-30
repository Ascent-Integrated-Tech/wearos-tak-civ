package com.aitech.weartak_civ.integration.managedconfig

import android.content.Context
import android.content.RestrictionsManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aitech.weartak_civ.cep.di.CepProvider

/**
 * Reads `cep_configuration_json` from the app restrictions bundle and hands it to
 * [CepProvider.applyManagedOverlay] for the full parse → validate → swap pipeline.
 *
 * The worker is intentionally thin: it owns transport (RestrictionsManager + WorkManager
 * coalescing), nothing more. Engine-side last-write-wins semantics + transactional swap
 * + diagnostics live in `CepProvider` so they apply uniformly to bootstrap, plugin paths
 * (WEAR-509), and any future ingestion source.
 */
class ManagedConfigApplyWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val rm = applicationContext.getSystemService(Context.RESTRICTIONS_SERVICE)
                as? RestrictionsManager
                ?: run {
                    Log.w(TAG, "RestrictionsManager unavailable; nothing to apply")
                    return Result.success()
                }
            val bundle = rm.applicationRestrictions
            val raw = bundle?.getString("cep_configuration_json", "") ?: ""
            Log.i(TAG, "applyManagedOverlay: rawLen=${raw.length} (empty means no overlay)")

            when (val outcome = CepProvider.applyManagedOverlay(raw)) {
                is CepProvider.ApplyResult.Applied -> {
                    Log.i(TAG, "CEP overlay applied: hash=${outcome.effectiveHash} at=${outcome.atMs}")
                    Result.success()
                }
                is CepProvider.ApplyResult.NoOp -> {
                    Log.i(TAG, "CEP overlay no-op (identical effective hash)")
                    Result.success()
                }
                is CepProvider.ApplyResult.Superseded -> {
                    Log.i(TAG, "CEP overlay candidate superseded by a newer payload")
                    Result.success()
                }
                is CepProvider.ApplyResult.Rejected -> {
                    Log.w(TAG, "CEP overlay rejected: ${outcome.reasonCode} — ${outcome.message}")
                    // Diagnostic was persisted by CepProvider already. Do not retry —
                    // a bad payload won't get better on retry, and another broadcast will
                    // re-enqueue when a newer payload is published.
                    Result.success()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ManagedConfigApplyWorker failed", t)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "CEP-MgrWorker"
    }
}
