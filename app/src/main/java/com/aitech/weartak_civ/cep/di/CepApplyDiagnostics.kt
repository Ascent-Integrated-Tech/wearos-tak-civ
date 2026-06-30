package com.aitech.weartak_civ.cep.di

import com.aitech.weartak_civ.cep.engine.config.SourceLayer

internal interface CepApplyDiagnostics {
    fun recordRejection(context: CepDiagnosticContext)

    fun recordInfo(context: CepDiagnosticContext)
}

internal data class CepDiagnosticContext(
    val source: SourceLayer,
    val reasonCode: String,
    val envelope: CepDiagnosticEnvelope = CepDiagnosticEnvelope(),
    val outcome: CepDiagnosticOutcome = CepDiagnosticOutcome(),
)

internal data class CepDiagnosticEnvelope(
    val schemaVersion: Int? = null,
    val payloadHash: String? = null,
    val tenant: String? = null,
    val issuer: String? = null,
    val plugin: CepDiagnosticPlugin = CepDiagnosticPlugin(),
)

internal data class CepDiagnosticPlugin(
    val pluginId: String? = null,
    val pluginVersion: String? = null,
)

internal data class CepDiagnosticOutcome(
    val effectiveBefore: String? = null,
    val effectiveAfter: String? = null,
    val fallbackDecision: String? = null,
    val message: String? = null,
)
