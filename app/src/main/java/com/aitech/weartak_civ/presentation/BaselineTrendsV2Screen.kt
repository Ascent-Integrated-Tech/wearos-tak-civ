package com.aitech.weartak_civ.presentation

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.aitech.weartak_civ.cep.metric.BaselineV2TrendItem
import com.aitech.weartak_civ.presentation.viewmodel.BaselineTrendsV2UiState
import com.aitech.weartak_civ.presentation.viewmodel.BaselineTrendsV2ViewModel

@Composable
fun BaselineTrendsV2Screen(
    navController: NavController,
    viewModel: BaselineTrendsV2ViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showResetConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.load()
    }
    LaunchedEffect((uiState as? BaselineTrendsV2UiState.Ready)?.exportedSnapshotText) {
        val text = (uiState as? BaselineTrendsV2UiState.Ready)?.exportedSnapshotText
            ?: return@LaunchedEffect
        val saved = saveV2SnapshotToDownloads(context, text)
        Toast.makeText(
            context,
            context.getString(if (saved) R.string.baseline_v2_saved else R.string.unable_export_v2),
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
                    text = stringResource(R.string.baseline_trends_v2),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center
                )
            }
            item {
                Text(
                    text = stringResource(R.string.baseline_trends_v2_desc),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp
                )
            }

            when (val state = uiState) {
                BaselineTrendsV2UiState.Loading -> item { CircularProgressIndicator() }
                is BaselineTrendsV2UiState.Error -> {
                    item { Text(stringResource(state.messageRes), textAlign = TextAlign.Center) }
                    v2MetricItems(state.metrics)
                }
                is BaselineTrendsV2UiState.Ready -> {
                    state.statusMessageRes?.let { messageRes ->
                        item { Text(stringResource(messageRes), textAlign = TextAlign.Center) }
                    }
                    if (state.metrics.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.no_baseline_v2_metrics),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        v2MetricItems(state.metrics)
                    }
                    item {
                        Button(
                            onClick = viewModel::recalculate,
                            enabled = !state.recalculating && !state.resetting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.recalculating) stringResource(R.string.calculating)
                                else stringResource(R.string.recalculate_v2)
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = { showResetConfirmation = true },
                            enabled = !state.recalculating && !state.resetting && !state.exporting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.resetting) stringResource(R.string.resetting)
                                else stringResource(R.string.reset_v2_metrics)
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = viewModel::export,
                            enabled = !state.recalculating && !state.resetting && !state.exporting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.exporting) stringResource(R.string.exporting)
                                else stringResource(R.string.export_v2_snapshot)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showResetConfirmation) {
        Dialog(onDismissRequest = { showResetConfirmation = false }) {
            Card(onClick = {}, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.reset_v2_metrics_title),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.reset_v2_metrics_warning),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            showResetConfirmation = false
                            viewModel.reset()
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

private fun ScalingLazyListScope.v2MetricItems(metrics: List<BaselineV2TrendItem>) {
    items(metrics, key = { "v2-${it.metricId}" }) { metric ->
        Text(
            text = buildString {
                append(metric.metricId)
                append("\nCurrent: ")
                append(metric.observedValue?.let(::formatNumber) ?: "—")
                append("\nSegments: ")
                append(metric.sampleCount)
                append("\nExpected: ")
                append(metric.expectedValue?.let(::formatNumber) ?: "—")
                append("\nExpected range: ")
                append(metric.lowerExpectedBound?.let(::formatNumber) ?: "—")
                append("–")
                append(metric.upperExpectedBound?.let(::formatNumber) ?: "—")
                append("\nCandidate boundaries: informational only")
                append("\nLow warning candidate: ")
                append(metric.candidateBoundaries.lowWarning?.let(::formatNumber) ?: "—")
                append("\nLow alert candidate: ")
                append(metric.candidateBoundaries.lowAlert?.let(::formatNumber) ?: "—")
                append("\nHigh warning candidate: ")
                append(metric.candidateBoundaries.highWarning?.let(::formatNumber) ?: "—")
                append("\nHigh alert candidate: ")
                append(metric.candidateBoundaries.highAlert?.let(::formatNumber) ?: "—")
                append("\nState: ")
                append(metric.lifecycle.name)
                append("\nConfidence: ")
                append(formatNumber(metric.aggregateConfidence * 100.0))
                append("%")
                append("\nCoverage: ")
                append(metric.coveredSeconds)
                append(" sec")
                append("\nActive days: ")
                append(metric.activeDays)
                append("\nQuality: ")
                append(metric.quality.state.name)
                append(" (")
                append(formatNumber(metric.quality.score * 100.0))
                append("%)")
                append("\nGeneration: ")
                append(metric.generation)
                append("\nModel: ")
                append(metric.modelVersion)
                append("\nDefinition: ")
                append(metric.definitionVersion)
                append("\nFormula: ")
                append(metric.thresholdFormulaVersion)
                append("\nPublished at: ")
                append(metric.publishedAtMs)
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            textAlign = TextAlign.Center,
            fontSize = 12.sp
        )
    }
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)

private fun saveV2SnapshotToDownloads(context: Context, text: String): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "weartak_baseline_v2_${System.currentTimeMillis()}.txt")
        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/WearTAK")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: return false
    return runCatching {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(text.toByteArray())
        } ?: error("Unable to open V2 export output")
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        true
    }.getOrElse {
        context.contentResolver.delete(uri, null, null)
        false
    }
}
