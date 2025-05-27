package com.aitech.weartak_civ.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.TAKConnectService
import kotlinx.coroutines.delay
import org.greenrobot.eventbus.EventBus
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.wear.compose.material.Icon
import com.aitech.weartak_civ.events.MarkerEvent
import com.aitech.weartak_civ.events.MarkerEvent.Companion.FRIENDLY
import com.aitech.weartak_civ.events.MarkerEvent.Companion.HOSTILE
import com.aitech.weartak_civ.events.MarkerEvent.Companion.NEUTRAL
import com.aitech.weartak_civ.events.MarkerEvent.Companion.UNKNOWN
import com.aitech.weartak_civ.presentation.MarkerActivity.Constants.markerType
import com.aitech.weartak_civ.service.DittoService
import kotlin.math.min

class MarkerActivity : ComponentActivity() {
    companion object Constants{
        lateinit var markerActivity : MarkerActivity
        var markerType: String = ""
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        markerActivity = this
        enableEdgeToEdge()
        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    NewMarkerCivApp(exitActivity = {finishAndRemoveTask()}, context)
                }
            }
        }
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun MarkerSelectionScreen(exitActivity: () -> Unit, shouldShowDialog: MutableState<Boolean>) {
    val dismissState = rememberSwipeToDismissBoxState()

    WearTAKCIVTheme {
        SwipeToDismissBox(
            state = dismissState,
            modifier = Modifier.unswipeable(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                DonutArcButton(
                    startAngle = -45f,
                    sweepAngle = 85f,
                    Alignment.CenterEnd,
                    "Friendly",
                    marker = painterResource(R.drawable.friendly),
                    onClick = {
                        EventBus.getDefault().post(MarkerEvent(FRIENDLY, 0))
                        markerType = FRIENDLY
                        shouldShowDialog.value = true
                    }
                )
                DonutArcButton(
                    startAngle = 45f,
                    sweepAngle = 85f,
                    Alignment.BottomCenter,
                    "Unknown",
                    marker = painterResource(R.drawable.unknown),
                    onClick = {
                        EventBus.getDefault().post(MarkerEvent(UNKNOWN, 0))
                        markerType = UNKNOWN
                        shouldShowDialog.value = true
                    }
                )
                DonutArcButton(
                    startAngle = 135f,
                    sweepAngle = 85f,
                    Alignment.CenterStart,
                    "Neutral",
                    marker = painterResource(R.drawable.neutral),
                    onClick = {
                        EventBus.getDefault().post(MarkerEvent(NEUTRAL, 0))
                        markerType = NEUTRAL
                        shouldShowDialog.value = true
                    }
                )
                DonutArcButton(
                    startAngle = 225f,
                    sweepAngle = 85f,
                    Alignment.TopCenter,
                    "Hostile",
                    marker = painterResource(R.drawable.hostile),
                    onClick = {
                        EventBus.getDefault().post(MarkerEvent(HOSTILE, 0))
                        markerType = HOSTILE
                        shouldShowDialog.value = true
                    }
                )
                IconButton(
                    onClick = {
                        markerType = ""
                        shouldShowDialog.value = false
                        exitActivity()
                              },
                    modifier = Modifier
                        .size(60.dp)
                        .border(1.dp, color = Color.Black, shape = CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = Color.Black,
                        containerColor = Color.LightGray
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close icon",
                        tint = Color.Black
                    )

                }
            }
        }
    }
}

@Composable
fun DonutArcButton(
    startAngle: Float,
    sweepAngle: Float,
    alignment: Alignment,
    markerTypeText: String,
    marker: Painter,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(200.dp)
            .clip(donutArcShape(startAngle, sweepAngle))
            .background(Color.DarkGray)
            .clickable(onClick = onClick)
            .padding(10.dp),
        contentAlignment = alignment
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = marker,
                contentDescription = "marker picture",
                modifier = Modifier.size(25.dp),
                alignment = alignment
            )
            Text(
                text = markerTypeText,
                color = Color.White,
                fontSize = 10.sp
            )
        }
    }
}

fun donutArcShape(
    startAngle: Float,
    sweepAngle: Float,
    innerRadiusRatio: Float = 0.5f
): GenericShape {
    return GenericShape { size, _ ->
        val outerRadius = min(size.width, size.height) / 2f
        val innerRadius = outerRadius * innerRadiusRatio
        val center = Offset(size.width / 2f, size.height / 2f)

        val outerRect = Rect(center = center, radius = outerRadius)
        val innerRect = Rect(center = center, radius = innerRadius)

        val path = Path().apply {
            arcTo(
                rect = outerRect,
                startAngleDegrees = startAngle,
                sweepAngleDegrees = sweepAngle,
                forceMoveTo = true
            )
            arcTo(
                rect = innerRect,
                startAngleDegrees = startAngle + sweepAngle,
                sweepAngleDegrees = -sweepAngle,
                forceMoveTo = false
            )
            close()
        }

        addPath(path)

    }
}

