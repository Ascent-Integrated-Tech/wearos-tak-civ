package com.aitech.weartak_civ.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.DROP_MARKER
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.LicenseManager.validateLicense
import com.aitech.weartak_civ.service.TAKConnectService
import kotlinx.coroutines.delay
import org.greenrobot.eventbus.EventBus

class MarkerActivity : ComponentActivity() {
    companion object Constants{
        lateinit var markerActivity : MarkerActivity
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        markerActivity = this
        enableEdgeToEdge()
        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current

                    MarkerCivApp(exitActivity = {finishAndRemoveTask()}, context)
                }
            }
        }
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun MarkerCivApp(exitActivity: () -> Unit, context: Context) {
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
            ConfirmationDialogActivity(
                stringResource(R.string.marker_drop_prompt),
                Icons.Default.LocationOn,
                Color.Green,
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
                        EventBus.getDefault().post(MessageEvent(DROP_MARKER, 0))
                    }
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
