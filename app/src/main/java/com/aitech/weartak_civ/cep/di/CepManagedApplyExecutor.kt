package com.aitech.weartak_civ.cep.di

import android.content.Context
import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.CepEngine
import com.aitech.weartak_civ.cep.engine.config.CanonicalJson
import com.aitech.weartak_civ.cep.engine.config.CepConfigMerger
import com.aitech.weartak_civ.cep.engine.config.CepConfigRegistry
import com.aitech.weartak_civ.cep.engine.config.CepDiagnosticRecord
import com.aitech.weartak_civ.cep.engine.config.ManagedConfigOverlayParser
import com.aitech.weartak_civ.cep.engine.config.ParsedManagedOverlay
import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.aitech.weartak_civ.cep.engine.config.SourceLayer
import com.aitech.weartak_civ.cep.engine.config.ValidationResult
import com.aitech.weartak_civ.cep.engine.lifecycle.CepEngineHolder
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.cancelAndJoin

/**
 * Holder-mediated apply: validate -> (optional no-op) -> raw persist -> build -> warm-up ->
 * swap -> cancel-old -> applied persist. Reset may force a swap even when the definitional
 * effective hash is unchanged (runtime sustain + engine instance must re-bind).
 *
 * Also owns the WEAR-509 poison-pill hold-off short-circuit for the managed-config source
 * (design §847–857): three consecutive rejections of the same managed payload hash inside
 * a 6-hour window cause subsequent attempts of that exact hash to be rejected with
 * [ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF] without re-running parse/compose. The
 * streak is keyed by payload hash and persisted via [CepApplyPersistence] so it survives
 * process restart. A different payload hash, a successful apply, or window expiry clears
 * the streak.
 */
