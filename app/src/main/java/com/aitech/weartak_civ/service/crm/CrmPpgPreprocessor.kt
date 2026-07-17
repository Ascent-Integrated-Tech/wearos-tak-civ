/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service.crm

import android.util.Log
import kotlin.math.max
import kotlin.math.min

class CrmPpgPreprocessor(
    private val windowMs: Long = WINDOW_MS,
    private val targetSamples: Int = TARGET_SAMPLES,
    private val targetHz: Int = TARGET_HZ,
    private val minCoverageMs: Long = MIN_COVERAGE_MS,
    private val maxSampleGapMs: Long = MAX_SAMPLE_GAP_MS,
    private val minRawSamples: Int = MIN_RAW_SAMPLES,
    private val minRawAmplitude: Float = MIN_RAW_AMPLITUDE,
) {
    private val samples = ArrayDeque<RawPpgSample>(targetSamples * 2)
    private val TAG: String = "WT-CrmPpgPreProc"
    private var totalSamples = 0

    fun addSample(uptimeMillis: Long, value: Float) {
        totalSamples++

        if (totalSamples % 100 == 0) {

            Log.d(

                TAG,

                "CRM_BUFFER samplesSeen=$totalSamples bufferSize=${samples.size}"

            )

        }
        if (!value.isFinite()) return
        samples.addLast(RawPpgSample(uptimeMillis, value))
        prune(uptimeMillis)
    }

    fun clear() {
        samples.clear()
    }

    fun buildWindow(): CrmWindowResult {
        if (samples.size < minRawSamples) {
            return rejected("insufficient_samples")
        }

        val latest = samples.last()
        prune(latest.uptimeMillis)
        val windowStart = latest.uptimeMillis - windowMs
        val windowSamples = samples.filter { it.uptimeMillis >= windowStart }
        if (windowSamples.size < minRawSamples) {
            return rejected("insufficient_samples", windowSamples)
        }

        val coverageMs =
            windowSamples.last().uptimeMillis -
                    windowSamples.first().uptimeMillis

        val effectiveHz =
            if (coverageMs > 0)
                (windowSamples.size * 1000f) / coverageMs
            else
                0f

        val maxGap = maxGapMs(windowSamples)

        Log.d(
            TAG,
            "CRM_WINDOW " +
                    "samples=${windowSamples.size} " +
                    "coverageMs=$coverageMs " +
                    "effectiveHz=${"%.1f".format(effectiveHz)} " +
                    "maxGapMs=$maxGap"
        )

        if (coverageMs < minCoverageMs) {
            return rejected("insufficient_coverage", windowSamples)
        }

        if (maxGap > maxSampleGapMs) {
            return rejected("sample_gap_too_large", windowSamples)
        }

        val rawValues = windowSamples.map { it.value }
        if (rawValues.any { !it.isFinite() }) {
            return rejected("non_finite", windowSamples)
        }

        val resampled = resample(windowSamples, targetSamples, targetHz)
        var minValue = Float.POSITIVE_INFINITY
        var maxValue = Float.NEGATIVE_INFINITY
        for (value in resampled) {
            minValue = min(minValue, value)
            maxValue = max(maxValue, value)
        }
        val amplitude = maxValue - minValue
        Log.d(
            TAG,
            "CRM_SIGNAL " +
                    "min=$minValue " +
                    "max=$maxValue " +
                    "amplitude=$amplitude"
        )
        if (!amplitude.isFinite() || amplitude < minRawAmplitude) {
            return rejected("flat_signal", windowSamples)
        }

        val scaled = FloatArray(resampled.size) { index ->
            ((resampled[index] - minValue) / amplitude).coerceIn(0f, 1f)
        }
        val quality = (amplitude / (amplitude + QUALITY_SOFT_CAP)).coerceIn(0f, 1f)
        return CrmWindowResult.Accepted(
            scaledSamples = scaled,
            resampledSamples = resampled,
            sourceSampleCount = windowSamples.size,
            windowDurationMs = coverageMs,
            windowStartUptimeMs = windowSamples.first().uptimeMillis,
            windowEndUptimeMs = windowSamples.last().uptimeMillis,
            maxSampleGapMs = maxGap,
            resampledMin = minValue,
            resampledMax = maxValue,
            resampledAmplitude = amplitude,
            quality = quality,
        )
    }

    private fun prune(nowUptimeMillis: Long) {
        val cutoff = nowUptimeMillis - windowMs
        while (samples.isNotEmpty() && samples.first().uptimeMillis < cutoff) {
            samples.removeFirst()
        }
    }

    private fun rejected(reason: String, source: List<RawPpgSample> = samples.toList()): CrmWindowResult.Rejected {
        val duration = if (source.size >= 2) source.last().uptimeMillis - source.first().uptimeMillis else 0L
        return CrmWindowResult.Rejected(
            reason = reason,
            sourceSampleCount = source.size,
            windowDurationMs = duration
        )
    }

    private fun maxGapMs(source: List<RawPpgSample>): Long {
        var maxGap = 0L
        for (i in 1 until source.size) {
            maxGap = max(maxGap, source[i].uptimeMillis - source[i - 1].uptimeMillis)
        }
        return maxGap
    }

    private fun resample(source: List<RawPpgSample>, targetSamples: Int, targetHz: Int): FloatArray {
        val out = FloatArray(targetSamples)
        val intervalMs = 1000.0 / targetHz.toDouble()
        val end = source.last().uptimeMillis.toDouble()
        val start = end - ((targetSamples - 1) * intervalMs)
        var j = 0
        for (i in 0 until targetSamples) {
            val t = start + (i * intervalMs)
            while (j < source.lastIndex && source[j + 1].uptimeMillis < t) {
                j++
            }
            val left = source[j]
            val right = source[min(j + 1, source.lastIndex)]
            out[i] = if (right.uptimeMillis == left.uptimeMillis) {
                left.value
            } else {
                val frac = ((t - left.uptimeMillis) / (right.uptimeMillis - left.uptimeMillis)).coerceIn(0.0, 1.0)
                (left.value + ((right.value - left.value) * frac)).toFloat()
            }
        }
        return out
    }

    private data class RawPpgSample(
        val uptimeMillis: Long,
        val value: Float
    )

    companion object {
        const val TARGET_HZ = 100
        const val TARGET_SAMPLES = 500
        const val WINDOW_MS = 5_000L
        const val MIN_RAW_SAMPLES = 20
        const val MIN_COVERAGE_MS = 4_500L
        const val MAX_SAMPLE_GAP_MS = 1_000L
        const val MIN_RAW_AMPLITUDE = 1f
        private const val QUALITY_SOFT_CAP = 50f
    }
}

