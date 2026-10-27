package com.aitech.weartak_civ.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.Locale

class SelfStatusRemarksTest {
    private val now = Instant.parse("2026-10-27T03:17:19Z").toEpochMilli()
    private val sample = SelfStatusPhysio(79, now, 91.1f, now - 1000, .155, now - 2000)
    private fun remarks(status: MoppStatus? = null, physio: SelfStatusPhysio? = sample) =
        SelfStatusSnapshot(status?.let { SavedMoppStatus(it, null) }, physio).remarksLine(now)

    @Test fun allMoppLevelsNoneAndUnsetMatchCompanionTokensAndOrdering() {
        val metrics = "HR: 79 bpm @ 03:17:19Z; Skin: 91.1F @ 03:17:18Z; Exert: 16% @ 03:17:17Z"
        for (status in MoppStatus.entries) {
            val prefix = if (status == MoppStatus.NONE) "" else "MOPP${status.key}"
            assertEquals(if (prefix.isEmpty()) metrics else "$prefix; $metrics", remarks(status))
            assertEquals(prefix, remarks(status, null))
        }
        assertEquals(metrics, remarks())
        assertEquals("", remarks(physio = null))
        assertEquals("", remarks(MoppStatus.NONE, null))
    }

    @Test fun missingOrInvalidMetricsAreOmittedIndependentlyWithoutPlaceholders() {
        for (hr in listOf(null, -1, 0, 19, 251)) {
            assertEquals("Skin: 91.1F @ 03:17:18Z; Exert: 16% @ 03:17:17Z",
                remarks(physio = sample.copy(hr = hr)))
        }
        for (skin in listOf(null, -1f, 0f, 58.9f, 113.1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals("HR: 79 bpm @ 03:17:19Z; Exert: 16% @ 03:17:17Z",
                remarks(physio = sample.copy(sktF = skin)))
        }
        for (exert in listOf(null, -.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals("HR: 79 bpm @ 03:17:19Z; Skin: 91.1F @ 03:17:18Z",
                remarks(physio = sample.copy(exert = exert)))
        }
        assertEquals("MOPP0", remarks(MoppStatus.LEVEL_0, SelfStatusPhysio()))
    }

    @Test fun unitsRoundingAndDecimalPointMatchJavaCompanionRegardlessOfLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            for ((fraction, percent) in listOf(0.0 to 0, .1549 to 15, .155 to 16, .995 to 100, 1.0 to 100)) {
                assertEquals("HR: 79 bpm @ 03:17:19Z; Skin: 91.3F @ 03:17:18Z; Exert: $percent% @ 03:17:17Z",
                    remarks(physio = sample.copy(sktF = 91.25f, exert = fraction)))
            }
            assertEquals("Skin: 91.0F @ 03:17:18Z", remarks(physio = sample.copy(hr = null, sktF = 91f, exert = null)))
            for (hr in listOf(20, 250)) {
                assertEquals("HR: $hr bpm @ 03:17:19Z", remarks(physio = sample.copy(hr = hr, sktF = null, exert = null)))
            }
            for (skin in listOf(59f, 113f)) {
                assertEquals("Skin: ${skin.toInt()}.0F @ 03:17:18Z",
                    remarks(physio = sample.copy(hr = null, sktF = skin, exert = null)))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun clocksUseActualIndependentSamplesAndCompanionFreshnessBoundaries() {
        for (time in listOf(null, 0L, now - 120_000L, now - 120_001L, now + 30_001L)) {
            assertEquals("MOPP4", remarks(MoppStatus.LEVEL_4,
                sample.copy(measuredAt = time, sktFMeasuredAt = time, exertMeasuredAt = time)))
        }
        assertEquals("HR: 79 bpm @ 03:15:19Z",
            remarks(physio = sample.copy(measuredAt = now - 119_999, sktF = null, exert = null)))
        assertEquals("HR: 79 bpm @ 03:17:49Z",
            remarks(physio = sample.copy(measuredAt = now + 30_000, sktF = null, exert = null)))
    }
}
