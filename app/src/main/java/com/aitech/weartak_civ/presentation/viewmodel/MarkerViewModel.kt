/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.provider.LocationVectorCalculator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MarkerViewModel : ViewModel() {
    data class MarkerVectorUiState(
        val distance: Double?,
        val orientation: Float,
        val callsign: String,
        val timeReceived: String
    )

    private val utcTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC)

    val droppedMarkerListSetting: StateFlow<List<DataRepository.DroppedMarker>> =
        dataRepository.droppedMarkerList

    val licenseKey: StateFlow<String> =
        dataRepository.getLicense.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "License"
        )

    val anyEndpointConnected: StateFlow<Boolean> = dataRepository.anyEndpointConnected.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val callsign: StateFlow<String> = dataRepository.getCallsign.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val location = dataRepository.latestLocation.value

    fun markerVectorUi(index: Int): StateFlow<MarkerVectorUiState?> =
        combine(
            droppedMarkerListSetting,
            dataRepository.latestLocation,
            dataRepository.azimuthTrueDegSmoothed,
            callsign
        ) { markers, location, azimuth, callsign ->
            val marker = markers.getOrNull(index) ?: return@combine null
            val markerLocation = marker.location ?: return@combine null
            val currentLocation = location ?: return@combine null

            val vector = LocationVectorCalculator.calculate(
                currentLocation.latitude,
                currentLocation.longitude,
                markerLocation.lat,
                markerLocation.lon
            )
            val orientation = LocationVectorCalculator.needleAngle(vector.bearing, azimuth)
                ?: vector.bearing
                ?: 0.0

            MarkerVectorUiState(
                distance = vector.distance,
                orientation = orientation.toFloat(),
                callsign = callsign,
                timeReceived = utcTimeFormatter.format(Instant.ofEpochMilli(marker.timeDropped))
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    suspend fun requestUpdateMarkerLocation(uid: String) {
        dataRepository.requestUpdateMarkerLocation(uid)
    }

    fun requestChangeMarkerTitle(uid: String, title: String) {
        dataRepository.requestChangeMarkerTitle(uid, title)
    }

    fun requestChangeMarkerRemark(uid: String, remark: String) {
        dataRepository.requestChangeMarkerRemark(uid, remark)
    }

    fun requestChangeMarkerType(uid: String, type: DataRepository.MarkerType){
        dataRepository.requestChangeMarkerType(uid, type)
    }

    suspend fun requestClearAllMarkers() {
        dataRepository.requestClearAll()
    }

    suspend fun requestClearSpecificMarker(uid: String) {
        dataRepository.requestClearSpecific(uid)
    }

    suspend fun requestClearLast() {
        dataRepository.requestClearLast()
    }

    suspend fun requestMarkerDrop(markerTypeRaw: String, title: String, remark: String, timeMillis: Long) {
        dataRepository.requestMarkerDrop(markerTypeRaw, title, remark, timeMillis)
    }
}
