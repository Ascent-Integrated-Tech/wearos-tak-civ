package com.aitech.weartak_civ.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Spo2Phase7RunnerInstrumentedTest {

    @Test
    fun reviewedScenarioSet_passesWithoutForegroundServiceBoot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val runner = Spo2Phase7Runner(Spo2Phase7RunnerEnv(applicationContext = context, loggerTag = "Spo2P7AndroidTest"))
        val results = runner.runReviewedScenarios()
        assertTrue("Expected at least one reviewed scenario result", results.isNotEmpty())
        val failures = results.filter {
            !it.pass || it.assertionFailures.isNotEmpty() || it.missing.isNotEmpty() || it.unexpected.isNotEmpty()
        }
        assertTrue("Reviewed SpO2 scenarios failed: $failures", failures.isEmpty())
    }
}
