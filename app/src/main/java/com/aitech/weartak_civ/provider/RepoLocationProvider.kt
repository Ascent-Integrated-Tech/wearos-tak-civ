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
            .map { fix ->
                fix?.let {
                    Location(
                        // MapLibre Compose Position is (lng, lat) in your usage
                        position = Position(it.longitude, it.latitude),
                        accuracy = it.accuracy.toDouble(),
                        bearing = null,
                        speed = if (it.hasSpeed()) it.speed.toDouble() else null,
                        bearingAccuracy =
                            if (fix.hasBearingAccuracy())
                                fix.bearingAccuracyDegrees.toDouble()
                            else null,
                        speedAccuracy = if (fix.hasSpeedAccuracy())
                            fix.speedAccuracyMetersPerSecond.toDouble()
                        else null,
                        timestamp = TimeSource.Monotonic.markNow()
                    )
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)
}
