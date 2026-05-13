package com.aitech.weartak_civ.cep.di

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aitech.weartak_civ.WearTakApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CepProviderPluginEvictionTest {

    private val pluginId = ApplicationProvider.getApplicationContext<WearTakApplication>().packageName
    private val ruleId = "$pluginId.rules.overlay_eviction"

    @After
    fun tearDown() = runBlocking {
        runCatching { CepProvider.unregisterPluginOverlay(pluginId) }
        val app = ApplicationProvider.getApplicationContext<WearTakApplication>()
        val leases = app.repo.getCepPluginLeaseUntilMsOnce().toMutableMap()
        leases.remove(pluginId)
        app.repo.storeCepPluginLeaseUntilMs(leases)
    }

    @Test
    fun expiredPluginContributionIsRemovedOnManagedRecompose() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<WearTakApplication>()
        runCatching { CepProvider.unregisterPluginOverlay(pluginId) }

        val overlayJson = """
            {
              "schemaVersion":1,
              "pluginId":"$pluginId",
              "leaseSec":600,
              "features":[],
              "rules":[
                {
                  "id":"$ruleId",
                  "when":{"is":true},
                  "action":{"type":"alert","severity":"INFO","message":"plugin"}
                }
              ]
            }
        """.trimIndent()

        val applied = CepProvider.applyPluginOverlay(
            rawOverlayJson = overlayJson,
            senderUid = 11050,
            senderPackages = setOf(pluginId)
        )
        assertTrue(applied is CepProvider.ApplyResult.Applied)
        assertTrue(CepProvider.currentEngine()?.ruleIds?.contains(ruleId) == true)

        val leases = app.repo.getCepPluginLeaseUntilMsOnce().toMutableMap()
        leases[pluginId] = System.currentTimeMillis() - 1L
        app.repo.storeCepPluginLeaseUntilMs(leases)

        val recomposed = CepProvider.applyManagedOverlay(null)
        assertTrue(recomposed is CepProvider.ApplyResult.Applied || recomposed is CepProvider.ApplyResult.NoOp)
        assertFalse(CepProvider.currentEngine()?.ruleIds?.contains(ruleId) == true)
    }
}
