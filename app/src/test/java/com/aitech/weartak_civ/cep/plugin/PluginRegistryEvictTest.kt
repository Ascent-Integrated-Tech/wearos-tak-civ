package com.aitech.weartak_civ.cep.plugin

import android.app.Application
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Covers the lease-expiry eviction path added for design §539–550 / 2026-05-11 review
 * recommendation 6. [PluginRegistry.evictExpired] is invoked lazily before recompose and
 * must drop both the in-memory parsed overlay and its persisted raw + lease entries.
 */
@RunWith(RobolectricTestRunner::class)
class PluginRegistryEvictTest {

    private val rawOverlay = """
        {
          "schemaVersion":1,
          "pluginId":"com.acme.plugin",
          "leaseSec":60,
          "features":[],"rules":[]
        }
    """.trimIndent()

    private val liveRawOverlay = """
        {
          "schemaVersion":1,
          "pluginId":"com.acme.live",
          "leaseSec":600,
          "features":[],"rules":[]
        }
    """.trimIndent()

    private fun mockRepo(
        initialOverlays: Map<String, String>,
        initialLeases: Map<String, Long>,
    ): DataRepository {
        val repo = mockk<DataRepository>(relaxed = true)
        val overlayState = initialOverlays.toMutableMap()
        val leaseState = initialLeases.toMutableMap()
        coEvery { repo.getCepPluginOverlaysOnce() } answers { overlayState.toMap() }
        coEvery { repo.getCepPluginLeaseUntilMsOnce() } answers { leaseState.toMap() }
        coEvery { repo.getCepPluginCertLogOnce() } returns emptyMap()
        val newOverlays = slot<Map<String, String>>()
        coEvery { repo.storeCepPluginOverlays(capture(newOverlays)) } answers {
            overlayState.clear(); overlayState.putAll(newOverlays.captured)
        }
        val newLeases = slot<Map<String, Long>>()
        coEvery { repo.storeCepPluginLeaseUntilMs(capture(newLeases)) } answers {
            leaseState.clear(); leaseState.putAll(newLeases.captured)
        }
        return repo
    }

    @Test
    fun evictExpiredDropsPluginsPastLeaseAndPersistsSurvivors() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val now = 10_000_000L
        val repo = mockRepo(
            initialOverlays = emptyMap(),
            initialLeases = emptyMap()
        )
        val trust = mockk<PluginTrust>(relaxed = true)
        coEvery { trust.enforce(any(), any(), any()) } returns Unit
        val registry = PluginRegistry(repo, trust)
        registry.clock = { now }
        registry.bootstrap()

        registry.upsertFromRaw(
            raw = rawOverlay,
            senderUid = 11050,
            senderPackages = setOf("com.acme.plugin")
        )
        registry.upsertFromRaw(
            raw = liveRawOverlay,
            senderUid = 11051,
            senderPackages = setOf("com.acme.live")
        )
        registry.clock = { now + 120_000L }

        val evicted = registry.evictExpired()
        assertEquals(setOf("com.acme.plugin"), evicted)
        val survivors = registry.knownPluginIds()
        assertTrue("expired must be gone", "com.acme.plugin" !in survivors)
        assertTrue("active must survive", "com.acme.live" in survivors)
        coVerify(atLeast = 1) { repo.storeCepPluginOverlays(any()) }
        coVerify(atLeast = 1) { repo.storeCepPluginLeaseUntilMs(any()) }
    }

    @Test
    fun evictExpiredReturnsEmptyWhenNothingExpired() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val now = 10_000_000L
        val repo = mockRepo(
            initialOverlays = mapOf("com.acme.plugin" to rawOverlay),
            initialLeases = mapOf("com.acme.plugin" to now + 60_000L)
        )
        val registry = PluginRegistry(repo, PluginTrust(app, repo))
        registry.clock = { now }
        registry.bootstrap()

        assertTrue(registry.evictExpired().isEmpty())
    }

    @Test
    fun bootstrapEmitsDiagnosticForMalformedPersistedOverlay() = runBlocking {
        val repo = mockRepo(
            initialOverlays = mapOf(
                "com.acme.bad" to "{not-json}",
                "com.acme.good" to liveRawOverlay
            ),
            initialLeases = emptyMap()
        )
        val trust = mockk<PluginTrust>(relaxed = true)
        val failures = mutableListOf<Pair<String, String>>()
        val registry = PluginRegistry(
            repo = repo,
            trust = trust,
            onBootstrapParseFailure = { _, reasonCode, message ->
                failures += reasonCode to message
            }
        )
        registry.clock = { 10_000_000L }

        registry.bootstrap()

        assertEquals(1, failures.size)
        assertEquals(ReasonCodes.MALFORMED_JSON, failures.single().first)
        assertTrue("malformed overlay should not survive bootstrap", "com.acme.bad" !in registry.knownPluginIds())
        assertTrue("valid overlay should survive bootstrap", "com.acme.good" in registry.knownPluginIds())
    }
}
