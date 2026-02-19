package com.aitech.weartak_civ.cep.engine

import kotlin.math.*

object ZoneHelper {

    /**
     * Tanaka HRmax formula:
     *   HRmax = 208 - (0.7 × age)
     */
    fun tanakaHrMax(age: Int): Double {
        return 208.0 - (0.7 * age)
    }

    /**
     * Karvonen heart-rate–reserve (HRR):
     *   HRR = HRmax - HRrest
     */
    fun hrr(hrRest: Double, hrMax: Double): Double {
        return (hrMax - hrRest).coerceAtLeast(1.0)
    }

    /**
     * Karvonen BPM from %HRR (0.0 – 1.0):
     *   BPM = HRrest + HRR * pct
     *
     * Example: for zone 3 lower bound (70%):
     *   pct = 0.70
     */
    fun karvonenMinBpm(hrRest: Double, hrMax: Double, pct: Double): Double {
        val reserve = hrMax - hrRest
        return hrRest + reserve * pct
    }

    /**
     * Compute %HRR (0–100).
     *   %HRR = (HR - HRrest) / HRR * 100
     */
    fun percentHrr(currentHr: Double, hrRest: Double, hrMax: Double): Double {
        val reserve = hrr(hrRest, hrMax)
        val pct = (currentHr - hrRest) / reserve * 100.0
        return pct.coerceIn(0.0, 200.0) // supports >HRmax values
    }

    /**
     * Utility: Convert BPM threshold to %HRR for comparison.
     */
    fun bpmToPercentHrr(bpm: Double, hrRest: Double, hrMax: Double): Double {
        val reserve = hrr(hrRest, hrMax)
        val pct = (bpm - hrRest) / reserve * 100.0
        return pct.coerceIn(0.0, 200.0)
    }
}
