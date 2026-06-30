package com.aitech.weartak_civ.cep.plugin

import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginOverlayParserTest {

    @Test
    fun parsesValidOverlay() {
        val raw = """
            {
              "schemaVersion":1,
              "pluginId":"com.acme.plugin",
              "leaseSec":60,
              "features":[{"type":"window","id":"com.acme.plugin.features.temp","source":"ambient.temp_c","op":"avg","window_sec":30}],
              "rules":[]
            }
        """.trimIndent()
        val parsed = PluginOverlayParser.parse(raw)
        assertEquals("com.acme.plugin", parsed.pluginId)
        assertEquals(1, parsed.contribution.features.size)
    }

    @Test
    fun rejectsEngineCadenceInPluginOverlay() {
        val raw = """
            {
              "schemaVersion":1,
              "pluginId":"com.acme.plugin",
              "leaseSec":60,
              "engine":{"window_tick_ms":1000},
              "features":[],"rules":[]
            }
        """.trimIndent()
        val ex = runCatching { PluginOverlayParser.parse(raw) }.exceptionOrNull()
        assertTrue(ex is ParserException)
        assertEquals(ReasonCodes.PLUGIN_ENGINE_NOT_ALLOWED, (ex as ParserException).reasonCode)
    }

    @Test
    fun rejectsDuplicateFeatureIdsInsidePluginPayload() {
        val raw = """
            {
              "schemaVersion":1,
              "pluginId":"com.acme.plugin",
              "leaseSec":60,
              "features":[
                {"type":"window","id":"com.acme.plugin.features.temp","source":"ambient.temp_c","op":"avg","window_sec":30},
                {"type":"window","id":"com.acme.plugin.features.temp","source":"ambient.temp_c","op":"min","window_sec":30}
              ],
              "rules":[]
            }
        """.trimIndent()
        val ex = runCatching { PluginOverlayParser.parse(raw) }.exceptionOrNull()
        assertTrue(ex is ParserException)
        assertEquals(ReasonCodes.PLUGIN_PAYLOAD_DUPLICATE_ID, (ex as ParserException).reasonCode)
    }

    @Test
    fun rejectsDuplicateRuleIdsInsidePluginPayload() {
        val raw = """
            {
              "schemaVersion":1,
              "pluginId":"com.acme.plugin",
              "leaseSec":60,
              "features":[],
              "rules":[
                {"id":"com.acme.plugin.rules.r1","when":{"is":true},"action":{"type":"alert","severity":"INFO","message":"m"}},
                {"id":"com.acme.plugin.rules.r1","when":{"is":true},"action":{"type":"alert","severity":"WARN","message":"m"}}
              ]
            }
        """.trimIndent()
        val ex = runCatching { PluginOverlayParser.parse(raw) }.exceptionOrNull()
        assertTrue(ex is ParserException)
        assertEquals(ReasonCodes.PLUGIN_PAYLOAD_DUPLICATE_ID, (ex as ParserException).reasonCode)
    }

    @Test
    fun parsesPluginVersionWhenProvided() {
        val raw = """
            {
              "schemaVersion":1,
              "pluginId":"com.acme.plugin",
              "pluginVersion":"2.3.4",
              "leaseSec":60,
              "features":[],"rules":[]
            }
        """.trimIndent()
        val parsed = PluginOverlayParser.parse(raw)
        assertEquals("2.3.4", parsed.pluginVersion)
    }
}
