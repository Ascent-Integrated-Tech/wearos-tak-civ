/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.provider

import android.os.Build
import android.location.Location as AndroidLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.maplibre.compose.location.Location
import org.maplibre.compose.location.LocationProvider
import org.maplibre.spatialk.geojson.Position
import kotlin.time.TimeSource

class RepoLocationProvider(
    repoFlow: StateFlow<AndroidLocation?>,
    scope: CoroutineScope
) : LocationProvider {

    override val location: StateFlow<Location?> =
        repoFlow
            .map { it?.toMapLibreLocationOrNull() }
            .stateIn(scope, SharingStarted.Eagerly, null)
}

internal fun AndroidLocation.toMapLibreLocationOrNull(): Location? {
    val latitude = latitude
    val longitude = longitude
    if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
        !longitude.isFinite() || longitude !in -180.0..180.0
    ) {
        return null
    }

    if (!hasAccuracy()) return null
    val accuracy = accuracy.toDouble()
    if (!accuracy.isFinite() || accuracy <= 0.0) return null

    val speed = speed.takeIf { hasSpeed() && it.isFinite() && it >= 0f }?.toDouble()
    val bearing = bearing.takeIf { hasBearing() && it.isFinite() }?.toDouble()
    val bearingAccuracy = bearingAccuracyDegrees
        .takeIf { hasBearingAccuracy() && it.isFinite() && it >= 0f }
        ?.toDouble()
    val speedAccuracy = speedAccuracyMetersPerSecond
        .takeIf { hasSpeedAccuracy() && it.isFinite() && it >= 0f }
        ?.toDouble()

    return Location(
        position = Position(longitude, latitude),
        accuracy = accuracy,
        bearing = bearing,
        speed = speed,
        bearingAccuracy = bearingAccuracy,
        speedAccuracy = speedAccuracy,
        timestamp = TimeSource.Monotonic.markNow()
    )
}
