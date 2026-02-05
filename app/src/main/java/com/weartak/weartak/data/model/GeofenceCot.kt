/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.weartak.weartak.data.model

data class GeofenceCot(
    val uid: String,
    val type: String,
    val callsign: String,
    val points: List<LatLon>,
    val tracking: Boolean,
    val trigger: GeofenceTrigger
)

enum class GeofenceTrigger {
    Entry, Exit, Both
}