/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.ui.formatter

import kotlin.math.pow

object DistanceFormatter {
    private const val DISTANCE_UNIT_THRESHOLD_KM = 1.0
    private const val METERS_PER_KILOMETER = 1000.0
    private const val KILOMETER_DECIMALS = 2

    data class DisplayDistance(
        val value: String,
        val unit: Unit
    ) {
        enum class Unit {
            METERS,
            KILOMETERS
        }

        fun text(metersLabel: String, kilometersLabel: String): String {
            val unitLabel = when (unit) {
                Unit.METERS -> metersLabel
                Unit.KILOMETERS -> kilometersLabel
            }
            return value + unitLabel
        }
    }

    fun format(distanceKm: Double?): DisplayDistance {
        val safeDistanceKm = (distanceKm ?: 0.0).coerceAtLeast(0.0)
        return if (safeDistanceKm < DISTANCE_UNIT_THRESHOLD_KM) {
            DisplayDistance(
                value = (safeDistanceKm * METERS_PER_KILOMETER).toInt().toString(),
                unit = DisplayDistance.Unit.METERS
            )
        } else {
            DisplayDistance(
                value = safeDistanceKm.round(KILOMETER_DECIMALS).toString(),
                unit = DisplayDistance.Unit.KILOMETERS
            )
        }
    }

    private fun Double.round(decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return kotlin.math.round(this * factor) / factor
    }
}