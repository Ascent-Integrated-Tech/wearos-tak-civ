package com.aitech.weartak_civ.integration.plugin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the CoT-to-plugin-settings bridge: the parse, the mapping, and — importantly — that an
 * event nothing claims is reported as unhandled so the caller keeps feeding it to the normal
 * parsers.
 */
@RunWith(RobolectricTestRunner::class) // the bridge logs via android.util.Log
class PluginConfigCotBridgeTest {

    private val shareUrl =
        "https://meshtastic.org/e/#CgMSAQEyAggBOgIIAQ"

    private fun meshCfgCot(
        url: String = shareUrl,
        from: String = "ALPHA-1",
        channel: String = "TeamNet",
    ): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="meshcfg.abc123" type="t-x-meshcfg" how="h-g-i-g-o"
               time="2026-08-26T12:00:00Z" start="2026-08-26T12:00:00Z" stale="2026-08-26T12:01:00Z">
          <point lat="34.6" lon="-98.4" hae="0.0" ce="9999999" le="9999999"/>
          <detail>
            <__meshtastic_cfg url="$url" ch="$channel" from="$from" senderUid="abc123"/>
          </detail>
        </event>
    """.trimIndent()

    @Test
    fun `pre-filter matches the config type and rejects ordinary cot`() {
        assertTrue(PluginConfigCotBridge.isConfigCot(meshCfgCot()))
        assertFalse(
            PluginConfigCotBridge.isConfigCot(
                """<event uid="X" type="a-f-G-U-C"><detail><contact callsign="BRAVO"/></detail></event>""",
            ),
        )
    }

    @Test
    fun `share link is delivered to the declared settings field`() {
        val coordinator = mockk<PluginSettingsCoordinator>()
        every { coordinator.pushValueToPluginsDeclaring(any(), any()) } returns listOf("com.meshtastic.weartak")

        assertTrue(PluginConfigCotBridge.handle(meshCfgCot(), coordinator))

        verify(exactly = 1) { coordinator.pushValueToPluginsDeclaring("configShareUrl", shareUrl) }
    }

    @Test
    fun `unclaimed event is reported unhandled so it falls through to the normal parsers`() {
        val coordinator = mockk<PluginSettingsCoordinator>()
        every { coordinator.pushValueToPluginsDeclaring(any(), any()) } returns emptyList()

        assertFalse(PluginConfigCotBridge.handle(meshCfgCot(), coordinator))
    }

    @Test
    fun `wrong type is ignored without touching any plugin`() {
        val coordinator = mockk<PluginSettingsCoordinator>()

        val notOurs = meshCfgCot().replace("t-x-meshcfg", "t-x-somethingelse")
        assertFalse(PluginConfigCotBridge.handle(notOurs, coordinator))

        verify(exactly = 0) { coordinator.pushValueToPluginsDeclaring(any(), any()) }
    }

    @Test
    fun `missing detail or empty payload is rejected rather than pushed`() {
        val coordinator = mockk<PluginSettingsCoordinator>()

        val noDetail = meshCfgCot().replace(
            Regex("<__meshtastic_cfg[^>]*/>"),
            "",
        )
        assertFalse(PluginConfigCotBridge.handle(noDetail, coordinator))
        assertFalse(PluginConfigCotBridge.handle(meshCfgCot(url = ""), coordinator))

        verify(exactly = 0) { coordinator.pushValueToPluginsDeclaring(any(), any()) }
    }

    @Test
    fun `entity declarations and payloads outside detail are rejected`() {
        val coordinator = mockk<PluginSettingsCoordinator>()
        val declaration = "<!DOCTYPE event [<!ENTITY external SYSTEM 'file:///not-allowed'>]>"
        assertFalse(PluginConfigCotBridge.handle(declaration + meshCfgCot(), coordinator))
        assertFalse(PluginConfigCotBridge.handle(
            meshCfgCot().replace("<detail>", "").replace("</detail>", ""), coordinator
        ))
        verify(exactly = 0) { coordinator.pushValueToPluginsDeclaring(any(), any()) }
    }

    @Test
    fun `malformed xml does not throw into the socket reader`() {
        val coordinator = mockk<PluginSettingsCoordinator>()

        assertFalse(PluginConfigCotBridge.handle("<event type=\"t-x-meshcfg\"", coordinator))
    }
}