internal class CepManagedApplyExecutor(
    private val registry: CepConfigRegistry,
    private val persistence: CepApplyPersistence,
    private val holder: CepEngineHolder,
    private val appCtx: Context,
    private val buildEngine: (Context, CepConfig) -> CepEngine,
    private val diagnostics: CepApplyDiagnostics,
    private val clearAlertsForRemovedRules: (Set<String>, String) -> Unit,
    private val pluginContributionsProvider: suspend () -> List<CepConfigMerger.PluginContribution> = { emptyList() },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onBeforeHygiene: suspend (previous: CepEngine?, newEngine: CepEngine) -> Unit = { _, _ -> },
) {

    companion object {
        /**
         * Streak length that triggers hold-off. The third consecutive rejection of the same
         * managed payload hash within [HOLDOFF_WINDOW_MS] is returned as
         * [ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF] instead of re-running validation.
         */
        const val HOLDOFF_THRESHOLD = 3
        /** Hold-off window per design §853 (6 hours). */
        const val HOLDOFF_WINDOW_MS = 6L * 60L * 60L * 1000L
    }

    suspend fun execute(
        rawPayload: String?,
        effectiveBefore: String,
        isSuperseded: () -> Boolean,
        source: SourceLayer = SourceLayer.MANAGED_CONFIG,
        pluginId: String? = null,
        pluginVersion: String? = null,
    ): CepProvider.ApplyResult {
        val now = nowMs()
        // Stable hash for the inbound payload, computed up-front so we can short-circuit on
        // hold-off before re-running parse/compose. `null` when the payload is missing or
        // not canonicalizable (malformed JSON cannot key a streak; the parse-time rejection
        // path below handles those without touching the streak).
        val attemptHash: String? = rawPayload?.let {
            CanonicalJson.hashRaw(it).takeUnless { h -> h.isEmpty() }
        }

        if (source == SourceLayer.MANAGED_CONFIG && attemptHash != null) {
            val holdoff = checkPoisonPillHoldoff(
                attemptHash = attemptHash,
                now = now,
                effectiveBefore = effectiveBefore,
                pluginId = pluginId,
                pluginVersion = pluginVersion
            )
            if (holdoff != null) return holdoff
        }

        val parsed: ParsedManagedOverlay? = if (rawPayload == null) null
        else try {
            ManagedConfigOverlayParser.parse(rawPayload, registry.configBounds)
        } catch (e: ParserException) {
            diagnostics.recordRejection(
                source = source,
                reasonCode = e.reasonCode,
                schemaVersion = null,
                payloadHash = CanonicalJson.hashRaw(rawPayload),
                effectiveBefore = effectiveBefore,
                effectiveAfter = effectiveBefore,
                fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
                tenant = null,
                issuer = null,
                message = e.message,
                pluginId = pluginId,
                pluginVersion = pluginVersion
            )
            recordManagedFailure(source, attemptHash, now)
            return CepProvider.ApplyResult.Rejected(e.reasonCode, e.message ?: "")
        }

        val incomingEpoch = parsed?.cepResetEpoch ?: 0L
        val pendingReset = incomingEpoch > 0L && persistence.shouldConsumeCepResetEpoch(incomingEpoch)

        val composed = registry.compose(parsed, pluginContributionsProvider())
        val resolved = when (composed) {
            is ValidationResult.Ok -> composed.resolved
            is ValidationResult.Err -> {
                diagnostics.recordRejection(
                    source = source,
                    reasonCode = composed.reasonCode,
                    schemaVersion = parsed?.schemaVersion,
                    payloadHash = parsed?.payloadHash,
                    effectiveBefore = effectiveBefore,
                    effectiveAfter = effectiveBefore,
                    fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
                    tenant = parsed?.tenant,
                    issuer = parsed?.issuer,
                    message = composed.message,
                    pluginId = pluginId,
                    pluginVersion = pluginVersion
                )
                recordManagedFailure(source, parsed?.payloadHash ?: attemptHash, now)
                return CepProvider.ApplyResult.Rejected(composed.reasonCode, composed.message)
            }
        }

        for (info in resolved.infoDiagnostics) {
            diagnostics.recordInfo(
                source = source,
                reasonCode = info,
                schemaVersion = parsed?.schemaVersion,
                payloadHash = parsed?.payloadHash,
                effectiveBefore = effectiveBefore,
                effectiveAfter = resolved.effectiveHash,
                tenant = parsed?.tenant,
                issuer = parsed?.issuer,
                pluginId = pluginId,
                pluginVersion = pluginVersion
            )
        }
        // Emit one structured diagnostic per plugin entry that was shadowed by a higher layer
        // (built-in / managed). Keeps the "structured failure surfacing" half of the U7
        // contract honored for the plugin layer.
        for (col in resolved.pluginCollisions) {
            val code = when (col.blockedBy) {
                SourceLayer.BUILT_IN -> ReasonCodes.PLUGIN_BLOCKED_BY_BUILTIN
                SourceLayer.MANAGED_CONFIG -> ReasonCodes.PLUGIN_BLOCKED_BY_MDM
                else -> ReasonCodes.PLUGIN_BLOCKED_BY_HIGHER_LAYER
            }
            diagnostics.recordInfo(
                source = source,
                reasonCode = code,
                schemaVersion = parsed?.schemaVersion,
                payloadHash = parsed?.payloadHash,
                effectiveBefore = effectiveBefore,
                effectiveAfter = resolved.effectiveHash,
                tenant = parsed?.tenant,
                issuer = parsed?.issuer,
                pluginId = col.pluginId.ifEmpty { pluginId },
                pluginVersion = pluginVersion
            )
        }

        val definitionalNoOp = registry.isNoOpAgainst(effectiveBefore, resolved)
        if (definitionalNoOp && !pendingReset) {
            diagnostics.recordInfo(
                source = source,
                reasonCode = ReasonCodes.NOOP_RECOMPOSE,
                schemaVersion = parsed?.schemaVersion,
                payloadHash = parsed?.payloadHash,
                effectiveBefore = effectiveBefore,
                effectiveAfter = effectiveBefore,
                tenant = parsed?.tenant,
                issuer = parsed?.issuer,
                pluginId = pluginId,
                pluginVersion = pluginVersion
            )
            if (source == SourceLayer.MANAGED_CONFIG) {
                persistence.storeCepConfigurationRawJson(rawPayload ?: "")
                clearManagedFailureState()
            }
            return CepProvider.ApplyResult.NoOp
        }

        if (isSuperseded()) return CepProvider.ApplyResult.Superseded

        if (source == SourceLayer.MANAGED_CONFIG) {
            persistence.storeCepConfigurationRawJson(rawPayload ?: "")
        }

        val previousEngine = holder.current()
        val newEngine = buildEngine(appCtx, resolved.engineConfig)
        previousEngine?.let { newEngine.adoptWindowIngestionFrom(it) }

        val sustainSnapshot = if (pendingReset) persistence.getCepSustainOverridesOnce() else null
        try {
            if (pendingReset) persistence.clearCepSustainOverridesStorage()
            newEngine.warmUp()
        } catch (t: Throwable) {
            newEngine.cancelAndJoin()
            if (sustainSnapshot != null) {
                persistence.storeCepSustainOverridesDual(sustainSnapshot.main, sustainSnapshot.warn)
            }
            diagnostics.recordRejection(
                source = source,
                reasonCode = "warmup_failed",
                schemaVersion = parsed?.schemaVersion,
                payloadHash = parsed?.payloadHash,
                effectiveBefore = effectiveBefore,
                effectiveAfter = effectiveBefore,
                fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
                tenant = parsed?.tenant,
                issuer = parsed?.issuer,
                message = t.message,
                pluginId = pluginId,
                pluginVersion = pluginVersion
            )
            recordManagedFailure(source, parsed?.payloadHash ?: attemptHash, now)
            return CepProvider.ApplyResult.Rejected("warmup_failed", t.message ?: "")
        }

        if (isSuperseded()) {
            newEngine.cancelAndJoin()
            if (pendingReset && sustainSnapshot != null) {
                persistence.storeCepSustainOverridesDual(sustainSnapshot.main, sustainSnapshot.warn)
            }
            return CepProvider.ApplyResult.Superseded
        }

        val previousRules = holder.current()?.ruleIds.orEmpty()
        val previous = holder.swap(newEngine)
        previous?.cancelAndJoin()
        onBeforeHygiene(previous, newEngine)
        newEngine.applyPressureAlertHygiene()
        newEngine.start()

        val removedRules = previousRules - newEngine.ruleIds
        clearAlertsForRemovedRules(removedRules, "policy_recompose")

        val appliedAt = System.currentTimeMillis()
        persistence.storeCepConfigurationApplied(resolved.effectiveHash, appliedAt)
        if (pendingReset) persistence.advanceCepResetEpochMirror(incomingEpoch)
        if (source == SourceLayer.MANAGED_CONFIG) clearManagedFailureState()

        return CepProvider.ApplyResult.Applied(resolved.effectiveHash, appliedAt)
    }

    /**
     * Returns a hold-off rejection when the persisted streak for [attemptHash] has reached
     * [HOLDOFF_THRESHOLD] within [HOLDOFF_WINDOW_MS], otherwise null. The first time the
     * streak crosses into hold-off the counter is incremented so the strike that produced
     * the hold-off is observable in persisted state; once already in hold-off the counter
     * stops growing to avoid unbounded writes.
     */
    private suspend fun checkPoisonPillHoldoff(
        attemptHash: String,
        now: Long,
        effectiveBefore: String,
        pluginId: String?,
        pluginVersion: String?,
    ): CepProvider.ApplyResult.Rejected? {
        val prior = persistence.getManagedPayloadFailureStateOnce() ?: return null
        if (prior.payloadHash != attemptHash) return null
        if (now - prior.firstFailureAtMs >= HOLDOFF_WINDOW_MS) return null
        if (prior.failureCount + 1 < HOLDOFF_THRESHOLD) return null

        if (prior.failureCount < HOLDOFF_THRESHOLD) {
            persistence.storeManagedPayloadFailureState(
                prior.copy(failureCount = prior.failureCount + 1)
            )
        }
        diagnostics.recordRejection(
            source = SourceLayer.MANAGED_CONFIG,
            reasonCode = ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF,
            schemaVersion = null,
            payloadHash = attemptHash,
            effectiveBefore = effectiveBefore,
            effectiveAfter = effectiveBefore,
            fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
            tenant = null,
            issuer = null,
            message = "managed payload in hold-off (failureCount=${prior.failureCount}, firstFailureAtMs=${prior.firstFailureAtMs})",
            pluginId = pluginId,
            pluginVersion = pluginVersion
        )
        return CepProvider.ApplyResult.Rejected(
            ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF,
            "managed payload hold-off active"
        )
    }

    /**
     * Update the persisted rejection streak after a managed-config apply was rejected.
     * No-op for non-managed sources or when no stable payload hash is available (malformed
     * JSON, missing payload). A different hash or an expired window resets the streak;
     * otherwise the count is incremented while keeping `firstFailureAtMs` anchored.
     */
    private suspend fun recordManagedFailure(
        source: SourceLayer,
        attemptHash: String?,
        now: Long,
    ) {
        if (source != SourceLayer.MANAGED_CONFIG) return
        if (attemptHash.isNullOrEmpty()) return
        val prior = persistence.getManagedPayloadFailureStateOnce()
        val next = when {
            prior == null ->
                DataRepository.CepManagedPayloadFailureState(attemptHash, 1, now)
            prior.payloadHash != attemptHash ->
                DataRepository.CepManagedPayloadFailureState(attemptHash, 1, now)
            now - prior.firstFailureAtMs >= HOLDOFF_WINDOW_MS ->
                DataRepository.CepManagedPayloadFailureState(attemptHash, 1, now)
            else ->
                prior.copy(failureCount = prior.failureCount + 1)
        }
        persistence.storeManagedPayloadFailureState(next)
    }

    private suspend fun clearManagedFailureState() {
        persistence.clearManagedPayloadFailureState()
    }
}
