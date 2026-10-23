package com.aitech.weartak_civ.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import com.aitech.weartak_civ.data.model.TakServerChannels
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.service.TAKConnectService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MapViewModel : ViewModel() {

    val savedTakServerList: StateFlow<List<TakServerItem>> =
        dataRepository.getTakServerList.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    val takChannels: StateFlow<List<TakServerChannels>> = dataRepository.takChannels

    val networkSettingsLocked: StateFlow<Boolean> =
        dataRepository.networkSettingsLocked.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    fun refreshTakChannels(serverId: String) {
        viewModelScope.launch {
            if (dataRepository.networkSettingsLocked.first()) {
                Log.w("MapViewModel", "Channel refresh blocked: network settings are locked")
                return@launch
            }
            TAKConnectService.refreshTakChannels(serverId)
        }
    }

    fun updateTakChannel(serverId: String, channelBitPosition: Int, shouldBeActive: Boolean) {
        viewModelScope.launch {
            if (dataRepository.networkSettingsLocked.first()) {
                Log.w("MapViewModel", "Channel update blocked: network settings are locked")
                return@launch
            }
            TAKConnectService.updateTakChannel(serverId, channelBitPosition, shouldBeActive)
        }
    }
}
