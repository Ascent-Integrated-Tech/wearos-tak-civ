package com.aitech.weartak_civ.presentation

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.cep.metric.MetricInspectionSnapshot
import com.aitech.weartak_civ.presentation.viewmodel.BaselineTrendsUiState
import com.aitech.weartak_civ.presentation.viewmodel.BaselineTrendsViewModel
import java.io.File
import java.io.FileInputStream
import java.text.DateFormat
import java.util.Date

@Composable
fun BaselineTrendsScreen(
    navController: NavController,
    baselineTrendsViewModel: BaselineTrendsViewModel = viewModel()
) {
    val uiState by baselineTrendsViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportSavedMessage = stringResource(R.string.metric_files_saved)
    val exportFailedMessage = stringResource(R.string.metric_files_save_failed)
    var showResetConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        baselineTrendsViewModel.load()
    }
    LaunchedEffect((uiState as? BaselineTrendsUiState.Ready)?.exportedFilePath) {
        val path = (uiState as? BaselineTrendsUiState.Ready)?.exportedFilePath ?: return@LaunchedEffect
        val saved = saveExportToDownloads(context, File(path))
        Toast.makeText(
            context,
            if (saved) exportSavedMessage else exportFailedMessage,
            Toast.LENGTH_LONG
        ).show()
    }
    BackHandler { navController.popBackStack() }

    Scaffold {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 38.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.baseline_trends),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center
                )
            }
            when (val state = uiState) {
                BaselineTrendsUiState.Loading -> item { CircularProgressIndicator() }
                is BaselineTrendsUiState.Unavailable -> item { Text(stringResource(state.messageRes), textAlign = TextAlign.Center) }
                is BaselineTrendsUiState.Error -> {
                    item { Text(stringResource(state.messageRes), textAlign = TextAlign.Center) }
                    metricItems(state.metrics)
                }
                is BaselineTrendsUiState.Ready -> {
                    state.statusMessageRes?.let { messageRes ->
                        item { Text(stringResource(messageRes), textAlign = TextAlign.Center) }
                    }
                    if (state.metrics.isEmpty()) {
                        item { Text(stringResource(R.string.no_baseline_metrics), textAlign = TextAlign.Center) }
                    } else {
                        metricItems(state.metrics)
                    }
                    item {
                        Button(
                            onClick = baselineTrendsViewModel::recalculate,
                            enabled = !state.recalculating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.recalculating) stringResource(R.string.calculating)
                                else stringResource(R.string.recalculate_trends)
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = baselineTrendsViewModel::export,
                            enabled = !state.recalculating && !state.exporting && !state.resetting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.exporting) stringResource(R.string.exporting)
                                else stringResource(R.string.export_metric_files)
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = { showResetConfirmation = true },
                            enabled = !state.recalculating && !state.exporting && !state.resetting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.resetting) stringResource(R.string.resetting)
                                else stringResource(R.string.reset_user_metrics)
                            )
                        }
                    }
                }
            }
        }
    }
    if (showResetConfirmation) {
        Dialog(onDismissRequest = { showResetConfirmation = false }) {
            Card(
                onClick = {},
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.reset_user_metrics_title),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.reset_user_metrics_warning),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            showResetConfirmation = false
                            baselineTrendsViewModel.reset()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.reset)) }
                    Button(
                        onClick = { showResetConfirmation = false },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

private fun ScalingLazyListScope.metricItems(
    metrics: List<MetricInspectionSnapshot>
) {
    items(metrics, key = { it.metricId }) { metric ->
        MetricTrendRow(metric)
    }
}

@Composable
private fun MetricTrendRow(metric: MetricInspectionSnapshot) {
    val displayName = when (metric.metricId) {
        "hrv.rmssd_ms" -> stringResource(R.string.metric_hrv_rmssd)
        "hr.resting_bpm.resting" -> stringResource(R.string.metric_resting_heart_rate)
        "hr.active_bpm" -> stringResource(R.string.metric_active_heart_rate)
        else -> metric.displayName
    }
    val value = metric.currentValue?.let {
        stringResource(R.string.metric_value_format, formatNumber(it), metric.unit)
    } ?: stringResource(R.string.not_calculated_yet)
    val baseline = metric.baseline?.let {
        stringResource(R.string.baseline_value_format, formatNumber(it), metric.unit)
    }
    val state = stringResource(
        R.string.metric_state_format,
        metric.state.name.lowercase().replace('_', ' ')
    )
    val confidence = metric.confidence?.let {
        stringResource(R.string.metric_confidence_format, formatNumber(it * 100.0))
    }
    val calculated = metric.calculatedAtMs?.let {
        stringResource(R.string.last_calculated_format, formatTimestamp(it))
    }
    val through = metric.dataThroughTsMs?.let {
        stringResource(R.string.data_through_format, formatTimestamp(it))
    }
    val collected = stringResource(
        R.string.data_collected_format,
        formatCoveredDuration(metric.coveredSeconds)
    )
    val activeDays = stringResource(R.string.data_active_days_format, metric.activeDays)
    Text(
        text = buildString {
            append(displayName)
            append("\n")
            append(value)
            append("\n")
            baseline?.let { append("$it\n") }
            append(state)
            confidence?.let { append("\n$it") }
            append("\n$collected")
            append("\n$activeDays")
            calculated?.let { append("\n$it") }
            through?.let { append("\n$it") }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        textAlign = TextAlign.Center,
        fontSize = 12.sp
    )
}

private fun formatTimestamp(timestampMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestampMs))

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)

@Composable
private fun formatCoveredDuration(seconds: Long): String {
    val notEnoughData = stringResource(R.string.duration_not_enough_data)
    val safeSeconds = seconds.coerceAtLeast(0L)
    if (safeSeconds < 60L) return notEnoughData
    val totalMinutes = safeSeconds / 60L
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes % (24L * 60L)) / 60L
    return when {
        days > 0L -> stringResource(R.string.duration_days_hours, days, hours)
        hours > 0L -> stringResource(R.string.duration_hours, hours)
        else -> stringResource(R.string.duration_minutes, totalMinutes)
    }
}

private fun saveExportToDownloads(context: android.content.Context, source: File): Boolean {
    if (!source.exists()) return false
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/WearTAK")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
    return runCatching {
        resolver.openOutputStream(uri)?.use { output ->
            FileInputStream(source).use { input -> input.copyTo(output) }
        } ?: error("Unable to open Downloads output")
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        true
    }.getOrElse {
        resolver.delete(uri, null, null)
        false
    }
}
