package com.aitech.weartak_civ.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.cep.metric.BaselineV2Runtime
import com.aitech.weartak_civ.cep.metric.BaselineV2TrendItem
import com.aitech.weartak_civ.cep.metric.BaselineV2TrendsAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BaselineTrendsV2UiState {
    data object Loading : BaselineTrendsV2UiState

    data class Ready(
        val metrics: List<BaselineV2TrendItem> = emptyList(),
        val recalculating: Boolean = false,
        val resetting: Boolean = false,
        val exporting: Boolean = false,
        val exportedSnapshotText: String? = null,
        val statusMessageRes: Int? = null
    ) : BaselineTrendsV2UiState

    data class Error(
        val messageRes: Int,
        val metrics: List<BaselineV2TrendItem> = emptyList()
    ) : BaselineTrendsV2UiState
}

class BaselineTrendsV2ViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<BaselineTrendsV2UiState>(BaselineTrendsV2UiState.Loading)
    val uiState: StateFlow<BaselineTrendsV2UiState> = _uiState.asStateFlow()

    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true

        _uiState.value = BaselineTrendsV2UiState.Ready(currentMetrics())

        viewModelScope.launch {
            BaselineV2Runtime.snapshotStore.snapshots.collect { batch ->
                val current = _uiState.value as? BaselineTrendsV2UiState.Ready ?: return@collect
                _uiState.value = current.copy(
                    metrics = BaselineV2TrendsAdapter.toDisplayItems(batch)
                )
            }
        }
    }

    fun recalculate() {
        val current = _uiState.value as? BaselineTrendsV2UiState.Ready ?: return
        if (current.recalculating || current.resetting) return

        _uiState.value = current.copy(
            recalculating = true,
            statusMessageRes = null
        )
        viewModelScope.launch {
            runCatching { BaselineV2Runtime.recalculateRestingHr() }
                .onSuccess {
                    _uiState.value = BaselineTrendsV2UiState.Ready(
                        metrics = currentMetrics(),
                        statusMessageRes = R.string.baseline_v2_recalculated
                    )
                }
                .onFailure {
                    _uiState.value = BaselineTrendsV2UiState.Error(
                        messageRes = R.string.unable_recalculate_v2,
                        metrics = currentMetrics()
                    )
                }
        }
    }

    fun reset() {
        val current = _uiState.value as? BaselineTrendsV2UiState.Ready ?: return
        if (current.recalculating || current.resetting) return

        _uiState.value = current.copy(
            resetting = true,
            statusMessageRes = null
        )
        viewModelScope.launch {
            runCatching { BaselineV2Runtime.resetRestingHr() }
                .onSuccess {
                    _uiState.value = BaselineTrendsV2UiState.Ready(
                        resetting = false,
                        statusMessageRes = R.string.baseline_v2_reset
                    )
                }
                .onFailure {
                    _uiState.value = BaselineTrendsV2UiState.Error(
                        messageRes = R.string.unable_reset_v2,
                        metrics = currentMetrics()
                    )
                }
        }
    }

    fun export() {
        val current = _uiState.value as? BaselineTrendsV2UiState.Ready ?: return
        if (current.recalculating || current.resetting || current.exporting) return
        _uiState.value = current.copy(exporting = true, exportedSnapshotText = null)
        viewModelScope.launch {
            runCatching { BaselineV2Runtime.snapshotExportText() }
                .onSuccess { text ->
                    _uiState.value = BaselineTrendsV2UiState.Ready(
                        metrics = currentMetrics(),
                        statusMessageRes = R.string.baseline_v2_exported,
                        exportedSnapshotText = text
                    )
                }
                .onFailure {
                    _uiState.value = BaselineTrendsV2UiState.Error(
                        messageRes = R.string.unable_export_v2,
                        metrics = currentMetrics()
                    )
                }
        }
    }

    private fun currentMetrics(): List<BaselineV2TrendItem> =
        BaselineV2TrendsAdapter.toDisplayItems(BaselineV2Runtime.snapshotStore.current())
}
