package com.aitech.weartak_civ.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import com.aitech.weartak_civ.data.model.TakServerChannels
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.service.TAKConnectService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class MapViewModel : ViewModel() {

    val savedTakServerList: StateFlow<List<TakServerItem>> =
        dataRepository.getTakServerList.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    val takChannels: StateFlow<List<TakServerChannels>> = dataRepository.takChannels

    fun refreshTakChannels(serverId: String) {
        TAKConnectService.refreshTakChannels(serverId)
    }

    fun updateTakChannel (serverId: String, channelBitPosition: Int, shouldBeActive: Boolean) {
        TAKConnectService.updateTakChannel(serverId, channelBitPosition, shouldBeActive)
    }
}
