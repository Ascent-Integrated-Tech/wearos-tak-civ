package com.aitech.weartak_civ.integration.plugin

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.aitech.weartak_civ.cep.plugin.PluginIngestRateLimiter
import com.aitech.weartak_civ.cep.plugin.PluginOverlaySubmissionLimiter
import com.weartak.plugin.sdk.model.CepRuleEventParcel
import com.weartak.plugin.sdk.model.CepRuleOperation
import com.weartak.plugin.sdk.model.SensorEventParcel
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Single façade for plugin-originated traffic: AIDL callbacks, host control merges, and
 * legacy broadcast adapters all route through here into [CepProvider] and related limits.
 */
class PluginIngressPort(
    private val app: WearTakApplication,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastCepSequenceByStream = ConcurrentHashMap<String, Long>()

    fun packagesForUid(context: Context, uid: Int): Set<String> = runCatching {
        context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    // --- Legacy PLUGIN_SAMPLE / PLUGIN_INGEST ---

    fun onLegacyPluginSampleIntent(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != PluginIngestReceiver.ACTION_PLUGIN_SAMPLE &&
            action != PluginIngestReceiver.ACTION_PLUGIN_INGEST_LEGACY
        ) {
            return
        }
        if (action == PluginIngestReceiver.ACTION_PLUGIN_INGEST_LEGACY) {
            Log.w(TAG, "legacy PLUGIN_INGEST accepted; migrate plugins to PLUGIN_SAMPLE")
        }

        val pluginId = intent.getStringExtra(PluginIngestReceiver.EXTRA_PLUGIN_ID)?.takeIf { it.isNotBlank() }
        val topic = intent.getStringExtra(PluginIngestReceiver.EXTRA_TOPIC)?.takeIf { it.isNotBlank() }
        val ts = if (intent.hasExtra(PluginIngestReceiver.EXTRA_TS)) {
            intent.getLongExtra(PluginIngestReceiver.EXTRA_TS, -1L)
        } else {
            -1L
        }
        val hasDouble = intent.hasExtra(PluginIngestReceiver.EXTRA_VALUE_DOUBLE)
        val hasBool = intent.hasExtra(PluginIngestReceiver.EXTRA_VALUE_BOOL)

        if (pluginId == null || topic == null || ts < 0L || hasDouble == hasBool) {
            recordRejection(
                context, action, pluginId, ReasonCodes.PLUGIN_SAMPLE_INVALID,
                "missing or ambiguous extras (pluginId=$pluginId topic=$topic ts=$ts hasDouble=$hasDouble hasBool=$hasBool)"
            )
            return
        }

        val senderUid = Binder.getCallingUid()
        val senderPackages = packagesForUid(context, senderUid)

        if (pluginId !in senderPackages) {
            Log.w(TAG, "rejecting PLUGIN_SAMPLE: sender uid=$senderUid pkgs=$senderPackages pluginId=$pluginId")
            recordSenderRejection(context, action, pluginId, senderUid, senderPackages)
            return
        }

        if (!topic.startsWith("$pluginId.")) {
            recordRejection(
                context, action, pluginId, ReasonCodes.PLUGIN_NAMESPACE_VIOLATION,
                "topic=$topic must be namespaced under pluginId=$pluginId"
            )
            return
        }

        val now = System.currentTimeMillis()
        if (ts < now - PluginIngestReceiver.DRIFT_PAST_MS || ts > now + PluginIngestReceiver.DRIFT_FUTURE_MS) {
            recordRejection(
                context, action, pluginId, ReasonCodes.PLUGIN_SAMPLE_DRIFT,
                "ts=$ts outside drift window now=$now"
            )
            return
        }

        if (!PluginIngestRateLimiter.tryAcquire(pluginId, topic, now)) {
            recordRejection(
                context, action, pluginId, ReasonCodes.PLUGIN_SAMPLE_RATE_LIMITED,
                "topic=$topic exceeded ${PluginIngestRateLimiter.DEFAULT_RATE_PER_SEC} Hz"
            )
            return
        }

        val value: Double = if (hasDouble) {
            intent.getDoubleExtra(PluginIngestReceiver.EXTRA_VALUE_DOUBLE, 0.0)
        } else if (intent.getBooleanExtra(PluginIngestReceiver.EXTRA_VALUE_BOOL, false)) {
            1.0
        } else {
            0.0
        }

        scope.launch {
            runCatching {
                CepProvider.ingestPluginSample(pluginId, topic, ts, value, senderUid, senderPackages)
            }.onFailure { Log.e(TAG, "PLUGIN_SAMPLE ingest failed pluginId=$pluginId topic=$topic", it) }
        }
    }

    // --- AIDL sensor path ---

    fun onSensorEventFromPlugin(
        pluginId: String,
        senderUid: Int,
        event: SensorEventParcel,
    ) {
        val senderPackages = packagesForUid(app, senderUid)
        if (pluginId !in senderPackages) {
            Log.w(TAG, "AIDL sensor reject: uid=$senderUid does not own pluginId=$pluginId")
            CepProvider.recordPluginSenderRejection(
                action = "aidl:onSensorEvent",
                claimedPluginId = pluginId,
                senderUid = senderUid,
                senderPackages = senderPackages,
            )
            return
        }
        val topic = sensorTopic(pluginId, event.sensorKey)
        if (!topic.startsWith("$pluginId.")) {
            Log.w(TAG, "AIDL sensor reject: bad topic=$topic")
            return
        }
        val ts = event.timestampUtcMillis
        val now = System.currentTimeMillis()
        if (ts < now - PluginIngestReceiver.DRIFT_PAST_MS || ts > now + PluginIngestReceiver.DRIFT_FUTURE_MS) {
            Log.w(TAG, "AIDL sensor reject: drift ts=$ts")
            return
        }
        if (!PluginIngestRateLimiter.tryAcquire(pluginId, topic, now)) {
            Log.w(TAG, "AIDL sensor reject: rate limited topic=$topic")
            return
        }
        val value = firstNumericFromValues(event.values)
        scope.launch {
            runCatching {
                CepProvider.ingestPluginSample(pluginId, topic, ts, value, senderUid, senderPackages)
            }.onFailure { Log.e(TAG, "AIDL sensor ingest failed pluginId=$pluginId topic=$topic", it) }
        }
    }

    fun onSensorBatchFromPlugin(
        pluginId: String,
        senderUid: Int,
        events: List<SensorEventParcel>,
    ) {
        for (e in events) onSensorEventFromPlugin(pluginId, senderUid, e)
    }

    // --- Legacy PLUGIN_OVERLAY ---

    fun onLegacyPluginOverlayIntent(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val senderUid = Binder.getCallingUid()
        val senderPackages = packagesForUid(context, senderUid)

        val data = Data.Builder()
        var pluginIdForWork: String? = null
        when (action) {
            PluginOverlayReceiver.ACTION_PLUGIN_OVERLAY -> {
                val raw = intent.getStringExtra(PluginOverlayReceiver.EXTRA_RAW_OVERLAY_JSON) ?: return
                val claimedPluginId = intent.getStringExtra(PluginOverlayReceiver.EXTRA_PLUGIN_ID)
                if (claimedPluginId.isNullOrBlank() || claimedPluginId !in senderPackages) {
                    Log.w(
                        TAG,
                        "rejecting PLUGIN_OVERLAY: sender uid=$senderUid pkgs=$senderPackages claimed=$claimedPluginId"
                    )
                    runCatching {
                        CepProvider.recordPluginSenderRejection(
                            action = PluginOverlayReceiver.ACTION_PLUGIN_OVERLAY,
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
                intent.getStringExtra(PluginOverlayReceiver.EXTRA_PLUGIN_VERSION)?.takeIf { it.isNotBlank() }?.let {
                    data.putString(PluginOverlayApplyWorker.KEY_PLUGIN_VERSION, it)
                }
                val now = System.currentTimeMillis()
                if (!PluginOverlaySubmissionLimiter.tryAcquire(claimedPluginId, now)) {
                    Log.w(TAG, "rejecting PLUGIN_OVERLAY rate-limited pluginId=$claimedPluginId senderUid=$senderUid")
                    runCatching {
                        CepProvider.recordPluginSenderRejection(
                            action = PluginOverlayReceiver.ACTION_PLUGIN_OVERLAY,
                            claimedPluginId = claimedPluginId,
                            senderUid = senderUid,
                            senderPackages = senderPackages,
                            reasonCode = ReasonCodes.PLUGIN_OVERLAY_RATE_LIMITED,
                        )
                    }
                    return
                }
            }
            PluginOverlayReceiver.ACTION_PLUGIN_UNREGISTER -> {
                val pluginId = intent.getStringExtra(PluginOverlayReceiver.EXTRA_PLUGIN_ID) ?: return
                if (pluginId !in senderPackages) {
                    Log.w(TAG, "rejecting PLUGIN_UNREGISTER: sender uid=$senderUid pkgs=$senderPackages pluginId=$pluginId")
                    runCatching {
                        CepProvider.recordPluginSenderRejection(
                            action = PluginOverlayReceiver.ACTION_PLUGIN_UNREGISTER,
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
            .enqueueUniqueWork(
                PluginOverlayReceiver.uniqueWorkNameFor(workNamePluginId),
                ExistingWorkPolicy.REPLACE,
                req
            )
    }

    fun onPackageFullyRemoved(packageName: String) {
        PluginDiagnostics.recordPackageRemoved(packageName)
        scope.launch {
            runCatching { CepProvider.evictPluginByPackage(packageName) }
                .onFailure { Log.w(TAG, "evictPluginByPackage($packageName) failed", it) }
        }
    }

    suspend fun applyOverlayFromWorker(
        mode: String?,
        raw: String?,
        pluginId: String,
        senderUid: Int,
        senderPackages: Set<String>,
    ): CepProvider.ApplyResult {
        return when (mode) {
            PluginOverlayApplyWorker.MODE_UPSERT ->
                CepProvider.applyPluginOverlay(raw.orEmpty(), senderUid, senderPackages)
            PluginOverlayApplyWorker.MODE_UNREGISTER ->
                CepProvider.unregisterPluginOverlay(pluginId)
            else -> CepProvider.ApplyResult.NoOp
        }
    }

    /** Synchronous path for [HostControlImpl] binder thread. */
    fun registerCepRulesBlocking(
        pluginId: String,
        sensorKey: String,
        rulesJson: String,
        senderUid: Int,
        senderPackages: Set<String>,
    ): CepProvider.ApplyResult {
        return runBlocking {
            val existing = app.repo.getCepPluginOverlaysOnce()[pluginId]
            val merged = try {
                PluginCepRulesMerge.mergeRegister(
                    pluginId = pluginId,
                    sensorKey = sensorKey,
                    rulesJson = org.json.JSONObject(rulesJson),
                    existingRaw = existing,
                )
            } catch (e: Exception) {
                Log.w(TAG, "registerCepRules merge failed", e)
                return@runBlocking CepProvider.ApplyResult.Rejected(
                    ReasonCodes.MALFORMED_JSON,
                    e.message ?: "merge failed"
                )
            }
            val now = System.currentTimeMillis()
            if (!PluginOverlaySubmissionLimiter.tryAcquire(pluginId, now)) {
                return@runBlocking CepProvider.ApplyResult.Rejected(
                    ReasonCodes.PLUGIN_OVERLAY_RATE_LIMITED,
                    "rate limited"
                )
            }
            CepProvider.applyPluginOverlay(merged, senderUid, senderPackages)
        }
    }

    fun unregisterCepRulesBlocking(
        pluginId: String,
        sensorKey: String,
        senderUid: Int,
        senderPackages: Set<String>,
    ): CepProvider.ApplyResult {
        return runBlocking {
            val existing = app.repo.getCepPluginOverlaysOnce()[pluginId]
            val merged = PluginCepRulesMerge.mergeUnregister(
                pluginId = pluginId,
                sensorKey = sensorKey,
                existingRaw = existing,
            )
            CepProvider.applyPluginOverlay(merged, senderUid, senderPackages)
        }
    }

    fun applyCepRuleEventBlocking(
        pluginId: String,
        event: CepRuleEventParcel,
        senderUid: Int,
        senderPackages: Set<String>,
    ): CepProvider.ApplyResult {
        val resolvedPluginId = pluginId.trim()
        if (resolvedPluginId.isEmpty()) {
            return CepProvider.ApplyResult.Rejected(ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH, "empty pluginId")
        }
        if (resolvedPluginId !in senderPackages) {
            CepProvider.recordPluginSenderRejection(
                action = "aidl:applyCepRuleEvent",
                claimedPluginId = resolvedPluginId,
                senderUid = senderUid,
                senderPackages = senderPackages,
                reasonCode = ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH,
            )
            return CepProvider.ApplyResult.Rejected(
                ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH,
                "sender uid=$senderUid does not own pluginId=$resolvedPluginId"
            )
        }

        val sensorKey = event.sensorKey.trim()
        val streamKey = "$resolvedPluginId|$sensorKey"
        val lastSeq = lastCepSequenceByStream[streamKey]
        if (lastSeq != null && event.sequence <= lastSeq) {
            return CepProvider.ApplyResult.NoOp
        }

        val merged = try {
            val existing = runBlocking { app.repo.getCepPluginOverlaysOnce()[resolvedPluginId] }
            when (event.operation) {
                CepRuleOperation.ADD, CepRuleOperation.UPDATE -> {
                    val rulesJson = event.rulesJson
                        ?: return CepProvider.ApplyResult.Rejected(
                            ReasonCodes.MALFORMED_JSON,
                            "rulesJson required for add/update"
                        )
                    val now = System.currentTimeMillis()
                    if (!PluginOverlaySubmissionLimiter.tryAcquire(resolvedPluginId, now)) {
                        return CepProvider.ApplyResult.Rejected(
                            ReasonCodes.PLUGIN_OVERLAY_RATE_LIMITED,
                            "rate limited"
                        )
                    }
                    PluginCepRulesMerge.mergeRegister(
                        pluginId = resolvedPluginId,
                        sensorKey = sensorKey,
                        rulesJson = JSONObject(rulesJson),
                        existingRaw = existing,
                    )
                }
                CepRuleOperation.REMOVE -> {
                    PluginCepRulesMerge.mergeUnregister(
                        pluginId = resolvedPluginId,
                        sensorKey = sensorKey,
                        existingRaw = existing,
                    )
                }
                else -> return CepProvider.ApplyResult.Rejected(
                    ReasonCodes.UNSUPPORTED_OPERATOR,
                    "unsupported CEP stream operation=${event.operation}"
                )
            }
        } catch (e: Exception) {
            return CepProvider.ApplyResult.Rejected(
                ReasonCodes.MALFORMED_JSON,
                e.message ?: "merge failed"
            )
        }

        val outcome = runBlocking { CepProvider.applyPluginOverlay(merged, senderUid, senderPackages) }
        if (outcome is CepProvider.ApplyResult.Applied || outcome is CepProvider.ApplyResult.NoOp) {
            lastCepSequenceByStream[streamKey] = event.sequence
        }
        return outcome
    }

    private fun recordSenderRejection(
        @Suppress("UNUSED_PARAMETER") context: Context,
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
        @Suppress("UNUSED_PARAMETER") context: Context,
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
        private const val TAG = "CEP-PluginIngress"

        fun sensorTopic(pluginId: String, sensorKey: String): String =
            if (sensorKey.startsWith("$pluginId.")) sensorKey else "$pluginId.$sensorKey"

        fun firstNumericFromValues(values: android.os.Bundle): Double {
            if (values.containsKey("value")) return values.getDouble("value")
            if (values.containsKey("valueDouble")) return values.getDouble("valueDouble")
            if (values.containsKey("v")) return values.getDouble("v")
            val key = values.keySet().firstOrNull() ?: return 0.0
            return when (val o = values.get(key)) {
                is Double -> o
                is Float -> o.toDouble()
                is Int -> o.toDouble()
                is Long -> o.toDouble()
                else -> 0.0
            }
        }
    }
}
