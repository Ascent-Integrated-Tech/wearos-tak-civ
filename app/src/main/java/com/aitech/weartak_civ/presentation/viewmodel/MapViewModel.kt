package com.aitech.weartak_civ.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitech.weartak_civ.data.DataRepository.TakChannel
import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.service.TAKConnectService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MapViewModel : ViewModel() {

    val savedTakServerList: StateFlow<List<TakServerItem>> =
        dataRepository.getTakServerList.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    val takChannels: StateFlow<List<TakServerChannels>> =
        dataRepository.takChannels
            .map { channelMap ->

                channelMap
                    .entries
                    .groupBy { entry ->
                        entry.key.serverUid
                    }
                    .map { (serverUid, entries) ->
                        TakServerChannels(
                            serverUid = serverUid,
                            channels = entries.map { it.value },
                            serverName = "temp"
                        )
                    }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    data class TakServerChannels(
        val serverUid: String,
        val channels: List<TakChannel>,
        val serverName: String
    )

    fun updateTakChannel (serverId: String, channelBitPosition: Int, shouldBeActive: Boolean) {
        viewModelScope.launch {
            TAKConnectService.updateTakChannel(serverId, channelBitPosition, shouldBeActive)
        }
    }
}