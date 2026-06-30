package com.aitech.weartak_civ.cep.di

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CepProviderPluginIngestTest {

    private val app = ApplicationProvider.getApplicationContext<WearTakApplication>()
    private val pluginId = app.packageName
    private val topic = "$pluginId.heat_index"

    @Test
    fun acceptedSampleRoutesIntoCurrentEngine() = runBlocking {
        val result = CepProvider.ingestPluginSample(
            pluginId = pluginId,
            topic = topic,
            tsMs = System.currentTimeMillis(),
            value = 42.0,
            senderUid = 11050,
            senderPackages = setOf(pluginId)
        )
        assertEquals(CepProvider.IngestResult.Accepted, result)
        assertTrue(CepProvider.currentEngine() != null)
    }

    @Test
    fun trustMismatchIsDroppedAndDiagnosed() = runBlocking {
        val result = CepProvider.ingestPluginSample(
            pluginId = pluginId,
            topic = topic,
            tsMs = System.currentTimeMillis(),
            value = 7.0,
            senderUid = 11051,
            senderPackages = setOf("com.malicious.spoofer")
        )
        assertEquals(
            CepProvider.IngestResult.Dropped(ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH),
            result
        )

        waitForDiagnostic(ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH)
    }

    private fun waitForDiagnostic(reasonCode: String) {
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            val seen = runCatching { CepProvider.diagnostics().recent() }
                .getOrDefault(emptyList())
                .any { it.reasonCode == reasonCode }
            if (seen) return
            Thread.sleep(50)
        }
        assertTrue("expected diagnostic $reasonCode", false)
    }
}
