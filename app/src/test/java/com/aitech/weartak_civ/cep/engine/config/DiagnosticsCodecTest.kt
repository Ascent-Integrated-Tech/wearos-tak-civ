package com.aitech.weartak_civ.cep.engine.config

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsCodecTest {

    @Test fun roundTripPreservesAllFields() {
        val rec = CepDiagnosticRecord(
            timestamp = 12345L,
            sourceLayer = SourceLayer.MANAGED_CONFIG,
            reasonCode = ReasonCodes.SCHEMA_MISMATCH,
            schemaVersion = 1,
            payloadHash = "abc",
            effectiveHashBefore = "before",
            effectiveHashAfter = "after",
            fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
            tenant = "acme", issuer = "policy-bot",
            message = "bad schema"
        )
        val encoded = DiagnosticsCodec.encode(listOf(rec))
        val decoded = DiagnosticsCodec.decode(encoded)
        assertEquals(1, decoded.size)
        val r = decoded[0]
        assertEquals(rec.timestamp, r.timestamp)
        assertEquals(rec.sourceLayer, r.sourceLayer)
        assertEquals(rec.reasonCode, r.reasonCode)
        assertEquals(rec.schemaVersion, r.schemaVersion)
        assertEquals(rec.payloadHash, r.payloadHash)
        assertEquals(rec.effectiveHashBefore, r.effectiveHashBefore)
        assertEquals(rec.effectiveHashAfter, r.effectiveHashAfter)
        assertEquals(rec.fallbackDecision, r.fallbackDecision)
        assertEquals(rec.tenant, r.tenant)
        assertEquals(rec.issuer, r.issuer)
        assertEquals(rec.message, r.message)
    }

    @Test fun decodeOfEmptyOrBlankIsEmpty() {
        assertEquals(0, DiagnosticsCodec.decode(null).size)
        assertEquals(0, DiagnosticsCodec.decode("").size)
        assertEquals(0, DiagnosticsCodec.decode("   ").size)
    }

    @Test fun decodeOfMalformedIsEmpty() {
        assertEquals(0, DiagnosticsCodec.decode("{not json").size)
    }

    @Test fun ringEvictsOldestRecords() {
        val sink = InMemoryDiagnosticSink(capacity = 3)
        repeat(5) {
            sink.record(
                CepDiagnosticRecord(
                    timestamp = it.toLong(), sourceLayer = SourceLayer.MANAGED_CONFIG,
                    reasonCode = "x"
                )
            )
        }
        val recent = sink.recent()
        assertEquals(3, recent.size)
        // Newest-last; oldest dropped.
        assertEquals(2L, recent.first().timestamp)
        assertEquals(4L, recent.last().timestamp)
    }
}
