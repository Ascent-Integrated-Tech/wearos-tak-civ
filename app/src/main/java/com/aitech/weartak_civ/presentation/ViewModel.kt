package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ViewModel (application: Application) :
    AndroidViewModel(application) {
    private val _stringValue = MutableStateFlow<String?>(null)
    val stringValue: StateFlow<String?> = _stringValue

    init {
        getString()
    }

    private fun getString() {
        viewModelScope.launch {
            DataRepository.getStringPreferences(
                getApplication(),
                DataRepository.STRING_KEY
            ).collect { valueString ->
                _stringValue.value = valueString
            }
        }
    }

    fun saveString(value: String) {
        viewModelScope.launch {
            DataRepository.saveStringPreferences(
                getApplication(),
                DataRepository.STRING_KEY,
                value
            )
        }
    }

}