@Composable
fun MarkerSuccessDialog(shouldShowDialog: MutableState<Boolean>, successBoolean : MutableState<Boolean>) {
    Dialog(onDismissRequest = { shouldShowDialog.value = false }) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .height(200.dp),
            colors = CardDefaults.cardColors(containerColor = colors.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (successBoolean.value) {
//                    Icon(
//                        Icons.Rounded.Check,
//                        modifier = Modifier.size(25.dp),
//                        contentDescription = null,
//                        tint = Color.Green
//                    )
                    Image(
                        painter =
                        when (markerType) {
                            UNKNOWN -> painterResource(R.drawable.unknown)
                            FRIENDLY -> painterResource(R.drawable.friendly)
                            HOSTILE -> painterResource(R.drawable.hostile)
                            else -> painterResource(R.drawable.neutral)
                        },
                     contentDescription = "Marker Type",
                        )
                    Spacer(modifier = Modifier.height(10.dp))
                    androidx.wear.compose.material.Text(
                        text = stringResource(R.string.success),
                        color = Color.Green,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    androidx.wear.compose.material.Text(
                        //TODO" Have this text translated
                        text = "$markerType Marker Dropped",
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Icon(
                        Icons.Rounded.Clear,
                        modifier = Modifier.size(25.dp),
                        contentDescription = null,
                        tint = Color.Red
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // TODO: Add more context to the error, for example "not connected to server & mesh/ditto is disabled. Enable meshing to fix"
                    androidx.wear.compose.material.Text(
                        text = stringResource(R.string.error),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun NewMarkerCivApp(exitActivity: () -> Unit, context: Context) {
    val navController = rememberSwipeDismissableNavController()
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")
    val shouldShowConfirm = remember { mutableStateOf(false) }
    val takServer by TAKConnectService.isAnyTakConnectedLiveData.observeAsState()
    val location = dataRepository.latestLocation.value
    var locationChecked by remember { mutableStateOf(enableLocationService) }
    val successBoolean = remember { mutableStateOf(false) }
// react to changes in the license.
    LaunchedEffect(licenseKey) {
        if (isLicenseValid()) {
            navController.navigate("marker_screen") {
//                popUpTo("invalid_license_screen") {
//                    inclusive = true
//                }
            }
        } else {
            navController.navigate("invalid_license_screen") {
//                popUpTo("invalid_license_screen") {
//                    inclusive = true
//                }
            }
        }
    }
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = if (isLicenseValid()) "marker_screen" else "invalid_license_screen"
    ) {
        composable("marker_screen" ) {
            MarkerSelectionScreen(exitActivity = {exitActivity()}, shouldShowConfirm)

            if (shouldShowConfirm.value) {
                if (takServer == true) {
                    if (location != null && locationChecked) {
                        successBoolean.value = true
                    }
                } else {
                    if (MainActivity.enableDittoService && DittoService.isRunning.get()) {
                        Log.w("WearTAK-MarkerActivity", "Not connected to tak server, but Ditto is enabled and running. Marker will be sent through here.")
                        successBoolean.value = true
                    } else {
                        successBoolean.value = false
                    }
                }

                LaunchedEffect(Unit) {
                    delay(2000) // Delay for 5 seconds
                    shouldShowConfirm.value = false // Dismiss the dialog
                    exitActivity()
                }
                MarkerSuccessDialog(
                    shouldShowConfirm,
                    successBoolean,
                )
            }
        }
//        composable(
//            "license_setting_screen") {
//            LicenseKeyScreen(navController, licenseSettingsState, context)
//        }
        composable(
            "invalid_license_screen") {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}


@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun PreviewMarkerSelectionScreen() {
    val shouldShowConfirm = remember { mutableStateOf(true) }

    MarkerSelectionScreen(exitActivity = {},shouldShowConfirm )
}
@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
@Composable
fun PreviewMarkerSuccessDialog() {
    val successBoolean = remember { mutableStateOf(true) }
    val shouldShowConfirm = remember { mutableStateOf(true) }
    MarkerSuccessDialog(successBoolean, shouldShowConfirm)
}
