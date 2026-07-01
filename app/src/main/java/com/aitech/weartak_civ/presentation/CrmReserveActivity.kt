package com.aitech.weartak_civ.presentation

/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.CrmDataRepository
import com.aitech.weartak_civ.data.CrmDisplayState
import com.aitech.weartak_civ.data.CrmInferenceStatus
import com.aitech.weartak_civ.data.CrmThresholdRanges
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.presentation.viewmodel.CrmUiState
import com.aitech.weartak_civ.presentation.viewmodel.CrmViewModel
import com.aitech.weartak_civ.presentation.viewmodel.CrmViewModelFactory

class CrmReserveActivity : ComponentActivity() {

    private val app: WearTakApplication
        get() = application as WearTakApplication

    private val crmViewModel: CrmViewModel by viewModels {
        CrmViewModelFactory(
            crmDataRepository = app.crmRepo,
            dataRepository = app.repo,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WearTAKCIVTheme {
                BackHandler {
                    finish()
                }

                val uiState by crmViewModel.uiState.collectAsStateWithLifecycle()

                CrmReserveScreen(
                    uiState = uiState,
                    onBackClick = { finish() },
                )
            }
        }
    }
}


@Composable
fun CrmReserveScreen(
    uiState: CrmUiState,
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null,
) {
    val nowMillis = rememberNowMillisTicker()

    val ageLabel = crmAgeLabel(
        nowMillis = nowMillis,
        lastAcceptedAtMillis = uiState.lastAcceptedAtMillis,
    )

    val backgroundColor = crmBackgroundColor(
        displayState = uiState.displayState,
        hasValidScore = uiState.hasValidScore,
    )

    val foregroundColor = crmForegroundColor(
        displayState = uiState.displayState,
        hasValidScore = uiState.hasValidScore,
    )

    val statusText = crmStatusText(
        uiState = uiState,
        ageLabel = ageLabel,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (onBackClick != null) {
            Text(
                text = "Back",
                color = foregroundColor.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clickable { onBackClick() },
            )
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.hasValidScore) {
                Text(
                    text = uiState.score?.toString().orEmpty(),
                    color = foregroundColor,
                    fontSize = 58.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 58.sp,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = "CRM",
                    color = foregroundColor.copy(alpha = 0.9f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = uiState.displayLabel,
                    color = foregroundColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(
                    text = uiState.displayLabel,
                    color = foregroundColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = "CRM",
                    color = foregroundColor.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Text(
                text = statusText,
                color = foregroundColor.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )

        }

        if (uiState.hasValidScore) {
            CrmThresholdReserveBar(
                score = uiState.score,
                hasValidScore = true,
                thresholds = uiState.thresholds,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .width(132.dp)
                    .height(24.dp),
            )
        }
    }
}

private fun crmStatusText(
    uiState: CrmUiState,
    ageLabel: String,
): String {
    if (uiState.hasValidScore) {
        return if (ageLabel.isNotBlank()) {
            "${uiState.signalLabel} • $ageLabel"
        } else {
            uiState.signalLabel
        }
    }

    return when (uiState.status) {
        CrmInferenceStatus.NOT_WORN -> "Wear watch snugly"
        CrmInferenceStatus.IDLE -> "Waiting for CRM samples"
        CrmInferenceStatus.REJECTED -> {
            uiState.rejectionReason
                ?.takeIf { it.isNotBlank() }
                ?.let { "Adjust strap: $it" }
                ?: "Adjust strap for better signal"
        }
        CrmInferenceStatus.ERROR -> "CRM unavailable"
        CrmInferenceStatus.ACCEPTED -> "Waiting for CRM score"
    }
}

@Composable
private fun CrmThresholdReserveBar(
    score: Int?,
    hasValidScore: Boolean,
    thresholds: CrmThresholdRanges,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier,
    ) {
        drawThresholdBar(
            score = score,
            hasValidScore = hasValidScore,
            thresholds = thresholds,
        )
    }
}

private fun DrawScope.drawThresholdBar(
    score: Int?,
    hasValidScore: Boolean,
    thresholds: CrmThresholdRanges,
) {
    val barHeight = 7.dp.toPx()
    val tickHeight = 13.dp.toPx()
    val markerHeight = 7.dp.toPx()

    val barTop = size.height - barHeight
    val barWidth = size.width

    val poorEndX = barWidth * (thresholds.poorMax.coerceIn(0, 100) / 100f)
    val moderateEndX = barWidth * (thresholds.moderateMax.coerceIn(0, 100) / 100f)

    drawRoundedSegment(
        color = CrmPoorColor,
        startX = 0f,
        endX = poorEndX,
        top = barTop,
        height = barHeight,
    )

    drawRoundedSegment(
        color = CrmModerateColor,
        startX = poorEndX,
        endX = moderateEndX,
        top = barTop,
        height = barHeight,
    )

    drawRoundedSegment(
        color = CrmGoodColor,
        startX = moderateEndX,
        endX = barWidth,
        top = barTop,
        height = barHeight,
    )

    drawThresholdTick(
        x = poorEndX,
        top = barTop - tickHeight / 2f,
        height = tickHeight,
    )

    drawThresholdTick(
        x = moderateEndX,
        top = barTop - tickHeight / 2f,
        height = tickHeight,
    )

    if (hasValidScore && score != null) {
        val markerX = barWidth * (score.coerceIn(0, 100) / 100f)

        val markerPath = Path().apply {
            moveTo(markerX, barTop - 2.dp.toPx())
            lineTo(markerX - markerHeight, barTop - markerHeight - 2.dp.toPx())
            lineTo(markerX + markerHeight, barTop - markerHeight - 2.dp.toPx())
            close()
        }

        drawPath(
            path = markerPath,
            color = Color.White,
        )
    }
}

@Composable
private fun rememberNowMillisTicker(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }

    return now
}

private fun crmAgeLabel(
    nowMillis: Long,
    lastAcceptedAtMillis: Long,
): String {
    if (lastAcceptedAtMillis <= 0L) return ""

    val ageSeconds = ((nowMillis - lastAcceptedAtMillis) / 1_000L).coerceAtLeast(0L)

    return when {
        ageSeconds < 10L -> "Now"
        ageSeconds < 60L -> "${ageSeconds}s ago"
        ageSeconds < 3600L -> "${ageSeconds / 60L}m ago"
        else -> "${ageSeconds / 3600L}h ago"
    }
}

private fun DrawScope.drawRoundedSegment(
    color: Color,
    startX: Float,
    endX: Float,
    top: Float,
    height: Float,
) {
    val width = (endX - startX).coerceAtLeast(0f)

    if (width <= 0f) return

    drawRoundRect(
        color = color,
        topLeft = Offset(startX, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f, height / 2f),
    )
}

private fun DrawScope.drawThresholdTick(
    x: Float,
    top: Float,
    height: Float,
) {
    drawLine(
        color = Color.White.copy(alpha = 0.95f),
        start = Offset(x, top),
        end = Offset(x, top + height),
        strokeWidth = Stroke.DefaultMiter,
    )
}

private fun crmBackgroundColor(
    displayState: CrmDisplayState?,
    hasValidScore: Boolean,
): Color {
    if (!hasValidScore) return CrmUnknownColor

    return when (displayState) {
        CrmDisplayState.POOR -> CrmPoorColor
        CrmDisplayState.MODERATE -> CrmModerateColor
        CrmDisplayState.GOOD -> CrmGoodColor
        null -> CrmUnknownColor
    }
}

private fun crmForegroundColor(
    displayState: CrmDisplayState?,
    hasValidScore: Boolean,
): Color {
    if (!hasValidScore) return Color.White

    return when (displayState) {
        CrmDisplayState.MODERATE -> Color.Black
        CrmDisplayState.POOR,
        CrmDisplayState.GOOD,
        null -> Color.White
    }
}

private val CrmPoorColor = Color(0xFFB00020)
private val CrmModerateColor = Color(0xFFFFC107)
private val CrmGoodColor = Color(0xFF0B7D3B)
private val CrmUnknownColor = Color(0xFF202124)