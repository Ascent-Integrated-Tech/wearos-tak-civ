package com.aitech.weartak_civ.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitech.weartak_civ.DEFAULT_ENABLE_BLOODHOUND_COMPASS_SERVICE
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DataRepository.BloodhoundUiCommand
import com.aitech.weartak_civ.presentation.MainActivity.Constants.bhRepo
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBloodhoundCompassService
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BloodhoundViewmodel : ViewModel() {
    val getLicense: StateFlow<String> =
        dataRepository.getLicense.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "License"
        )

    val listBhItemsUi: StateFlow<List<Utils.BloodhoundItem>> =
        bhRepo.listBhItemsUi.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val savedEnableBloodhoundCompassService: StateFlow<Boolean> =
        dataRepository.getEnableBloodhoundCompassService.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DEFAULT_ENABLE_BLOODHOUND_COMPASS_SERVICE
        )

    val bloodhoundUiCommands: SharedFlow<BloodhoundUiCommand> =
        dataRepository.bloodhoundUiCommands

    fun requestInPosition(uid: String) {
        viewModelScope.launch {
            bhRepo.requestInPosition(uid)
        }
    }

    fun requestActivateBloodhound(uid: String) {
        viewModelScope.launch {
            bhRepo.requestActivateBloodhound(uid)
        }
    }

    fun clearBloodhoundUnreadCount(reason: String) {
        viewModelScope.launch {
            dataRepository.clearBloodhoundUnreadCount(reason)
        }
    }
}