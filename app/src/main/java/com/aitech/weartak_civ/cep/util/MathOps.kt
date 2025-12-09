package com.aitech.weartak_civ.cep.util
import kotlin.math.sqrt

object MathOps {
    fun lsqSlope(series: DoubleArray): Double? {
        val n = series.size
        if (n < 2) return null
        var sumX = 0.0; var sumY = 0.0; var sumXY = 0.0; var sumXX = 0.0
        for (i in 0 until n) {
            val x = i.toDouble()
            val y = series[i]
            sumX += x; sumY += y; sumXY += x*y; sumXX += x*x
        }
        val denom = n * sumXX - sumX * sumX
        if (denom == 0.0) return null
        return (n * sumXY - sumX * sumY) / denom
    }
    fun rms(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val meanSq = values.sumOf { it * it } / values.size
        return sqrt(meanSq)
    }
}


