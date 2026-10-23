package com.aitech.weartak_civ.service.crm

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrmPpgPreprocessorTest {
    @Test
    fun validWindowProducesExactlyFiveHundredScaledSamples() {
        val preprocessor = CrmPpgPreprocessor()
        for (i in 0..500) {
            val t = i * 10L
            val value = 1_000f + (100f * sin(i / 10.0)).toFloat()
            preprocessor.addSample(t, value)
        }

        val result = preprocessor.buildWindow()

        assertTrue(result is CrmWindowResult.Accepted)
        val accepted = result as CrmWindowResult.Accepted
        assertEquals(500, accepted.scaledSamples.size)
        assertTrue(accepted.scaledSamples.all { it in 0f..1f })
        assertEquals(0f, accepted.scaledSamples.minOrNull() ?: -1f, 0.0001f)
        assertEquals(1f, accepted.scaledSamples.maxOrNull() ?: -1f, 0.0001f)
    }

    @Test
    fun flatWindowIsRejected() {
        val preprocessor = CrmPpgPreprocessor()
        for (i in 0..500) {
            preprocessor.addSample(i * 10L, 1234f)
        }

        val result = preprocessor.buildWindow()

        assertTrue(result is CrmWindowResult.Rejected)
        assertEquals("flat_signal", (result as CrmWindowResult.Rejected).reason)
    }

    @Test
    fun sparseWindowIsRejected() {
        val preprocessor = CrmPpgPreprocessor()
        for (i in 0 until 10) {
            preprocessor.addSample(i * 500L, i.toFloat())
        }

        val result = preprocessor.buildWindow()

        assertTrue(result is CrmWindowResult.Rejected)
        assertEquals("insufficient_samples", (result as CrmWindowResult.Rejected).reason)
    }
}
