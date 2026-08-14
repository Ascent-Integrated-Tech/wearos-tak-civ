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

        val parsed = when (val result = parseOverlay(rawPayload, source, effectiveBefore, pluginId, pluginVersion, attemptHash, now)) {
            is ParseOutcome.Success -> result.parsed
            is ParseOutcome.Rejected -> return result.result
        }

        val incomingEpoch = parsed?.cepResetEpoch ?: 0L
        val pendingReset = incomingEpoch > 0L && persistence.shouldConsumeCepResetEpoch(incomingEpoch)

        val resolved = when (val result = composeOverlay(parsed, source, effectiveBefore, pluginId, pluginVersion, attemptHash, now)) {
            is ComposeOutcome.Success -> result.resolved
            is ComposeOutcome.Rejected -> return result.result
        }

        recordResolvedDiagnostics(source, parsed, resolved, effectiveBefore, pluginId, pluginVersion)

        val definitionalNoOp = registry.isNoOpAgainst(effectiveBefore, resolved.resolved)
        if (definitionalNoOp && !pendingReset) {
            return handleNoOp(rawPayload, source, parsed, effectiveBefore, pluginId, pluginVersion)
        }

        return applyResolvedOverlay(
            ApplyPhaseContext(
                source = source,
                resolved = resolved,
                parsed = parsed,
                rawPayload = rawPayload,
                effectiveBefore = effectiveBefore,
                incomingEpoch = incomingEpoch,
                pendingReset = pendingReset,
                attemptHash = attemptHash,
                now = now,
                isSuperseded = isSuperseded,
                pluginId = pluginId,
                pluginVersion = pluginVersion,
            )
        )
    }

    private data class ApplyPhaseContext(
        val source: SourceLayer,
        val resolved: ValidationResult.Ok,
        val parsed: ParsedManagedOverlay?,
        val rawPayload: String?,
        val effectiveBefore: String,
        val incomingEpoch: Long,
        val pendingReset: Boolean,
        val attemptHash: String?,
        val now: Long,
        val isSuperseded: () -> Boolean,
        val pluginId: String?,
        val pluginVersion: String?,
    )

    private sealed class ParseOutcome {
        data class Success(val parsed: ParsedManagedOverlay?) : ParseOutcome()
        data class Rejected(val result: CepProvider.ApplyResult.Rejected) : ParseOutcome()
    }

    private sealed class ComposeOutcome {
        data class Success(val resolved: ValidationResult.Ok) : ComposeOutcome()
        data class Rejected(val result: CepProvider.ApplyResult.Rejected) : ComposeOutcome()
    }

    private suspend fun parseOverlay(
        rawPayload: String?,
        source: SourceLayer,
        effectiveBefore: String,
        pluginId: String?,
        pluginVersion: String?,
        attemptHash: String?,
        now: Long,
    ): ParseOutcome {
        if (rawPayload == null) return ParseOutcome.Success(null)
        return try {
            ParseOutcome.Success(ManagedConfigOverlayParser.parse(rawPayload, registry.configBounds))
        } catch (e: ParserException) {
            diagnostics.recordRejection(
                CepDiagnosticContext(
                    source = source,
                    reasonCode = e.reasonCode,
                    envelope = CepDiagnosticEnvelope(
                        payloadHash = CanonicalJson.hashRaw(rawPayload),
                        plugin = CepDiagnosticPlugin(
                            pluginId = pluginId,
                            pluginVersion = pluginVersion,
                        ),
                    ),
                    outcome = CepDiagnosticOutcome(
                        effectiveBefore = effectiveBefore,
                        effectiveAfter = effectiveBefore,
                        fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
                        message = e.message,
                    )
                )
            )
            recordManagedFailure(source, attemptHash, now)
            ParseOutcome.Rejected(CepProvider.ApplyResult.Rejected(e.reasonCode, e.message ?: ""))
        }
    }

    private suspend fun composeOverlay(
        parsed: ParsedManagedOverlay?,
        source: SourceLayer,
        effectiveBefore: String,
        pluginId: String?,
        pluginVersion: String?,
        attemptHash: String?,
        now: Long,
    ): ComposeOutcome {
        return when (val composed = registry.compose(parsed, pluginContributionsProvider())) {
            is ValidationResult.Ok -> ComposeOutcome.Success(composed)
            is ValidationResult.Err -> {
                diagnostics.recordRejection(
                    CepDiagnosticContext(
                        source = source,
                        reasonCode = composed.reasonCode,
                        envelope = CepDiagnosticEnvelope(
                            schemaVersion = parsed?.schemaVersion,
                            payloadHash = parsed?.payloadHash,
                            tenant = parsed?.tenant,
                            issuer = parsed?.issuer,
                            plugin = CepDiagnosticPlugin(
                                pluginId = pluginId,
                                pluginVersion = pluginVersion,
                            ),
                        ),
                        outcome = CepDiagnosticOutcome(
                            effectiveBefore = effectiveBefore,
                            effectiveAfter = effectiveBefore,
                            fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
                            message = composed.message,
                        )
                    )
                )
                recordManagedFailure(source, parsed?.payloadHash ?: attemptHash, now)
                ComposeOutcome.Rejected(CepProvider.ApplyResult.Rejected(composed.reasonCode, composed.message))
            }
        }
    }

    private fun recordResolvedDiagnostics(
        source: SourceLayer,
        parsed: ParsedManagedOverlay?,
        resolved: ValidationResult.Ok,
        effectiveBefore: String,
        pluginId: String?,
        pluginVersion: String?,
    ) {
        for (info in resolved.resolved.infoDiagnostics) {
            diagnostics.recordInfo(
                CepDiagnosticContext(
                    source = source,
                    reasonCode = info,
                    envelope = CepDiagnosticEnvelope(
                        schemaVersion = parsed?.schemaVersion,
                        payloadHash = parsed?.payloadHash,
                        tenant = parsed?.tenant,
                        issuer = parsed?.issuer,
                        plugin = CepDiagnosticPlugin(
                            pluginId = pluginId,
                            pluginVersion = pluginVersion,
                        ),
                    ),
                    outcome = CepDiagnosticOutcome(
                        effectiveBefore = effectiveBefore,
                        effectiveAfter = resolved.resolved.effectiveHash,
                    )
                )
            )
        }
        for (col in resolved.resolved.pluginCollisions) {
            val code = when (col.blockedBy) {
                SourceLayer.BUILT_IN -> ReasonCodes.PLUGIN_BLOCKED_BY_BUILTIN
                SourceLayer.MANAGED_CONFIG -> ReasonCodes.PLUGIN_BLOCKED_BY_MDM
                else -> ReasonCodes.PLUGIN_BLOCKED_BY_HIGHER_LAYER
            }
            diagnostics.recordInfo(
                CepDiagnosticContext(
                    source = source,
                    reasonCode = code,
                    envelope = CepDiagnosticEnvelope(
                        schemaVersion = parsed?.schemaVersion,
                        payloadHash = parsed?.payloadHash,
                        tenant = parsed?.tenant,
                        issuer = parsed?.issuer,
                        plugin = CepDiagnosticPlugin(
                            pluginId = col.pluginId.ifEmpty { pluginId.orEmpty() },
                            pluginVersion = pluginVersion,
                        ),
                    ),
                    outcome = CepDiagnosticOutcome(
                        effectiveBefore = effectiveBefore,
                        effectiveAfter = resolved.resolved.effectiveHash,
                    )
                )
            )
        }
    }

    private suspend fun handleNoOp(
        rawPayload: String?,
        source: SourceLayer,
        parsed: ParsedManagedOverlay?,
        effectiveBefore: String,
        pluginId: String?,
        pluginVersion: String?,
    ): CepProvider.ApplyResult {
        diagnostics.recordInfo(
            CepDiagnosticContext(
                source = source,
                reasonCode = ReasonCodes.NOOP_RECOMPOSE,
                envelope = CepDiagnosticEnvelope(
                    schemaVersion = parsed?.schemaVersion,
                    payloadHash = parsed?.payloadHash,
                    tenant = parsed?.tenant,
                    issuer = parsed?.issuer,
                    plugin = CepDiagnosticPlugin(
                        pluginId = pluginId,
                        pluginVersion = pluginVersion,
                    ),
                ),
                outcome = CepDiagnosticOutcome(
                    effectiveBefore = effectiveBefore,
                    effectiveAfter = effectiveBefore,
                )
            )
        )
        if (source == SourceLayer.MANAGED_CONFIG) {
            persistence.storeCepConfigurationRawJson(rawPayload ?: "")
            clearManagedFailureState()
        }
        return CepProvider.ApplyResult.NoOp
    }

    private suspend fun applyResolvedOverlay(context: ApplyPhaseContext): CepProvider.ApplyResult {
        if (context.isSuperseded()) return CepProvider.ApplyResult.Superseded

        if (context.source == SourceLayer.MANAGED_CONFIG) {
            persistence.storeCepConfigurationRawJson(context.rawPayload ?: "")
        }

        val previousEngine = holder.current()
        val newEngine = buildEngine(appCtx, context.resolved.resolved.engineConfig)
        previousEngine?.let { newEngine.adoptWindowIngestionFrom(it) }

        val sustainSnapshot = if (context.pendingReset) persistence.getCepSustainOverridesOnce() else null
        try {
            if (context.pendingReset) persistence.clearCepSustainOverridesStorage()
            newEngine.warmUp()
        } catch (t: Throwable) {
            newEngine.cancelAndJoin()
            if (sustainSnapshot != null) {
                persistence.storeCepSustainOverridesDual(sustainSnapshot.main, sustainSnapshot.warn)
            }
            diagnostics.recordRejection(
                CepDiagnosticContext(
                    source = context.source,
                    reasonCode = "warmup_failed",
                    envelope = CepDiagnosticEnvelope(
                        schemaVersion = context.parsed?.schemaVersion,
                        payloadHash = context.parsed?.payloadHash,
                        tenant = context.parsed?.tenant,
                        issuer = context.parsed?.issuer,
                        plugin = CepDiagnosticPlugin(
                            pluginId = context.pluginId,
                            pluginVersion = context.pluginVersion,
                        ),
                    ),
                    outcome = CepDiagnosticOutcome(
                        effectiveBefore = context.effectiveBefore,
                        effectiveAfter = context.effectiveBefore,
                        fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
                        message = t.message,
                    )
                )
            )
            recordManagedFailure(context.source, context.parsed?.payloadHash ?: context.attemptHash, context.now)
            return CepProvider.ApplyResult.Rejected("warmup_failed", t.message ?: "")
        }

        if (context.isSuperseded()) {
            newEngine.cancelAndJoin()
            if (context.pendingReset && sustainSnapshot != null) {
                persistence.storeCepSustainOverridesDual(sustainSnapshot.main, sustainSnapshot.warn)
            }
            return CepProvider.ApplyResult.Superseded
        }

        val previousRules = holder.current()?.ruleIds.orEmpty()
        val previous = holder.swap(newEngine)
        previous?.cancelAndJoin()
        onBeforeHygiene(previous, newEngine)
        newEngine.applyPressureAlertHygiene()
        newEngine.applyPhysioAlertHygiene()
        newEngine.start()

        val removedRules = previousRules - newEngine.ruleIds
        clearAlertsForRemovedRules(removedRules, "policy_recompose")

        val appliedAt = System.currentTimeMillis()
        persistence.storeCepConfigurationApplied(context.resolved.resolved.effectiveHash, appliedAt)
        if (context.pendingReset) persistence.advanceCepResetEpochMirror(context.incomingEpoch)
        if (context.source == SourceLayer.MANAGED_CONFIG) clearManagedFailureState()

        return CepProvider.ApplyResult.Applied(context.resolved.resolved.effectiveHash, appliedAt)
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
            CepDiagnosticContext(
                source = SourceLayer.MANAGED_CONFIG,
                reasonCode = ReasonCodes.MDM_PAYLOAD_POISON_PILL_HOLDOFF,
                envelope = CepDiagnosticEnvelope(
                    payloadHash = attemptHash,
                    plugin = CepDiagnosticPlugin(
                        pluginId = pluginId,
                        pluginVersion = pluginVersion,
                    ),
                ),
                outcome = CepDiagnosticOutcome(
                    effectiveBefore = effectiveBefore,
                    effectiveAfter = effectiveBefore,
                    fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
                    message = "managed payload in hold-off (failureCount=${prior.failureCount}, firstFailureAtMs=${prior.firstFailureAtMs})",
                )
            )
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
