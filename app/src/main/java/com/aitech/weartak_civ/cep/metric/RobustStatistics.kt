package com.aitech.weartak_civ.cep.metric

import kotlin.math.sqrt

object RobustStatistics {
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    fun percentile(values: List<Double>, percentile: Double): Double? {
        require(percentile in 0.0..1.0) { "Percentile must be between 0 and 1" }
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val position = percentile * (sorted.lastIndex)
        val lower = position.toInt()
        val upper = kotlin.math.ceil(position).toInt().coerceAtMost(sorted.lastIndex)
        val fraction = position - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }

    fun mad(values: List<Double>, centre: Double? = null): Double? {
        val resolvedCentre = centre ?: median(values) ?: return null
        return median(values.map { kotlin.math.abs(it - resolvedCentre) })
    }

    fun iqr(values: List<Double>): Double? {
        val lower = percentile(values, 0.25) ?: return null
        val upper = percentile(values, 0.75) ?: return null
        return (upper - lower).coerceAtLeast(0.0)
    }

    fun winsorizedStandardDeviation(values: List<Double>, lowerPercentile: Double = 0.05, upperPercentile: Double = 0.95): Double? {
        if (values.isEmpty()) return null
        require(lowerPercentile in 0.0..1.0 && upperPercentile in 0.0..1.0)
        require(lowerPercentile <= upperPercentile)
        val lower = percentile(values, lowerPercentile) ?: return null
        val upper = percentile(values, upperPercentile) ?: return null
        val bounded = values.map { it.coerceIn(lower, upper) }
        val mean = bounded.average()
        return sqrt(bounded.map { (it - mean) * (it - mean) }.average())
    }

    fun robustScale(values: List<Double>, minimumScale: Double): RobustScaleEstimate {
        require(minimumScale > 0.0) { "Minimum scale must be positive" }
        val centre = median(values)
        if (centre == null) return RobustScaleEstimate(null, null, "NO_DATA")
        val madScale = mad(values, centre)?.times(1.4826)?.takeIf { it > 0.0 }
        val iqrScale = iqr(values)?.div(1.349)?.takeIf { it > 0.0 }
        val winsorizedScale = winsorizedStandardDeviation(values)?.takeIf { it > 0.0 }
        val scale = madScale ?: iqrScale ?: winsorizedScale ?: minimumScale
        val method = when {
            madScale != null -> "MAD_1_4826"
            iqrScale != null -> "IQR_DIV_1_349"
            winsorizedScale != null -> "WINSORIZED_STDDEV"
            else -> "CONFIGURED_MINIMUM"
        }
        return RobustScaleEstimate(centre, scale, method)
    }
}

data class RobustScaleEstimate(
    val centre: Double?,
    val scale: Double?,
    val method: String
)