sealed class CrmWindowResult {
    data class Accepted(
        val scaledSamples: FloatArray,
        val resampledSamples: FloatArray,
        val sourceSampleCount: Int,
        val windowDurationMs: Long,
        val windowStartUptimeMs: Long,
        val windowEndUptimeMs: Long,
        val maxSampleGapMs: Long,
        val resampledMin: Float,
        val resampledMax: Float,
        val resampledAmplitude: Float,
        val quality: Float,
    ) : CrmWindowResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Accepted) return false

            return scaledSamples.contentEquals(other.scaledSamples) &&
                    resampledSamples.contentEquals(other.resampledSamples) &&
                    sourceSampleCount == other.sourceSampleCount &&
                    windowDurationMs == other.windowDurationMs &&
                    windowStartUptimeMs == other.windowStartUptimeMs &&
                    windowEndUptimeMs == other.windowEndUptimeMs &&
                    maxSampleGapMs == other.maxSampleGapMs &&
                    resampledMin == other.resampledMin &&
                    resampledMax == other.resampledMax &&
                    resampledAmplitude == other.resampledAmplitude &&
                    quality == other.quality
        }

        override fun hashCode(): Int {
            var result = scaledSamples.contentHashCode()
            result = 31 * result + resampledSamples.contentHashCode()
            result = 31 * result + sourceSampleCount
            result = 31 * result + windowDurationMs.hashCode()
            result = 31 * result + windowStartUptimeMs.hashCode()
            result = 31 * result + windowEndUptimeMs.hashCode()
            result = 31 * result + maxSampleGapMs.hashCode()
            result = 31 * result + resampledMin.hashCode()
            result = 31 * result + resampledMax.hashCode()
            result = 31 * result + resampledAmplitude.hashCode()
            result = 31 * result + quality.hashCode()
            return result
        }
    }

    data class Rejected(
        val reason: String,
        val sourceSampleCount: Int,
        val windowDurationMs: Long
    ) : CrmWindowResult()
}
