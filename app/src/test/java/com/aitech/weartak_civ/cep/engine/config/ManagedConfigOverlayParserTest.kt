package com.aitech.weartak_civ.cep.engine.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManagedConfigOverlayParserTest {

    private fun minimal(extra: String = ""): String = """
      {"schemaVersion":1,"cepResetEpoch":0,"rulesMode":"MERGE","featuresMode":"MERGE",
       "clearCepRules":false,"clearCepFeatures":false,"features":[],"rules":[],
       "disabledRuleIds":[],"disabledFeatureIds":[]$extra}
    """.trimIndent()

    @Test fun acceptsMinimalPayload() {
        val parsed = ManagedConfigOverlayParser.parse(minimal())
        assertEquals(1, parsed.schemaVersion)
        assertEquals("MERGE", parsed.rulesMode)
        assertEquals("MERGE", parsed.featuresMode)
        assertFalse(parsed.clearCepRules)
        assertFalse(parsed.clearCepFeatures)
        assertEquals(0L, parsed.cepResetEpoch)
        assertTrue(parsed.payloadHash.isNotEmpty())
        assertNotNull(parsed.rawCanonical)
    }

    @Test fun malformedJsonRejected() {
        try {
            ManagedConfigOverlayParser.parse("not json")
            fail("expected ParserException")
        } catch (e: ParserException) {
            assertEquals(ReasonCodes.MALFORMED_JSON, e.reasonCode)
        }
    }

    @Test fun missingSchemaVersionRejected() {
        try {
            ManagedConfigOverlayParser.parse("""{"rulesMode":"MERGE"}""")
            fail("expected ParserException")
        } catch (e: ParserException) {
            assertEquals(ReasonCodes.SCHEMA_MISMATCH, e.reasonCode)
        }
    }

    @Test fun forwardSchemaVersionRejected() {
        try {
            ManagedConfigOverlayParser.parse(minimal().replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
            fail("expected ParserException")
        } catch (e: ParserException) {
            assertEquals(ReasonCodes.SCHEMA_MISMATCH, e.reasonCode)
        }
    }

    @Test fun unsupportedModeRejected() {
        try {
            ManagedConfigOverlayParser.parse(minimal().replace("\"rulesMode\":\"MERGE\"", "\"rulesMode\":\"PATCH\""))
            fail("expected ParserException")
        } catch (e: ParserException) {
            assertEquals(ReasonCodes.UNSUPPORTED_MODE, e.reasonCode)
        }
    }

    @Test fun patchShapeRejectedExplicitly() {
        // Top-level "patch" key signals PATCH intent
        val patchPayload = """{"schemaVersion":1,"patch":[{"op":"replace","path":"/rules/0","value":{}}]}"""
        try {
            ManagedConfigOverlayParser.parse(patchPayload)
            fail("expected ParserException for PATCH-shape")
        } catch (e: ParserException) {
            assertEquals(ReasonCodes.UNSUPPORTED_PATCH_SHAPE, e.reasonCode)
        }
    }

    @Test fun payloadSizeRejected() {
        val bounds = CepConfigBounds(maxPayloadBytes = 32)
        try {
            ManagedConfigOverlayParser.parse(minimal(), bounds)
            fail("expected ParserException for payload too large")
        } catch (e: ParserException) {
            assertEquals(ReasonCodes.PAYLOAD_TOO_LARGE, e.reasonCode)
        }
    }

    @Test fun clearSupersedesRulesArrayDiagnosticEmitted() {
        val payload = """
          {"schemaVersion":1,"rulesMode":"MERGE","featuresMode":"MERGE",
           "clearCepRules":true,"clearCepFeatures":false,
           "features":[],
           "rules":[{"id":"r1","when":{"is":{"ref":"x"}},"sustained_for_sec":0,"hold_down_sec":0,
                     "action":{"type":"alert","severity":"INFO","message":"m"}}]}
        """.trimIndent()
        val parsed = ManagedConfigOverlayParser.parse(payload)
        assertTrue(parsed.sawClearSupersedesRules)
        assertEquals(0, parsed.rules.size)
    }

    @Test fun clearSupersedesFeaturesArrayDiagnosticEmitted() {
        val payload = """
          {"schemaVersion":1,"rulesMode":"MERGE","featuresMode":"MERGE",
           "clearCepRules":false,"clearCepFeatures":true,
           "features":[{"id":"features.foo","type":"window","source":"src","window_sec":1,"op":"min"}],
           "rules":[]}
        """.trimIndent()
        val parsed = ManagedConfigOverlayParser.parse(payload)
        assertTrue(parsed.sawClearSupersedesFeatures)
        assertEquals(0, parsed.features.size)
    }

    @Test fun disabledIdsParsedAsSet() {
        val payload = """
          {"schemaVersion":1,"rulesMode":"MERGE","featuresMode":"MERGE",
           "clearCepRules":false,"clearCepFeatures":false,
           "features":[],"rules":[],
           "disabledRuleIds":["a","b","a"],"disabledFeatureIds":["features.x"]}
        """.trimIndent()
        val parsed = ManagedConfigOverlayParser.parse(payload)
        assertEquals(setOf("a", "b"), parsed.disabledRuleIds)
        assertEquals(setOf("features.x"), parsed.disabledFeatureIds)
    }

    @Test fun resetEpochIsParsedAndNonNegative() {
        val payload = minimal().replace("\"cepResetEpoch\":0", "\"cepResetEpoch\":42")
        val parsed = ManagedConfigOverlayParser.parse(payload)
        assertEquals(42L, parsed.cepResetEpoch)
    }
}
