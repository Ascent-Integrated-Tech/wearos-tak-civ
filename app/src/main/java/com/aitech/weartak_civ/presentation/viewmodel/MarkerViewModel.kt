package com.aitech.weartak_civ.presentation.viewmodel

import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.sitx.SitxServiceEvent
import com.aitech.weartak_civ.service.sitx.SitxServiceEventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update


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