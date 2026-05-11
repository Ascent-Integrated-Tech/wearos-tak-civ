package com.aitech.weartak_civ.cep.di

import android.content.Context
import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.CepEngine
import com.aitech.weartak_civ.cep.engine.config.CanonicalJson
import com.aitech.weartak_civ.cep.engine.config.CepDiagnosticRecord
import com.aitech.weartak_civ.cep.engine.config.CepConfigRegistry
import com.aitech.weartak_civ.cep.engine.config.ManagedConfigOverlayParser
import com.aitech.weartak_civ.cep.engine.config.ParsedManagedOverlay
import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.aitech.weartak_civ.cep.engine.config.SourceLayer
import com.aitech.weartak_civ.cep.engine.config.ValidationResult
import com.aitech.weartak_civ.cep.engine.lifecycle.CepEngineHolder

/**
 * Holder-mediated apply: validate → (optional no-op) → raw persist → build → warm-up →
 * swap → cancel-old → applied persist. Reset may force a swap even when the definitional
 * effective hash is unchanged (runtime sustain + engine instance must re-bind).
 */
internal class CepManagedApplyExecutor(
    private val registry: CepConfigRegistry,
    private val persistence: CepApplyPersistence,
    private val holder: CepEngineHolder,
    private val appCtx: Context,
    private val buildEngine: (Context, CepConfig) -> CepEngine,
    private val diagnostics: CepApplyDiagnostics,
    private val clearAlertsForRemovedRules: (Set<String>, String) -> Unit,
) {

    suspend fun execute(
        ticket: Long,
        rawPayload: String?,
        effectiveBefore: String,
        isSuperseded: () -> Boolean,
    ): CepProvider.ApplyResult {
        val parsed: ParsedManagedOverlay? = if (rawPayload == null) null
        else try {
            ManagedConfigOverlayParser.parse(rawPayload, registry.configBounds)
        } catch (e: ParserException) {
            diagnostics.recordRejection(
                source = SourceLayer.MANAGED_CONFIG,
                reasonCode = e.reasonCode,
                schemaVersion = null,
                payloadHash = CanonicalJson.hashRaw(rawPayload),
                effectiveBefore = effectiveBefore,
                effectiveAfter = effectiveBefore,
                fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
                tenant = null, issuer = null,
                message = e.message
            )
            return CepProvider.ApplyResult.Rejected(e.reasonCode, e.message ?: "")
        }

        val incomingEpoch = parsed?.cepResetEpoch ?: 0L
        val pendingReset =
            incomingEpoch > 0L && persistence.shouldConsumeCepResetEpoch(incomingEpoch)

        val composed = registry.compose(parsed)
        val resolved = when (composed) {
            is ValidationResult.Ok -> composed.resolved
            is ValidationResult.Err -> {
                diagnostics.recordRejection(
                    source = SourceLayer.MANAGED_CONFIG,
                    reasonCode = composed.reasonCode,
                    schemaVersion = parsed?.schemaVersion,
                    payloadHash = parsed?.payloadHash,
                    effectiveBefore = effectiveBefore,
                    effectiveAfter = effectiveBefore,
                    fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
                    tenant = parsed?.tenant, issuer = parsed?.issuer,
                    message = composed.message
                )
                return CepProvider.ApplyResult.Rejected(composed.reasonCode, composed.message)
            }
        }

        for (info in resolved.infoDiagnostics) {
            diagnostics.recordInfo(
                source = SourceLayer.MANAGED_CONFIG,
                reasonCode = info,
                schemaVersion = parsed?.schemaVersion,
                payloadHash = parsed?.payloadHash,
                effectiveBefore = effectiveBefore,
                effectiveAfter = resolved.effectiveHash,
                tenant = parsed?.tenant, issuer = parsed?.issuer
            )
        }

        // No-op only when definitional surface unchanged AND no pending reset. A newer
        // cepResetEpoch still commits reset side-effects (sustain clear, mirror, swap) even
        // if the effective hash matches the prior authoritative engine.
        val definitionalNoOp = registry.isNoOpAgainst(effectiveBefore, resolved)
        if (definitionalNoOp && !pendingReset) {
            diagnostics.recordInfo(
                source = SourceLayer.MANAGED_CONFIG,
                reasonCode = ReasonCodes.NOOP_RECOMPOSE,
                schemaVersion = parsed?.schemaVersion,
                payloadHash = parsed?.payloadHash,
                effectiveBefore = effectiveBefore,
                effectiveAfter = effectiveBefore,
                tenant = parsed?.tenant, issuer = parsed?.issuer
            )
            persistence.storeCepConfigurationRawJson(rawPayload ?: "")
            return CepProvider.ApplyResult.NoOp
        }

        if (isSuperseded()) return CepProvider.ApplyResult.Superseded

        persistence.storeCepConfigurationRawJson(rawPayload ?: "")

        val previousEngine = holder.current()
        val newEngine = buildEngine(appCtx, resolved.engineConfig)
        previousEngine?.let { newEngine.adoptWindowIngestionFrom(it) }

        val sustainSnapshot =
            if (pendingReset) persistence.getCepSustainOverridesOnce() else null
        try {
            if (pendingReset) persistence.clearCepSustainOverridesStorage()
            newEngine.warmUp()
        } catch (t: Throwable) {
            newEngine.cancel()
            if (sustainSnapshot != null) {
                persistence.storeCepSustainOverridesDual(sustainSnapshot.main, sustainSnapshot.warn)
            }
            diagnostics.recordRejection(
                source = SourceLayer.MANAGED_CONFIG,
                reasonCode = "warmup_failed",
                schemaVersion = parsed?.schemaVersion,
                payloadHash = parsed?.payloadHash,
                effectiveBefore = effectiveBefore,
                effectiveAfter = effectiveBefore,
                fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
                tenant = parsed?.tenant, issuer = parsed?.issuer,
                message = t.message
            )
            return CepProvider.ApplyResult.Rejected("warmup_failed", t.message ?: "")
        }

        if (isSuperseded()) {
            newEngine.cancel()
            if (pendingReset) {
                val s = requireNotNull(sustainSnapshot)
                persistence.storeCepSustainOverridesDual(s.main, s.warn)
            }
            return CepProvider.ApplyResult.Superseded
        }

        val previousRules = holder.current()?.ruleIds.orEmpty()
        val previous = holder.swap(newEngine)

        newEngine.start()

        previous?.cancel()
        val survivingRules = newEngine.ruleIds
        val removedRules = previousRules - survivingRules
        clearAlertsForRemovedRules(removedRules, "policy_recompose")

        val now = System.currentTimeMillis()
        persistence.storeCepConfigurationApplied(resolved.effectiveHash, now)
        if (pendingReset) persistence.advanceCepResetEpochMirror(incomingEpoch)

        return CepProvider.ApplyResult.Applied(resolved.effectiveHash, now)
    }
}
