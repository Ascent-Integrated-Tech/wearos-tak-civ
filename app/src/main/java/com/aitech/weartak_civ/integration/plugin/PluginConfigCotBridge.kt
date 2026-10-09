package com.aitech.weartak_civ.integration.plugin

import android.util.Log
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Routes inbound configuration CoT to whichever plugin declares an interest in it.
 *
 * Some plugins need configuration that arrives over the TAK network rather than from the watch UI —
 * the Meshtastic plugin, for example, is shipped its channel/LoRa config by the ATAK-side plugin as
 * a `t-x-meshcfg` event so a whole team can join one mesh from a single push. The plugin contract
 * has no way to express that on its own: [com.weartak.plugin.sdk.aidl.IWearTakHostCallback] is
 * plugin-to-host in every direction, so a plugin can never see CoT the host received.
 *
 * Rather than teach the contract about CoT, this maps a recognised event onto the plugin's own
 * settings schema and hands it to [PluginSettingsCoordinator.pushValueToPluginsDeclaring]. A plugin
 * opts in by declaring the field; one that doesn't is never contacted, and the value is validated
 * against the plugin's declared schema like any other setting.
 *
 * Adding another mapping is a single [ConfigCotMapping] entry.
 */
internal object PluginConfigCotBridge {

    /**
     * A CoT shape this host knows how to hand to a plugin.
     *
     * @param cotType the event `type` attribute to match.
     * @param detailTag the child of `<detail>` carrying the payload.
     * @param payloadAttribute the attribute on [detailTag] holding the value.
     * @param settingsFieldId the plugin settings field the value is delivered as.
     */
    data class ConfigCotMapping(
        val cotType: String,
        val detailTag: String,
        val payloadAttribute: String,
        val settingsFieldId: String,
    )

    private val MAPPINGS = listOf(
        // Meshtastic share link (channel name + PSK + LoRa region/preset/slot), as broadcast by the
        // ATAK Meshtastic plugin's AtakCotConfig.
        ConfigCotMapping(
            cotType = "t-x-meshcfg",
            detailTag = "__meshtastic_cfg",
            payloadAttribute = "url",
            settingsFieldId = "configShareUrl",
        ),
    )

    /**
     * Cheap pre-filter for the inbound socket loop, so the common case costs a substring scan
     * rather than a DOM parse. A false positive is harmless — [handle] re-checks properly.
     */
    fun isConfigCot(rawXml: String): Boolean = MAPPINGS.any { rawXml.contains(it.cotType) }

    /**
     * Parse [rawXml] and deliver its payload to any interested plugin.
     *
     * @return true if the event matched a mapping and at least one plugin accepted it.
     */
    fun handle(rawXml: String, coordinator: PluginSettingsCoordinator): Boolean {
        if (rawXml.contains("<!DOCTYPE", ignoreCase = true) ||
            rawXml.contains("<!ENTITY", ignoreCase = true)) {
            Log.w(TAG, "Rejecting configuration CoT with DTD/entity declarations")
            return false
        }
        val root = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw SAXException("External entities are not permitted") }
            builder
                .parse(InputSource(StringReader(rawXml)))
                .documentElement
        } catch (_: SAXException) {
            Log.w(TAG, "Configuration CoT contains invalid XML")
            return false
        } catch (_: IOException) {
            Log.w(TAG, "Configuration CoT could not be read")
            return false
        }

        if (root.tagName != "event") return false
        val type = root.getAttribute("type").orEmpty()
        val mapping = MAPPINGS.firstOrNull { it.cotType == type } ?: return false

        val detailElement = (0 until root.childNodes.length)
            .mapNotNull { root.childNodes.item(it) as? Element }
            .firstOrNull { it.tagName == "detail" }
        val detail = detailElement?.let { parent ->
            (0 until parent.childNodes.length)
                .mapNotNull { parent.childNodes.item(it) as? Element }
                .firstOrNull { it.tagName == mapping.detailTag }
        }
            ?: run {
                Log.w(TAG, "config CoT type=$type has no <${mapping.detailTag}>")
                return false
            }

        val payload = detail.getAttribute(mapping.payloadAttribute).orEmpty()
        if (payload.isBlank()) {
            Log.w(TAG, "config CoT type=$type has empty ${mapping.payloadAttribute}")
            return false
        }

        // Diagnostics only — the payload itself is a shared secret (the channel PSK is inside the
        // Meshtastic share link), so it is never logged.
        val from = detail.getAttribute("from").ifBlank { "unknown" }
        val channel = detail.getAttribute("ch").ifBlank { "?" }

        val accepted = coordinator.pushValueToPluginsDeclaring(mapping.settingsFieldId, payload)
        if (accepted.isEmpty()) {
            Log.i(TAG, "config CoT type=$type from='$from' ch='$channel' — no plugin declares ${mapping.settingsFieldId}")
            return false
        }
        Log.i(TAG, "config CoT type=$type from='$from' ch='$channel' -> ${accepted.joinToString()}")
        return true
    }

    private const val TAG = "CEP-PluginCfgCot"
}
