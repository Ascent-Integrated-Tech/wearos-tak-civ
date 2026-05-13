package com.aitech.weartak_civ.cep.di

import com.aitech.weartak_civ.cep.engine.config.SourceLayer

internal interface CepApplyDiagnostics {
    fun recordRejection(
        source: SourceLayer,
        reasonCode: String,
        schemaVersion: Int?,
        payloadHash: String?,
        effectiveBefore: String?,
        effectiveAfter: String?,
        fallbackDecision: String?,
        tenant: String?,
        issuer: String?,
        message: String?,
        pluginId: String? = null,
        pluginVersion: String? = null,
    )

    fun recordInfo(
        source: SourceLayer,
        reasonCode: String,
        schemaVersion: Int?,
        payloadHash: String?,
        effectiveBefore: String?,
        effectiveAfter: String?,
        tenant: String?,
        issuer: String?,
        pluginId: String? = null,
        pluginVersion: String? = null,
    )
}
