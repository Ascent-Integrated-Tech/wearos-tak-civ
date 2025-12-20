/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.presentation

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.complication.SosComplicationService
import com.aitech.weartak_civ.events.CustomEmergencyEvent
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.CAT_SOS_ALERT_CANCELLED
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.CAT_MANUAL
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.DESC_SOS_ALERT
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.DESC_SOS_ALERT_CANCELLED
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.SosActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.SosActivity.Constants.isAlertingLiveData
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.DittoService
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.TAKConnectService
import kotlinx.coroutines.delay
import org.greenrobot.eventbus.EventBus


class SosActivity : ComponentActivity() {
companion object Constants{
    lateinit var sosActivity : SosActivity
    val TAG = "WearTAK-" + SosActivity::class.java.simpleName
    val isAlertingLiveData = MutableLiveData<Boolean>(false)

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
    fun PreviewMessageCardAlerting() {
        WearTAKCIVTheme {
            Surface {
                val isAlerting = MutableLiveData<Boolean>(false)
                val showDialog = remember { mutableStateOf(true) }
                val successFailure = remember { mutableStateOf(true) }

                SuccessDialogActivity(
                    showDialog,
                    successFailure,
                    isAlerting,
                    LocalContext.current
                )
            }
        }
    }
    @Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
    @Composable
    fun PreviewAlertScreen() {
        WearTAKCIVTheme {
            Surface {
                val isAlerting = MutableLiveData<Boolean>(true)
                val isConfirmed = remember { mutableStateOf(false) }

                ConfirmationPage(
                    isConfirmed,
                    {},
                    isAlerting
                )
            }
        }
    }
}

@Composable
fun ConfirmationPage(
    isConfirmed: MutableState<Boolean>,
    exitActivity: () -> Unit,
    isAlerting: MutableLiveData<Boolean>
) {
    WearTAKCIVTheme {
        val context = LocalContext.current
        Column(
            modifier = if (isAlerting.value == true) {
                Modifier
                    .fillMaxSize()
                    .background(Color.Red)
                    .padding(16.dp)
            } else {
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .padding(16.dp)
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Warning,
                modifier = Modifier.size(25.dp),
                contentDescription = null,
                tint = Color.LightGray
            )
            Spacer(modifier = Modifier.size(15.dp))
            Text(
                text = if (isAlerting.value == true) stringResource(R.string.cancel_current_sos_alert) else stringResource(R.string.sos_alert_prompt),
                textAlign = TextAlign.Center,
                fontSize = if (stringResource(R.string.sos_alert_prompt).length > 40) 13.sp else 14.sp
            )
            Spacer(modifier = Modifier.size(15.dp))
            Row(
                modifier = Modifier
                    .padding(top = 0.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center) {
                Button(
                    onClick = {
                        isConfirmed.value = false
                        exitActivity()
                        context.startActivity(Intent(context, MainActivity::class.java))
                    },
                    contentPadding = PaddingValues(10.dp),
//                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                    )
                ) {
                    Text(
                        text = stringResource(R.string.dismiss),
                    )
                }

                Spacer(modifier = Modifier.size(10.dp))

                Button(
                    onClick = {
                        Log.w(TAG, "SOS Clicked.")
                        isConfirmed.value = true
//                        exitActivity()
//                        context.startActivity(Intent(context, MainActivity::class.java))
                    },
//                    modifier = Modifier.fillMaxWidth(0.45f),
                    contentPadding = PaddingValues(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAlerting.value == true) Color.Black else Color.Red,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.confirm),
                        color = if (isAlerting.value == true) Color.White else Color.Black
                    )
                }
            }
        }
    }
}

