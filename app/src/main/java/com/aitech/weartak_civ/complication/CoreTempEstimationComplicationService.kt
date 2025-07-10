package com.aitech.weartak_civ.complication

class CoreTempEstimationComplicationService(initialCT: Double = 37.0) {

    private val a = 1.0
    private val gamma2 = 0.022 * 0.022
    private val b0 = -7887.1
    private val b1 = 384.4286
    private val b2 = -4.5714
    private val sigma2 = 18.88 * 18.88

    private var ct = initialCT
    private var variance = 0.0

    fun estimate(hrSeries: List<Double>): List<Double> {
        val result = mutableListOf<Double>()

        for (hr in hrSeries) {
            val ctPred = a * ct
            val varPred = a * a * variance + gamma2
            val c = 2 * b2 * ctPred + b1
            val k = (varPred * c) / (c * c * varPred + sigma2)
            val hrExpected = b2 * ctPred * ctPred + b1 * ctPred + b0
            ct = ctPred + k * (hr - hrExpected)
            variance = (1 - k * c) * varPred
            result.add(ct)
        }

        return result
    }

    fun getCurrentCT(): Double = ct
}
