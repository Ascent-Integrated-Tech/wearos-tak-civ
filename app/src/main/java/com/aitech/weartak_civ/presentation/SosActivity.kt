package com.aitech.weartak_civ.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.TAKConnectService
import kotlinx.coroutines.delay
import org.greenrobot.eventbus.EventBus


class SosActivity : ComponentActivity() {
companion object Constants{
    lateinit var sosActivity : SosActivity
}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sosActivity = this
        enableEdgeToEdge()
        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current

                   SosCivApp(exitActivity = {finishAndRemoveTask()}, context)
                }
            }
        }
    }

    @Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
    @Composable
    fun PreviewMessageCard() {
        WearTAKCIVTheme {
            Surface {
                val shouldShowConfirmationDialog = remember { mutableStateOf(true) }
                val isConfirmed = remember { mutableStateOf(true) }
                
                ConfirmationDialog(
                    shouldShowConfirmationDialog,
                    rememberSwipeDismissableNavController(),
                    stringResource(R.string.sos_alert_prompt),
                    Icons.Default.Warning,
                    Color.Red,
                    "main_screen",
                    isConfirmed
                )            }
        }
    }
}


// vvv Composables also used in MarkerActivity
@Composable
fun ConfirmationDialogActivity(
    description: String,
    icon: ImageVector,
    confirmColor: Color,
    isConfirmed: MutableState<Boolean>,
    isDismissed: MutableState<Boolean>,
) {
    AlertDialog(
        containerColor = colors.background,
        modifier = Modifier.fillMaxSize(),
        onDismissRequest = { },
        icon = {
            Icon(
                icon,
                modifier = Modifier.size(25.dp),
                contentDescription = null,
                tint = Color.LightGray
            )
        },
        text = {
            Text(
                description,
                textAlign = TextAlign.Center,
                fontSize = if (description.length > 40) 13.sp else 14.sp
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    isConfirmed.value = true
                    isDismissed.value = false
                },
                modifier = Modifier.fillMaxWidth(0.45f),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = confirmColor,
                ),
            ) {
                Text(
                    text = stringResource(R.string.confirm),
                    color = Color.Black
                )
            }
        },

        dismissButton = {
            Button(
                onClick = {
                    isConfirmed.value = false
                    isDismissed.value = true
                },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.fillMaxWidth(0.45f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Gray,
                )
            ) {
                Text(
                    text = stringResource(R.string.dismiss),
                )
            }
        }
    )
}

@Composable
fun SuccessDialogActivity(shouldShowDialog: MutableState<Boolean>, successBoolean : MutableState<Boolean>) {
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
                    Icon(
                        Icons.Rounded.Check,
                        modifier = Modifier.size(25.dp),
                        contentDescription = null,
                        tint = Color.Green
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.success),
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
                    Text(
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
fun SosCivApp(exitActivity: () -> Unit, context: Context) {
    val isConfirmed = remember { mutableStateOf(false) }
    val isDismissed = remember { mutableStateOf(false) }
    val successBoolean = remember { mutableStateOf(false) }
    val shouldShowConfirm = remember { mutableStateOf(false) }
    val takServer by TAKConnectService.isAnyTakConnectedLiveData.observeAsState()
    val location = dataRepository.latestLocation.value
    var locationChecked by remember { mutableStateOf(enableLocationService) }
    val navController = rememberSwipeDismissableNavController()
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")

    // react to changes in the license.
    LaunchedEffect(licenseKey) {
        if (isLicenseValid()) {
            navController.navigate("sos_screen") {
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
        startDestination = if (isLicenseValid()) "sos_screen" else "invalid_license_screen"
    ) {
        composable("sos_screen" ) {
            ConfirmationDialogActivity(
                stringResource(R.string.sos_alert_prompt),
                Icons.Default.Warning,
                Color.Red,
                isConfirmed,
                isDismissed
            )

            if (isDismissed.value) {
                exitActivity()
            }

            //shouldShowDialog = Should successdialog be shown
            //successBoolean = Success boolean (x or check)
            //isConfirmed = Was confirmation dialog confirm button pressed
            if (isConfirmed.value) {
                if (takServer == true) {
                    if (location != null && locationChecked) {
                        successBoolean.value = true
                        SOSButtonEmergencyMessage()            }
                } else {
                    successBoolean.value = false
                }
                LaunchedEffect(Unit) {
                    delay(2000) // Delay for 5 seconds
                    shouldShowConfirm.value = false // Dismiss the dialog
                    isConfirmed.value = false
                    exitActivity()
                }
                SuccessDialogActivity(
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