@Composable
fun SuccessDialogActivity(
    shouldShowDialog: MutableState<Boolean>,
    successBoolean : MutableState<Boolean>,
    isAlerting: MutableLiveData<Boolean>,
    context:Context) {
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
                    isAlerting.value = isAlerting.value != true
                    isAlerting.postValue(isAlerting.value)
                    requestSosComplicationUpdate(context)

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (isAlerting.value == true) stringResource(R.string.sos_alert_sent) else stringResource(R.string.sos_alert_cancelled),
                        textAlign = TextAlign.Center,
                    )
                    Log.w(TAG, "isAlertingLiveData = ${isAlertingLiveData.observeAsState()}")
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

                    isAlerting.value = isAlerting.value == true
                    isAlerting.postValue(isAlerting.value)
                    Log.w(TAG, "isAlertingLiveData = ${isAlertingLiveData.observeAsState()}")
                }
            }
        }
    }
}

fun SOSButtonEmergencyMessage(isAlertingLiveData: MutableLiveData<Boolean>){
    if (isAlertingLiveData.value != true) {
        EventBus.getDefault().post(CustomEmergencyEvent(CAT_MANUAL, DESC_SOS_ALERT, 1, true))
        Log.d(MainActivity.Constants.TAG, "Manual Alert Triggered")
    } else {
        EventBus.getDefault().post(CustomEmergencyEvent(CAT_SOS_ALERT_CANCELLED, DESC_SOS_ALERT_CANCELLED, 1, true))
        Log.d(MainActivity.Constants.TAG, "Cancel Alert Triggered")
    }
}


@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun SosCivApp(exitActivity: () -> Unit, context: Context) {
    val isConfirmed = remember { mutableStateOf(false) }
    val isDismissed = remember { mutableStateOf(false) }
    val successBoolean = remember { mutableStateOf(false) }
    val shouldShowConfirm = remember { mutableStateOf(false) }
    isAlertingLiveData.observeAsState()
    val anyEndpointConnected by dataRepository.anyEndpointConnected.collectAsStateWithLifecycle(initialValue = false)
//    val anyEndpointConnected = connectedEndpoints.isNotEmpty()

    val location = dataRepository.latestLocation.value
    var locationChecked by remember { mutableStateOf(enableLocationService) }
    val navController = rememberSwipeDismissableNavController()
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")

    // react to changes in the license.
    LaunchedEffect(licenseKey) {
        val currentDestination = navController.currentBackStackEntry?.destination?.route
        val isValid = isLicenseValid()

        when {
            !isValid && currentDestination != "invalid_license_screen" -> {
                context.startActivity(Intent(context, SettingsActivity::class.java))
                exitActivity()
            }
            isValid == true && currentDestination != "sos_screen" -> {
                navController.navigate("sos_screen") {
                    popUpTo(0) { inclusive = true } // clears the back stack
                }
            }
        }
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = if (isLicenseValid()) "sos_screen" else "invalid_license_screen"
    ) {
        composable("sos_screen" ) {
//            ConfirmationDialogActivity(
//                isConfirmed,
//                isDismissed,
//                isAlertingLiveData
//            )
            ConfirmationPage(
                isConfirmed,
                {exitActivity()},
                isAlertingLiveData
            )
            Log.w(TAG, "SOS_Screen Composable Reached.")
//            if (isDismissed.value) {
//                exitActivity()
//                context.startActivity(Intent(context, MainActivity::class.java))
//            }

            //shouldShowDialog = Should successdialog be shown
            //successBoolean = Success boolean (x or check)
            //isConfirmed = Was confirmation dialog confirm button pressed

            if (isConfirmed.value) {
                successBoolean.value = (location != null && locationChecked && anyEndpointConnected)
                if (successBoolean.value) {SOSButtonEmergencyMessage(isAlertingLiveData)}

                LaunchedEffect(Unit) {
                    delay(2000) // Delay for 5 seconds
                    shouldShowConfirm.value = false // Dismiss the dialog
                    exitActivity()
                    context.startActivity(Intent(context, MainActivity::class.java))
                    isConfirmed.value = false
                }
                SuccessDialogActivity(
                    shouldShowConfirm,
                    successBoolean,
                    isAlertingLiveData,
                    context
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

fun requestSosComplicationUpdate(context: Context) {
    val componentName = ComponentName(context, SosComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}

