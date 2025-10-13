package com.aitech.weartak_civ.cep.engine

object ZoneHelper {
    fun karvonenMinBpm(hrRest: Double, hrMax: Double, lowerPct: Double): Double {
        val reserve = hrMax - hrRest
        return hrRest + reserve * lowerPct
    }
}


