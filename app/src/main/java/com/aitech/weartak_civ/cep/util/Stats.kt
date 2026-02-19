package com.aitech.weartak_civ.cep.util

object Stats {
    fun mean(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.average()
    fun std(values: List<Double>): Double? {
        if (values.size < 2) return null
        val m = values.average()
        val varSum = values.sumOf { (it - m) * (it - m) }
        return kotlin.math.sqrt(varSum / (values.size - 1))
    }
    fun zscore(values: List<Double>): Double? {
        val last = values.lastOrNull() ?: return null
        val m = mean(values) ?: return null
        val s = std(values) ?: return null
        if (s == 0.0) return 0.0
        return (last - m) / s
    }
}


