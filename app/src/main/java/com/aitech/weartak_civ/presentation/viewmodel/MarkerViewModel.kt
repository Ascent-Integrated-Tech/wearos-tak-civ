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
import kotlinx.coroutines.flow.StateFlow
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn


class MarkerViewModel : ViewModel() {
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
    val multicastRunning: StateFlow<Boolean> = dataRepository.multicastPublisherRunning.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )
    val location = dataRepository.latestLocation.value


    suspend fun requestUpdateMarkerLocation(uid: String) {
        dataRepository.requestUpdateMarkerLocation(uid)
    }
     fun requestChangeMarkerTitle(uid: String, title: String){
        dataRepository.requestChangeMarkerTitle(uid, title)
    }
    suspend fun requestClearAllMarkers(){
        dataRepository.requestClearAll()
    }
    suspend fun requestClearSpecificMarker(uid: String){
        dataRepository.requestClearSpecific(uid)
    }
    suspend fun requestClearLast(){
        dataRepository.requestClearLast()
    }
    suspend fun requestMarkerDrop(markerTypeRaw: String, title: String, timeMillis: Long){
        dataRepository.requestMarkerDrop(markerTypeRaw, title, timeMillis)
    }
}