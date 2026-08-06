package com.aitech.weartak_civ.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.metric.MetricInspectionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BaselineTrendsUiState {
    data object Loading : BaselineTrendsUiState
    data class Ready(
        val metrics: List<MetricInspectionSnapshot>,
        val recalculating: Boolean = false,
        val lastRefreshMs: Long? = null,
        val statusMessageRes: Int? = null,
        val exporting: Boolean = false,
        val resetting: Boolean = false,
        val exportedFilePath: String? = null
    ) : BaselineTrendsUiState
    data class Unavailable(val messageRes: Int) : BaselineTrendsUiState
    data class Error(val messageRes: Int, val metrics: List<MetricInspectionSnapshot> = emptyList()) : BaselineTrendsUiState
}

class BaselineTrendsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<BaselineTrendsUiState>(BaselineTrendsUiState.Loading)
    val uiState: StateFlow<BaselineTrendsUiState> = _uiState.asStateFlow()

    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            loadMetrics()
        }
    }

    fun recalculate() {
        val current = _uiState.value as? BaselineTrendsUiState.Ready ?: return
        if (current.recalculating) return
        _uiState.value = current.copy(recalculating = true, statusMessageRes = null)
        viewModelScope.launch {
            try {
                val metrics = withContext(Dispatchers.Default) {
                    if (CepProvider.currentEngine() == null) {
                        null
                    } else {
                        CepProvider.recalculateAllBaselineMetrics()
                    }
                }
                if (metrics == null) {
                    _uiState.value = BaselineTrendsUiState.Unavailable(R.string.metric_engine_unavailable)
                } else {
                    _uiState.value = BaselineTrendsUiState.Ready(
                        metrics = metrics.values.toDisplayOrder(),
                        lastRefreshMs = System.currentTimeMillis(),
                        statusMessageRes = R.string.baseline_trends_recalculated
                    )
                }
            } catch (error: Exception) {
                _uiState.value = BaselineTrendsUiState.Error(
                    messageRes = R.string.unable_recalculate_trends,
                    metrics = current.metrics
                )
            }
        }
    }

    fun export() {
        val current = _uiState.value as? BaselineTrendsUiState.Ready ?: return
        if (current.recalculating || current.exporting || current.resetting) return
        _uiState.value = current.copy(exporting = true, statusMessageRes = null, exportedFilePath = null)
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    if (CepProvider.currentEngine() == null) null
                    else CepProvider.exportBaselineMetrics()
                }
                _uiState.value = if (file == null) {
                    BaselineTrendsUiState.Error(R.string.metric_engine_unavailable, current.metrics)
                } else {
                    current.copy(
                        exporting = false,
                        statusMessageRes = R.string.metric_files_exported,
                        exportedFilePath = file.absolutePath
                    )
                }
            } catch (error: Exception) {
                _uiState.value = BaselineTrendsUiState.Error(
                    R.string.unable_export_metric_files,
                    current.metrics
                )
            }
        }
    }

    fun reset() {
        val current = _uiState.value as? BaselineTrendsUiState.Ready ?: return
        if (current.recalculating || current.exporting || current.resetting) return
        _uiState.value = current.copy(resetting = true, statusMessageRes = null)
        viewModelScope.launch {
            try {
                val reset = withContext(Dispatchers.Default) {
                    CepProvider.resetBaselineMetricsForUserChange()
                }
                if (!reset) {
                    _uiState.value = BaselineTrendsUiState.Error(
                        R.string.metric_engine_unavailable,
                        current.metrics
                    )
                } else {
                    val metrics = withContext(Dispatchers.Default) {
                        CepProvider.inspectBaselineMetrics()
                    }
                    _uiState.value = BaselineTrendsUiState.Ready(
                        metrics = metrics.values.toDisplayOrder(),
                        resetting = false,
                        statusMessageRes = R.string.user_metrics_reset
                    )
                }
            } catch (error: Exception) {
                _uiState.value = BaselineTrendsUiState.Error(
                    R.string.unable_reset_user_metrics,
                    current.metrics
                )
            }
        }
    }

    private suspend fun loadMetrics() {
        try {
            val metrics = withContext(Dispatchers.Default) {
                if (CepProvider.currentEngine() == null) null
                else CepProvider.inspectBaselineMetrics()
            }
            _uiState.value = if (metrics == null) {
                BaselineTrendsUiState.Unavailable(R.string.metric_engine_unavailable)
            } else {
                BaselineTrendsUiState.Ready(metrics.values.toDisplayOrder())
            }
        } catch (error: Exception) {
            _uiState.value = BaselineTrendsUiState.Error(
                R.string.unable_read_baseline_trends
            )
        }
    }

    private fun Collection<MetricInspectionSnapshot>.toDisplayOrder(): List<MetricInspectionSnapshot> {
        val order = listOf(
            "hr.resting_bpm.resting",
            "hr.active_bpm",
            "hrv.rmssd_ms",
            "skin_temp.peripheral_median_c"
        )
        return sortedBy { metric -> order.indexOf(metric.metricId).let { if (it < 0) Int.MAX_VALUE else it } }
    }
}
