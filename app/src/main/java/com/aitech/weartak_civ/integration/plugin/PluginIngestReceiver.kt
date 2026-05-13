package com.aitech.weartak_civ.integration.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.util.Log
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.aitech.weartak_civ.cep.plugin.PluginIngestRateLimiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Plugin sample ingest receiver (design §414–432).
 *
 * Invariants enforced here:
 *  1. Sender-package binding: claimed `pluginId` must equal a package owned by the
 *     broadcaster's UID (`Binder.getCallingUid()` + `getPackagesForUid`).
 *  2. Required extras: `pluginId`, `topic`, `ts`, and exactly one of `valueDouble`/
 *     `valueBool`.
 *  3. Topic namespacing: `topic` must start with `pluginId.` (design §480 / §914).
 *  4. Timestamp drift window: `ts` must fall within `[now - 30s, now + 5s]` (design §919).
 *  5. Per-topic leaky bucket at default 5 Hz (design §920).
 *  6. Cert pinning (run inside `CepProvider.ingestPluginSample` via `PluginTrust`).
 *
 * The receiver supports the design-canonical action `PLUGIN_SAMPLE` and, for backward
 * compatibility during the rename window, also accepts the legacy `PLUGIN_INGEST` action.
 * Cleanup plan: drop `PLUGIN_INGEST` once external plugins re-publish against the new name.
 */
class PluginIngestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != ACTION_PLUGIN_SAMPLE && action != ACTION_PLUGIN_INGEST_LEGACY) return
        if (action == ACTION_PLUGIN_INGEST_LEGACY) {
            Log.w(TAG, "legacy PLUGIN_INGEST accepted; migrate plugins to PLUGIN_SAMPLE")
        }

        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID)?.takeIf { it.isNotBlank() }
        val topic = intent.getStringExtra(EXTRA_TOPIC)?.takeIf { it.isNotBlank() }
        val ts = if (intent.hasExtra(EXTRA_TS)) intent.getLongExtra(EXTRA_TS, -1L) else -1L
        val hasDouble = intent.hasExtra(EXTRA_VALUE_DOUBLE)
        val hasBool = intent.hasExtra(EXTRA_VALUE_BOOL)

        if (pluginId == null || topic == null || ts < 0L || hasDouble == hasBool) {
            recordRejection(action, pluginId, ReasonCodes.PLUGIN_SAMPLE_INVALID,
                "missing or ambiguous extras (pluginId=$pluginId topic=$topic ts=$ts " +
                    "hasDouble=$hasDouble hasBool=$hasBool)")
            return
        }

        val senderUid = Binder.getCallingUid()
        val senderPackages: Set<String> = runCatching {
            context.packageManager.getPackagesForUid(senderUid)?.toSet().orEmpty()
        }.getOrDefault(emptySet())

        if (pluginId !in senderPackages) {
            Log.w(TAG, "rejecting PLUGIN_SAMPLE: sender uid=$senderUid pkgs=$senderPackages pluginId=$pluginId")
            recordSenderRejection(action, pluginId, senderUid, senderPackages)
            return
        }

        if (!topic.startsWith("$pluginId.")) {
            recordRejection(action, pluginId, ReasonCodes.PLUGIN_NAMESPACE_VIOLATION,
                "topic=$topic must be namespaced under pluginId=$pluginId")
            return
        }

        val now = System.currentTimeMillis()
        if (ts < now - DRIFT_PAST_MS || ts > now + DRIFT_FUTURE_MS) {
            recordRejection(action, pluginId, ReasonCodes.PLUGIN_SAMPLE_DRIFT,
                "ts=$ts outside [now-${DRIFT_PAST_MS / 1000}s, now+${DRIFT_FUTURE_MS / 1000}s] now=$now")
            return
        }

        if (!PluginIngestRateLimiter.tryAcquire(pluginId, topic, now)) {
            recordRejection(action, pluginId, ReasonCodes.PLUGIN_SAMPLE_RATE_LIMITED,
                "topic=$topic exceeded ${PluginIngestRateLimiter.DEFAULT_RATE_PER_SEC} Hz")
            return
        }

        val value: Double = if (hasDouble) intent.getDoubleExtra(EXTRA_VALUE_DOUBLE, 0.0)
            else if (intent.getBooleanExtra(EXTRA_VALUE_BOOL, false)) 1.0 else 0.0

        // Hop off the BroadcastReceiver main-thread budget. goAsync would be the canonical
        // choice but plugin sample ingest is best-effort fire-and-forget; we don't need a
        // PendingResult contract.
        scope.launch {
            try {
                CepProvider.ingestPluginSample(pluginId, topic, ts, value, senderUid, senderPackages)
            } catch (t: Throwable) {
                Log.e(TAG, "PLUGIN_SAMPLE ingest failed pluginId=$pluginId topic=$topic", t)
            }
        }
    }

    private fun recordSenderRejection(
        action: String,
        pluginId: String?,
        senderUid: Int,
        senderPackages: Set<String>,
    ) {
        runCatching {
            CepProvider.recordPluginSenderRejection(
                action = action,
                claimedPluginId = pluginId,
                senderUid = senderUid,
                senderPackages = senderPackages,
                reasonCode = ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH,
            )
        }
    }

    private fun recordRejection(
        action: String,
        pluginId: String?,
        reasonCode: String,
        message: String,
    ) {
        runCatching {
            CepProvider.recordPluginSenderRejection(
                action = action,
                claimedPluginId = pluginId,
                senderUid = -1,
                senderPackages = emptySet(),
                reasonCode = reasonCode,
            )
        }
        Log.w(TAG, "drop $action pluginId=$pluginId reason=$reasonCode: $message")
    }

    companion object {
        private const val TAG = "CEP-PluginIngestReceiver"
        /** Design-canonical action name (design §419). */
        const val ACTION_PLUGIN_SAMPLE = "com.aitech.weartak_civ.PLUGIN_SAMPLE"
        /**
         * Legacy action retained until external plugins are rebuilt against the
         * canonical action; emit a deprecation note via [TAG] when received.
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

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
