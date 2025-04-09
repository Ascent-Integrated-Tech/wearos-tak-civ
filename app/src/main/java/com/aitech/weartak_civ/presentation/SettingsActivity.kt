package com.aitech.weartak_civ.presentation

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.MutableLiveData
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.RevealValue
import androidx.wear.compose.foundation.rememberRevealState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ExperimentalWearMaterialApi
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.SelectableChip
import androidx.wear.compose.material.SplitToggleChip
import androidx.wear.compose.material.SwipeToRevealChip
import androidx.wear.compose.material.SwipeToRevealDefaults
import androidx.wear.compose.material.SwipeToRevealPrimaryAction
import androidx.wear.compose.material.SwipeToRevealSecondaryAction
import androidx.wear.compose.material.SwipeToRevealUndoAction
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.complication.HeartRateComplicationService
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.CALLSIGN_TEAM_ROLE_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_ADDED_VIA_UI
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_REMOVED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_TOGGLED
import com.aitech.weartak_civ.events.MessageEvent.Companion.UPDATE_INTERVAL_CHANGED
import com.aitech.weartak_civ.presentation.MainActivity.Constants.ALL_WIFI_NETWORKS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.EVENT_SEND_DELAY_MS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.SOME_WIFI_NETWORKS
import com.aitech.weartak_civ.presentation.MainActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsignSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimization
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimizationSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableAllWifiBatteryOptimizationState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBiometricsService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableBiometricsServiceLiveData
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableDittoService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableLocationServiceLiveData
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimization
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimizationSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableWifiBatteryOptimizationState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.generateSaveBattOnWifiString
import com.aitech.weartak_civ.presentation.MainActivity.Constants.getMinReportingInterval
import com.aitech.weartak_civ.presentation.MainActivity.Constants.license
import com.aitech.weartak_civ.presentation.MainActivity.Constants.licenseSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.saveBattOnWifiString
import com.aitech.weartak_civ.presentation.MainActivity.Constants.saveBattOnWifiStringState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startBiometricService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startDittoService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startLocationService
import com.aitech.weartak_civ.presentation.MainActivity.Constants.startStopServicesLicense
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.takServerListState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.team
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamEntriesValuesList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.versionNumber
import com.aitech.weartak_civ.presentation.MainActivity.Constants.wifiSsidList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.wifiSsidListSettingsState
import com.aitech.weartak_civ.presentation.MainActivity.Constants.wifiSsidListState
import com.aitech.weartak_civ.presentation.SettingsActivity.Constants.callsignState
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.CotDispatchService
import com.aitech.weartak_civ.service.DittoService
import com.aitech.weartak_civ.service.HeartrateService
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.LicenseManager.validateLicense
import com.aitech.weartak_civ.service.LocationService
import com.aitech.weartak_civ.service.TAKConnectService
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_CONNECTING_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.TAK_SERVER_DISCONNECTED_STRING
import com.aitech.weartak_civ.service.TAKConnectService.Companion.requestTakServerComplicationUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus



class SettingsActivity : ComponentActivity() {
    companion object Constants {
        lateinit var settingsActivity: SettingsActivity
        val callsignState = MutableLiveData<String>("")
        val licenseState = MutableLiveData<String>("")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsActivity = this
        enableEdgeToEdge()
        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WearTAKCivSettings(callsignState, licenseState)
                }
            }
        }
    }
}

@Composable
fun WearTAKCivSettings(callsignLiveDataState: MutableLiveData<String>, licenseLiveDataState: MutableLiveData<String>) {
    val navController = rememberSwipeDismissableNavController()
    val context = LocalContext.current

    // collect license from data repo so we can see if it changes.
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")

    //Log.d(TAG, "WearTAKCivApp composable called")
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = if (isLicenseValid()) "settings_screen" else "invalid_license_screen"
    ) {
        composable("main_screen" ) {
//            MainScreen(
//                navController = navController,
//                callsignState = callsignLiveDataState,
//                dataRepository = dataRepository,
//                context = context
//            )
        }
        composable(
            "settings_screen") {
            SettingsScreen(navController, callsignLiveDataState, licenseLiveDataState, context)
        }
        composable(
            "callsign_screen") {
            CallsignSetting(navController, callsignSettingsState)
        }
        composable(
            "role_screen") {
            RoleSetting(navController)
        }
        composable(
            "team_screen") {
            TeamSetting(navController)
        }
        composable(
            "tak_server_screen") {
            TakServerSetting(navController)
        }
        composable(
            "new_server_screen") {
            NewServerPage(navController)
        }
        composable(
            "edit_server_screen/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            Log.d(TAG, "edit_server_screen index: $index")
            EditServerPage(navController, index)
        }
        composable("optimize_battery_on_wifi_screen"){
            OptimizeBatteryOnWifiSelectScreen(navController)
        }
        composable(
            "wifi_ssid_screen") {
            WifiSsidSelectScreen(navController)
        }
        composable(
            "wifi_ssid_setting_screen") {
            NewWiFiSSIDSetting(navController)
        }
        composable(
            "edit_wifi_ssid_setting_screen/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            Log.d(TAG, "edit_wifi_ssid_setting_screen index: $index")
            EditedWiFiSSIDSetting(navController, index)
        }
        composable(
            "license_setting_screen") {
            LicenseKeyScreen(navController, licenseSettingsState, context)
        }
        composable(
            "invalid_license_screen") {
            InvalidLicenseScreen(navController)
        }
    }

}

@Composable
fun CallsignRoleTeamChip(
    route: String,
    title: String,
    nameState: MutableLiveData<String>,
    navController: NavController
) {
    val nameSecondary: String? by nameState.observeAsState()
    Chip(
        onClick = { navController.navigate(route) },
        enabled = true,
        label = { Text(text = title) },
        secondaryLabel = {
            Text(text = nameSecondary.toString(), maxLines =1, overflow = TextOverflow.Ellipsis)
        },
        colors = ChipDefaults.chipColors(
            backgroundColor = Color.DarkGray,
            contentColor = Color.White,
            secondaryContentColor = Color.LightGray
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 3.dp)
    )
}

@Composable
fun CallsignSetting(navController: NavController, callsignLiveDataState: MutableLiveData<String>) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        var callsignTextFieldValue by remember {
            mutableStateOf(
                TextFieldValue(
                    callsignLiveDataState.value ?: "null"
                )
            )
        }
//        Log.d(TAG, "callsignTextFieldValue=" + callsignTextFieldValue.text)

        OutlinedTextField(
            value = callsignTextFieldValue,
            onValueChange = {
                callsignTextFieldValue = it
            },
            maxLines = 1,
            label = { Text(text = stringResource(R.string.callsign), color = colors.primary) },
            placeholder = { Text(text = stringResource(R.string.callsign), color = colors.primary) },
            textStyle = TextStyle(color = colors.primary)
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    callsignLiveDataState.postValue(callsignTextFieldValue.text)
                    Log.d(TAG, "Settings saved with callsign: ${callsignLiveDataState.value}")
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                })
        }
    }
}

@Composable
fun TeamSetting(navController: NavController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = 20.dp, bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        val index : Int = findTeamIndex(teamSettingsState.value)
        var selectedRadioIndex by remember { mutableStateOf(index) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp)
                .padding(bottom = 40.dp)
                .verticalScroll(state = rememberScrollState()),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            teamEntriesValuesList.ltEntries.forEachIndexed { index, ltItem ->
                run {
                    SelectableChip(
                        modifier = Modifier.fillMaxWidth(),
                        selected = index == selectedRadioIndex,
                        onClick = { selectedRadioIndex = index},
                        label = {
                            // The primary label should have a maximum 3 lines of text
                            Text(ltItem, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        },
                        enabled = true,
                        shape = RoundedCornerShape(0.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(15.dp))
        }
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    teamSettingsState.postValue(teamEntriesValuesList.ltEntries[selectedRadioIndex])
                    Log.d(TAG, "Settings saved with team: ${teamSettingsState.value}")
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun RoleSetting (navController: NavController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = 20.dp, bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        val index = findRoleIndex(roleSettingsState.value)
        var selectedRadioIndex by remember { mutableStateOf(index) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 5.dp)
                .padding(bottom = 40.dp)
                .verticalScroll(state = rememberScrollState()),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            roleEntriesValuesList.lrEntries.forEachIndexed { index, lrItem ->
                run {
                    SelectableChip(
                        modifier = Modifier.fillMaxWidth(),
                        selected = index == selectedRadioIndex,
                        onClick = { selectedRadioIndex = index},
                        label = {
                            // The primary label should have a maximum 3 lines of text
                            Text(lrItem, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        },
                        enabled = true,
                        shape = RoundedCornerShape(0.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(15.dp))
        }
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }                },
                onClickConfirm = {
                    roleSettingsState.postValue(roleEntriesValuesList.lrEntries[selectedRadioIndex])
                    Log.d(TAG, "Settings saved with role: ${roleSettingsState.value}")
                    navController.navigate("settings_screen") {
                        popUpTo(("settings_screen")) {
                            inclusive = true
                        }
                    }                })
        }
    }
}

@SuppressLint("CoroutineCreationDuringComposition", "UnrememberedMutableState")
@Composable
fun TakServerSetting(navController: NavController) {
    val takServerSettingList by takServerListState.observeAsState(initial = emptyList())
    //val selectedIndexState = MutableLiveData<Int?>(null)
    //val selectedIndex by selectedIndexState.observeAsState(initial = null)
    Log.d(TAG, "TakServerSetting called")
    val text = stringResource(R.string.tak_server_connections)

    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .align(Alignment.TopCenter)
                .padding(bottom = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LazyColumn {
                    item {
                        Text(
                            text = text,
                            fontSize = if (text.length!! < 20) 15.sp else 13.sp,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 20.dp).align(Alignment.CenterHorizontally)
                        )
                    }
                    itemsIndexed(takServerSettingList){ index, takServerItem ->
                        var takServerChecked by remember{ mutableStateOf(takServerItem.isEnabled)}
                        Log.d(TAG, "index($index): takServerChecked($takServerChecked)")
                        SwipeableTakServerChip(
                            index,
                            takServerItem.name,
                            takServerItem.address,
                            checked = takServerChecked,
                            onCheckedChange =  {
                                Log.d(TAG, "onCheckedChange index($index): $it")
                                takServerChecked = it
                                Log.d(TAG, "index($index): takServerChecked($takServerChecked)")
                                takServerSettingList[index].isEnabled = it
                                val mutableTakServerList = takServerSettingList.toMutableList()
                                mutableTakServerList.get(index).isEnabled = it
                                takServerList = mutableTakServerList.toList()
                                takServerListState.postValue(takServerList)
                                //selectedIndexState.postValue(null)
                                CoroutineScope(Dispatchers.Main).launch {
                                    dataRepository.updateTakServerItem(index, takServerItem)
                                    Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(
                                        MessageEvent(SERVER_TOGGLED, index)
                                    ) }, EVENT_SEND_DELAY_MS)
                                    val minReportingInterval = getMinReportingInterval(takServerList)
                                    var prevReportingInterval = 1
                                    runBlocking {
                                        prevReportingInterval = dataRepository.getReportingUpdateInterval.first()
                                    }
                                    Log.d(TAG, "prevReportingInterval($prevReportingInterval); currMinReportingInterval($minReportingInterval)")
                                    if (prevReportingInterval != minReportingInterval) {
                                        Log.d(TAG, "prevReportingInterval($prevReportingInterval) != currMinReportingInterval($minReportingInterval). Sending UPDATE_INTERVAL_CHANGED")
                                        runBlocking {
                                            dataRepository.storeReportingUpdateInterval(minReportingInterval)
                                        }
                                        CotDispatchService.reportingUpdateIntervalSecs = minReportingInterval
                                        EventBus.getDefault().post(
                                            MessageEvent(
                                                UPDATE_INTERVAL_CHANGED, minReportingInterval)
                                        )
                                    }
                                } },
                            onPrimaryActionClick = {
                                Log.d(TAG, "Remove takServerItem ($takServerItem)")
                                val mutableTakServerList = takServerSettingList.toMutableList()
                                mutableTakServerList.removeAt(index)
                                takServerList = mutableTakServerList.toList()
                                takServerListState.postValue(takServerList)
                                //selectedIndexState.postValue(null)
                                Log.d(TAG, "onPrimaryActionClick index($index): REMOVE")
                                CoroutineScope(Dispatchers.Main).launch {
                                    dataRepository.removeTakServerItem(takServerItem)
                                    Handler(Looper.getMainLooper()).postDelayed( { EventBus.getDefault().post(
                                        MessageEvent(SERVER_REMOVED, index)
                                    ) }, EVENT_SEND_DELAY_MS)
                                }
                            },
                            onSecondaryActionClick = {
                                //selectedIndexState.postValue(index)
                                navController.navigate("edit_server_screen/$index")
                            }
                        )
                    }

                    item {
                        Chip(
                            onClick = { navController.navigate("new_server_screen") {
                                popUpTo(("new_server_screen")) {
                                    inclusive = true
                                }
                            } },
                            enabled = true,
                            label = { Text(text = stringResource(R.string.add_network)) },
                            colors = ChipDefaults.chipColors(
                                backgroundColor = Color.DarkGray,
                                contentColor = Color.White,
                                secondaryContentColor = Color.LightGray
                            ),
                            icon = {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Add TAK Server"
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                Button(
                    onClick = {
                        navController.navigate("settings_screen") {
                            popUpTo(("settings_screen")) {
                                inclusive = true
                            }
                        } },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                        contentColor = Color.Red
                    ),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "Go back to Main Settings Screen",
                    )
                }
            }
        }
    }
    Log.d(TAG, "Tak Server List: $takServerSettingList")
}

@OptIn(ExperimentalWearMaterialApi::class)
@Composable
fun SwipeableTakServerChip(
    index: Int,
    title: String,
    address: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    onPrimaryActionClick: () -> Unit,
    onSecondaryActionClick: () -> Unit
) {
    val revealState = rememberRevealState()
    val scope = rememberCoroutineScope()
    val takServerStatus by TAKConnectService.takServerDetails.get(index).connectionStatusLiveData.observeAsState(initial = TAK_SERVER_DISCONNECTED_STRING)

    Log.d(TAG, "SwipeableTakServerChip called with index $index: takServerStatus($takServerStatus)")
    SwipeToRevealChip(
        revealState = revealState,
        modifier =
        Modifier
            .fillMaxWidth()
            .semantics {
                // Use custom actions to make the primary and secondary actions accessible
                customActions =
                    listOf(
                        CustomAccessibilityAction("Delete TAK Server") {
                            /* Add the primary action click handler here */
                            true
                        },
                        CustomAccessibilityAction("Edit TAK Server") {
                            /* Add the secondary click handler here */
                            true
                        }
                    )
            },
        primaryAction = {
            SwipeToRevealPrimaryAction(
                revealState = revealState,
                icon = { Icon(SwipeToRevealDefaults.Delete, stringResource(R.string.delete)) },
                label = { Text(stringResource(R.string.delete)) },
                onClick = { onPrimaryActionClick(); scope.launch {revealState.animateTo(targetValue = RevealValue.RightRevealed)}}
            )
        },
        secondaryAction = {
            SwipeToRevealSecondaryAction(
                revealState = revealState,
                onClick = { onSecondaryActionClick() }
            ) {
                Icon(SwipeToRevealDefaults.MoreOptions, "More Options")
            }
        },
        undoPrimaryAction = {
            SwipeToRevealUndoAction(
                revealState = revealState,
                label = { Text(stringResource(R.string.undo)) },
                onClick = { /* Add the undo handler for primary action */ }
            )
        },
        undoSecondaryAction = {
            SwipeToRevealUndoAction(
                revealState = revealState,
                label = { Text(stringResource(R.string.undo)) },
                onClick = { /* Add the undo handler for secondary action */ }
            )
        },
        onFullSwipe = {onPrimaryActionClick() }
    ) {
        SplitToggleChip(
            label = {
                Image(
                    painter = if (takServerStatus == TAK_SERVER_CONNECTED_STRING) painterResource(R.drawable.tak_server_connected) else if (takServerStatus == TAK_SERVER_CONNECTING_STRING) painterResource(R.drawable.tak_server_connecting) else painterResource(R.drawable.tak_server_disconnected),
                    contentDescription = "Top Left Icon",
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(22.dp)
                        .padding(end = 2.dp),
                )
                Text(title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier
                    .align(Alignment.CenterVertically))
            },
            secondaryLabel = {
                Text(address, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            checked = checked,
            toggleControl = {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    modifier = Modifier.size(25.dp)
                )
            },
            onCheckedChange = onCheckedChange,
            enabled = true,
            onClick = { scope.launch {revealState.animateTo(targetValue = RevealValue.RightRevealing)} },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 3.dp)
        )
    }
}

@Composable
fun NewServerPage(navController: NavController) {
    var serverSettingsChanged = remember { mutableStateOf(false) }
    val isNetworkConnected = remember { mutableStateOf(false) }
    WearTAKCIVTheme {
        var name by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("8089") }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var reportingUpdateInterval by remember { mutableStateOf("10") }
        var isP12Cert = false
        val context = LocalContext.current
        val serverSettingsChanged = remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .align(Alignment.TopCenter)
                    .padding(bottom = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.new_server),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                ServerSettingText(
                    stringResource(R.string.connection_name),
                    name,
                    onValueChanged = { name = it },
                    false,
                    false,
                    true
                )
                ServerSettingText(
                    stringResource(R.string.address_url),
                    address,
                    onValueChanged = { address = it },
                    false,
                    false,
                    true
                )
                ServerSettingText(
                    stringResource(R.string.port),
                    port,
                    onValueChanged = { port = it },
                    false,
                    true,
                    true
                )
                ServerSettingText(
                    stringResource(R.string.username),
                    username,
                    onValueChanged = { username = it },
                    false,
                    false,
                    true
                )
                ServerSettingText(
                    stringResource(R.string.password),
                    password,
                    onValueChanged = { password = it },
                    true,
                    false,
                    true
                )

                val focusManager = LocalFocusManager.current

                OutlinedTextField(
                    modifier = Modifier.padding(vertical = 5.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (reportingUpdateInterval.isEmpty()) {
                                Toast.makeText(
                                    mainActivity,
                                    context.getString(R.string.reporting_interval_empty_enter_again), Toast.LENGTH_SHORT).show()
                            } else if (reportingUpdateInterval == "0") {
                                Toast.makeText(
                                    mainActivity,
                                    context.getString(R.string.reporting_interval_0_enter_again), Toast.LENGTH_SHORT).show()
                            } else {
                                // If there is valid input, we can clear focus
                                // (which typically dismisses the keyboard)
                                focusManager.clearFocus()

                                // Now you can navigate or do the next action
                            }
                        }
                    ),
                    singleLine = true,
                    value = reportingUpdateInterval,
                    onValueChange = {
                        reportingUpdateInterval = it
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.reporting_interval),
                            color = colors.primary,
                            style = MaterialTheme.typography.caption3
                        )
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.interval_in_secs),
                            color = colors.primary,
                            style = MaterialTheme.typography.caption3
                        )
                    },
                    textStyle = TextStyle(color = colors.primary),
                )

                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {

                val maxReportRate: Int = 100000000
                val minReportRate: Int = 1

                ConfirmCancelButtons(
                    onClickCancel = {
                        Log.d(TAG, "Settings NOT saved")
                        navController.navigate("tak_server_screen") {
                            popUpTo(("tak_server_screen")) {
                                inclusive = true
                            }
                        }
                    },
                    onClickConfirm = {
                        scope.launch {
                            if (name.isNotBlank() && address.isNotBlank() && port.isNotBlank() && username.isNotBlank() &&
                                password.isNotBlank() && reportingUpdateInterval.isNotBlank()) {
                                if (reportingUpdateInterval.equals("")) {
                                    Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_empty_enter_again), Toast.LENGTH_SHORT).show()
                                } else if (reportingUpdateInterval.equals("0")) {
                                    Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_0_enter_again), Toast.LENGTH_SHORT).show()
                                } else if (reportingUpdateInterval.toInt() < minReportRate /* || reportingUpdateInterval.text.toInt() > maxReportRate */) {
                                    Toast.makeText(mainActivity, context.getString(R.string.reporting_rate_outside_bounds) + " " + "$minReportRate" + "-" + "$maxReportRate", Toast.LENGTH_SHORT).show()
                                } else {
                                    val newReportingUpdateInterval = reportingUpdateInterval.toInt()
                                    val takServerItem = DataRepository.TakServerItem(true, name, address, port.toInt(),
                                        isP12Cert, username, password, "", "", newReportingUpdateInterval)
                                    runBlocking {
                                        dataRepository.addTakServerItem(takServerItem)
                                    }
                                    val mutableTakServerList = takServerListState.value?.toMutableList() ?: emptyList<DataRepository.TakServerItem>().toMutableList()
                                    mutableTakServerList.add(takServerItem)
                                    takServerList = mutableTakServerList.toList()
                                    takServerListState.postValue(takServerList)
                                    serverSettingsChanged.value = true

                                    Log.d(TAG, "serverSettingsChanged=${serverSettingsChanged.value}")
                                    Handler(Looper.getMainLooper()).postDelayed(
                                        { EventBus.getDefault().post(MessageEvent(
                                            SERVER_ADDED_VIA_UI, takServerList.size-1)) }, EVENT_SEND_DELAY_MS)

                                    Log.d(TAG, "reportingUpdateInterval.toInt()=${newReportingUpdateInterval}; COT:${CotDispatchService.reportingUpdateIntervalSecs}")
                                    val minReportingInterval = getMinReportingInterval(takServerList)
                                    var prevReportingInterval = 1
                                    runBlocking {
                                        prevReportingInterval = dataRepository.getReportingUpdateInterval.first()
                                    }
                                    Log.d(TAG, "prevReportingInterval($prevReportingInterval); currMinReportingInterval($minReportingInterval)")
                                    if (prevReportingInterval != minReportingInterval) {
                                        Log.d(TAG, "prevReportingInterval($prevReportingInterval) != currMinReportingInterval($minReportingInterval). Sending UPDATE_INTERVAL_CHANGED")
                                        runBlocking {
                                            dataRepository.storeReportingUpdateInterval(minReportingInterval)
                                        }
                                        CotDispatchService.reportingUpdateIntervalSecs = minReportingInterval
                                        EventBus.getDefault().post(MessageEvent(UPDATE_INTERVAL_CHANGED, minReportingInterval))
                                    }
                                    /*
                                    if (newReportingUpdateInterval < CotDispatchService.reportingUpdateIntervalSecs) {
                                        Log.d(TAG, "Setting reportingUpdateIntervalSecs (curr= ${CotDispatchService.reportingUpdateIntervalSecs}) to ${newReportingUpdateInterval}")
                                        runBlocking {
                                            dataRepository.storeReportingUpdateInterval(newReportingUpdateInterval)
                                        }
                                        CotDispatchService.reportingUpdateIntervalSecs = newReportingUpdateInterval
                                        //LocationService.locationUpdateFastestIntervalSecs = CotDispatchService.reportingUpdateIntervalSecs
                                        EventBus.getDefault().post(MessageEvent(UPDATE_INTERVAL_CHANGED, newReportingUpdateInterval))
                                    }

                                     */
                                }
                            } else {
                                Toast.makeText(
                                    mainActivity,
                                    context.getString(R.string.please_enter_valid_values_for_all_fields), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
                Log.d(TAG, "NewServerPage serverSettingsChanged=${serverSettingsChanged.value}")
                if (serverSettingsChanged.value) {
                    //TAKConnectService.newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTING_STRING)
                    val loadingDialog by TAKConnectService.newServerAddedViaUiConnectionStatusLiveData.observeAsState()
                    val shouldShowLoadingDialog = remember { mutableStateOf(false) }
                    val shouldShowConfirmDialog = remember { mutableStateOf(false) }
                    val isServerConnected = remember { mutableStateOf(false) }

                    if (NetworkManager.isNetworkConnected) {
                        Log.d(TAG, "NewServerPage: serverSettingsChanged w/ Network connected")
                        isNetworkConnected.value = true
                        displayServerDialogs(true, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected)
                        LoadingDialog(shouldShowLoadingDialog)
                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog,shouldShowLoadingDialog, navController, isServerConnected, serverSettingsChanged)
                    } else {
                        Log.d(TAG, "NewServerPage: serverSettingsChanged w/ Network disconnected")
                        isNetworkConnected.value = false
                        displayServerDialogs(false, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected)
                        //LoadingDialog(shouldShowLoadingDialog)
                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog, shouldShowLoadingDialog, navController, isServerConnected, serverSettingsChanged)
                    }
                }
            }
        }
    }
}

@Composable
fun EditServerPage(
    navController: NavController,
    index: Int
) {
    WearTAKCIVTheme {
        val takServerListLocal by takServerListState.observeAsState(initial = emptyList())
        Log.d(TAG, "Edited tak server list: $takServerListLocal")
        var name by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.name.orEmpty()) }
        var address by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.address.orEmpty()) }
        var port by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.port.toString().orEmpty()) }
        var username by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.username.orEmpty()) }
        var password by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.password.orEmpty()) }
        var reportingUpdateInterval by remember { mutableStateOf(takServerListLocal.getOrNull(index)?.reportingUpdateInterval.toString().orEmpty()) }
        var isP12Cert by remember { mutableStateOf(takServerListLocal.get(index)?.isP12Cert ?: false) }
        val context = LocalContext.current
        val serverConnSettingsChanged = remember { mutableStateOf(false) }
        Log.d(TAG, "address=$address, serverSettingsChanged=${serverConnSettingsChanged.value}")

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .align(Alignment.TopCenter)
                    .padding(bottom = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.edit_server),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                ServerSettingText(
                    stringResource(R.string.connection_name),
                    name,
                    onValueChanged = { name = it },
                    false,
                    false,
                    true
                )

                if (isP12Cert == true) {
                    ServerSettingText(
                        stringResource(R.string.address_url),
                        address,
                        onValueChanged = { address = it },
                        false,
                        false,
                        false
                    )
                    ServerSettingText(
                        stringResource(R.string.port),
                        port,
                        onValueChanged = { port = it },
                        false,
                        true,
                        true)
                } else {
                    ServerSettingText(
                        stringResource(R.string.address_url),
                        address,
                        onValueChanged = { address = it },
                        false,
                        false,
                        true
                    )
                    ServerSettingText(
                        stringResource(R.string.port),
                        port,
                        onValueChanged = { port = it },
                        false,
                        true,
                        true)
                    ServerSettingText(
                        stringResource(R.string.username),
                        username,
                        onValueChanged = { username = it },
                        false,
                        false,
                        true
                    )
                    ServerSettingText(
                        stringResource(R.string.password),
                        password,
                        onValueChanged = { password = it },
                        true,
                        false,
                        true
                    )
                }
                val focusManager = LocalFocusManager.current

                OutlinedTextField(
                    modifier = Modifier.padding(vertical = 5.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (reportingUpdateInterval.isEmpty()) {
                                Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_empty_enter_again), Toast.LENGTH_SHORT).show()
                            } else if (reportingUpdateInterval == "0") {
                                Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_0_enter_again), Toast.LENGTH_SHORT).show()
                            } else {
                                // If there is valid input, we can clear focus
                                // (which typically dismisses the keyboard)
                                focusManager.clearFocus()

                                // Now you can navigate or do the next action
                            }
                        }
                    ),
                    singleLine = true,
                    value = reportingUpdateInterval,
                    onValueChange = {
                        reportingUpdateInterval = it
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.reporting_interval),
                            color = colors.primary,
                            style = MaterialTheme.typography.caption3
                        )
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.interval_in_secs),
                            color = colors.primary,
                            style = MaterialTheme.typography.caption3
                        )
                    },
                    textStyle = TextStyle(color = colors.primary),
                )
                if (isP12Cert == true) {
                    Text(
                        text = "Using client certificate",
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(5.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                val maxReportRate: Int = 100000000
                val minReportRate: Int = 1
                val scope = rememberCoroutineScope()

                val context = LocalContext.current

                ConfirmCancelButtons(
                    onClickCancel = {
                        Log.d(TAG, "Settings NOT saved")
                        navController.navigate("tak_server_screen") {
                            popUpTo(("tak_server_screen")) {
                                inclusive = true
                            }
                        }
                    },
                    onClickConfirm = {
                        if (name.isNotBlank() && address.isNotBlank() && port.isNotBlank() && reportingUpdateInterval.isNotBlank() &&
                            (if (isP12Cert) { true } else { username.isNotBlank() } ) &&
                            (if (isP12Cert) { true } else { password.isNotBlank() } ) ) {
                            if (reportingUpdateInterval.equals("")) {
                                Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_empty_enter_again), Toast.LENGTH_SHORT).show()
                            } else if (reportingUpdateInterval.equals("0")) {
                                Toast.makeText(mainActivity, context.getString(R.string.reporting_interval_0_enter_again), Toast.LENGTH_SHORT).show()
                            } else if (reportingUpdateInterval.toInt() < minReportRate /* || reportingUpdateInterval.text.toInt() > maxReportRate */) {
                                Toast.makeText(mainActivity,
                                    context.getString(R.string.reporting_rate_outside_bounds) + " " + "$minReportRate" + "-" + "$maxReportRate", Toast.LENGTH_SHORT).show()
                            } else {
                                var reportingIntervalChanged = false
                                val takServerDetails = takServerList.get(index)
                                Log.d(TAG, "EditServerPage: onClickConfirm all settings good")
                                Log.d(TAG, "takServerDetails: $takServerDetails")
                                Log.d(TAG, "name: $name, address: $address, port=$port, username=$username, password=$password, reportingUpdateInterval=${reportingUpdateInterval.toInt()}")
                                if (name != takServerDetails.name || address != takServerDetails.address || port != takServerDetails.port.toString() ||
                                    username != takServerDetails.username || password != takServerDetails.password ||
                                    takServerDetails.reportingUpdateInterval != reportingUpdateInterval.toInt()) {
                                    if (name == takServerDetails.name) {
                                        // Name is same, so something else in address, port, username, password or reporting interval has changed
                                        if (takServerDetails.reportingUpdateInterval == reportingUpdateInterval.toInt()) {
                                            serverConnSettingsChanged.value = true
                                            Log.d(TAG, "serverConnSettingsChanged set to true")
                                        } else {
                                            reportingIntervalChanged = true
                                            Log.d(TAG, "reportingIntervalChanged set to true")
                                        }
                                    } else { // Name is different
                                        if (address != takServerDetails.address || port.toInt() != takServerDetails.port ||
                                            username != takServerDetails.username || password != takServerDetails.password ||
                                            reportingUpdateInterval.toInt() != takServerDetails.reportingUpdateInterval) {
                                            // Apart from name, something else is different
                                            if (reportingUpdateInterval.toInt() == takServerDetails.reportingUpdateInterval) {
                                                serverConnSettingsChanged.value = true
                                                Log.d(TAG, "serverConnSettingsChanged set to true with name being different")
                                            } else {
                                                reportingIntervalChanged = true
                                                Log.d(TAG, "reportingIntervalChanged set to true with name being different")
                                            }
                                        }
                                    }
                                    val changedTakServerDetail: DataRepository.TakServerItem =
                                        if (isP12Cert == true) {
                                            DataRepository.TakServerItem(takServerListLocal.get(index).isEnabled, name, address, port.toInt(), isP12Cert,
                                                takServerListLocal.get(index).username, takServerListLocal.get(index).password, takServerListLocal.get(index).p12Cert, takServerListLocal.get(index).p12CertPassword,
                                                reportingUpdateInterval.toInt())
                                        } else {
                                            DataRepository.TakServerItem(takServerListLocal.get(index).isEnabled, name, address, port.toInt(), isP12Cert,
                                                username, password, "", "", reportingUpdateInterval.toInt())
                                        }

                                    runBlocking {
                                        dataRepository.updateTakServerItem(index, changedTakServerDetail)
                                    }

                                    val mutableTakServerList = takServerListLocal.toMutableList()
                                    mutableTakServerList.set(index, changedTakServerDetail)
                                    takServerList = mutableTakServerList.toList()
                                    takServerListState.postValue(takServerList)

                                    val changedReportingUpdateInterval = reportingUpdateInterval.toInt()
                                    val minReportingInterval = getMinReportingInterval(takServerList)
                                    var prevReportingInterval = 1
                                    runBlocking {
                                        prevReportingInterval = dataRepository.getReportingUpdateInterval.first()
                                    }
                                    Log.d(TAG, "prevReportingInterval($prevReportingInterval); currMinReportingInterval($minReportingInterval)")
                                    if (prevReportingInterval != minReportingInterval) {
                                        Log.d(TAG, "prevReportingInterval($prevReportingInterval) != currMinReportingInterval($minReportingInterval). Sending UPDATE_INTERVAL_CHANGED")
                                        runBlocking {
                                            dataRepository.storeReportingUpdateInterval(minReportingInterval)
                                        }
                                        CotDispatchService.reportingUpdateIntervalSecs = minReportingInterval
                                        EventBus.getDefault().post(MessageEvent(UPDATE_INTERVAL_CHANGED, minReportingInterval))
                                    }

                                    if (serverConnSettingsChanged.value || reportingIntervalChanged) {
                                        Log.d(TAG, "EditServerPage serverSettingsChanged=${serverConnSettingsChanged.value} or reportingIntervalChanged=$reportingIntervalChanged")
                                        EventBus.getDefault().post(MessageEvent(SERVER_CHANGED, index))
                                        if (reportingIntervalChanged) {
                                            // Go back to previous page
                                            navController.navigate("tak_server_screen") {
                                                popUpTo(("tak_server_screen")) {
                                                    inclusive = true
                                                }
                                            }
                                        }
                                    } else {
                                        navController.navigate("tak_server_screen") {
                                            popUpTo(("tak_server_screen")) {
                                                inclusive = true
                                            }
                                        }
                                    }
                                } else {
                                    Log.d(TAG, "EditServerPage onClickConfirm: nothing has changed")
                                    serverConnSettingsChanged.value = false
                                    navController.navigate("tak_server_screen") {
                                        popUpTo(("tak_server_screen")) {
                                            inclusive = true
                                        }
                                    }
                                }
                            }
                        } else {
                            Toast.makeText(mainActivity, context.getString(R.string.please_enter_valid_values_for_all_fields), Toast.LENGTH_LONG).show()
                        }
                    }
                )
                Log.d(TAG, "EditServerPage: loadingDialog serverSettingsChanged=${serverConnSettingsChanged.value}")
                if (serverConnSettingsChanged.value) {
                    val loadingDialog by TAKConnectService.takServerDetails.get(index).connectionStatusLiveData.observeAsState()
                    val shouldShowLoadingDialog = remember { mutableStateOf(false) }
                    val shouldShowConfirmDialog = remember { mutableStateOf(false) }
                    val isServerConnected = remember { mutableStateOf(false) }
                    if (NetworkManager.isNetworkConnected) {
                        Log.d(TAG, "EditServerPage: serverSettingsChanged w/ Network connected")
                        val isNetworkConnected = remember { mutableStateOf(true)}

                        displayServerDialogs(isNetworkConnected.value, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected)
                        LoadingDialog(shouldShowLoadingDialog)
                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog, shouldShowLoadingDialog, navController, isServerConnected, serverConnSettingsChanged)
                    } else {
                        Log.d(TAG, "EditServerPage: serverSettingsChanged w/ Network disconnected")
                        val isNetworkConnected = remember { mutableStateOf(false)}

                        displayServerDialogs(isNetworkConnected.value, loadingDialog, shouldShowLoadingDialog, shouldShowConfirmDialog, isServerConnected)
                        //LoadingDialog(shouldShowLoadingDialog)
                        ServerRegistrationDialog(isNetworkConnected, shouldShowConfirmDialog, shouldShowLoadingDialog, navController, isServerConnected, serverConnSettingsChanged)
                    }
                }
            }
        }
    }
}

@Composable
fun ServerSettingText (
    label: String,
    value: String,
    onValueChanged: (String) -> Unit,
    isPassword : Boolean,
    isPort : Boolean,
    isEnabled: Boolean
) {
    val maxChar = 20

    OutlinedTextField(
        value = value,
        enabled = isEnabled,
        onValueChange = onValueChanged,
//        maxLines = 1,
        label = { Text(text = label, color = colors.primary, fontSize = 12.sp) },
        textStyle = TextStyle(color = colors.primary, fontSize = if (value!!.length <= maxChar) 12.sp else 10.sp),
        modifier = Modifier.padding(bottom = 5.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (isPort) KeyboardType.Number else KeyboardType.Text),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
fun OptimizeBatteryOnWifiSelectScreen(navController: NavController) {
    val enableWifiOpt = enableWifiBatteryOptimizationSettingsState.value ?: true
    val enableAllWifiOpt = enableAllWifiBatteryOptimizationSettingsState.value ?: true
    val SOME_WIFI_CONNECTIONS = stringResource(R.string.some_wifi_connections)
    val ALL_WIFI_CONNECTIONS = stringResource(R.string.all_wifi_connections)
    val NO_WIFI_CONNECTIONS = stringResource(R.string.no_wifi_connections)
    val radioOptions = listOf(ALL_WIFI_CONNECTIONS, NO_WIFI_CONNECTIONS, SOME_WIFI_CONNECTIONS)
    val initialValue = when {
        enableWifiOpt && enableAllWifiOpt -> radioOptions[0]  // "All"
        !enableWifiOpt -> radioOptions[1] // "No"
        else -> radioOptions[2] // "Some"
    }
    val selectedValue = remember { mutableStateOf(initialValue) }
    val isSelectedItem: (String) -> Boolean = { selectedValue.value == it }
    val onChangeState: (String) -> Unit = { selectedValue.value = it }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    //Log.d(TAG, "OptimizeBatteryonWifiSelectScreen called")

    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .align(Alignment.TopCenter)
                    .padding(bottom = 20.dp, top = 15.dp)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.save_battery_on_wifi),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(3.dp)
                        .padding(horizontal = 10.dp)
                )

                Text(
                    text = stringResource(R.string.slows_update_rate_on_enabled_wifi_networks),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .padding(horizontal = 10.dp)
                )

                Text(
                    text = stringResource(R.string.optimize_on),
                    textAlign = TextAlign.Left,
                    modifier = Modifier
                        .padding(bottom = 3.dp)
                        .padding(horizontal = 10.dp)
                        .align(Alignment.Start)
                )
                Log.d(TAG, "initial  value = $initialValue, selected value: ${selectedValue.value}")
                radioOptions.forEach { text ->
                    SelectableChip(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 5.dp),
                        selected = isSelectedItem(text),
                        onClick = { onChangeState(text)
                            if (text == SOME_WIFI_CONNECTIONS) {
                                enableWifiBatteryOptimizationSettingsState.postValue(true)
                                enableAllWifiBatteryOptimizationSettingsState.postValue(false)
                                scope.launch { scrollState.animateScrollTo(scrollState.maxValue + 150) }
                            } else if (text == ALL_WIFI_CONNECTIONS) {
                                enableWifiBatteryOptimizationSettingsState.postValue(true)
                                enableAllWifiBatteryOptimizationSettingsState.postValue(true)
                            } else {
                                enableWifiBatteryOptimizationSettingsState.postValue(false)
                                enableAllWifiBatteryOptimizationSettingsState.postValue(false)
                            }
                        },
                        label = {
                            // The primary label should have a maximum 3 lines of text
                            Text(text, maxLines = 3, fontSize = 14.sp, overflow = TextOverflow.Ellipsis)
                        },
                        enabled = true,
                    )

                }
                if (selectedValue.value == SOME_WIFI_CONNECTIONS) {
                    Chip(
                        onClick = {
                            navController.navigate("wifi_ssid_screen") {
                                popUpTo(("wifi_ssid_screen")) {
                                    inclusive = true
                                }
                            }
                            Log.d(TAG, "Some WiFi Connections selected: initial value = $initialValue, selected value: ${selectedValue.value}, isWifiOpt = $enableWifiOpt, isAllWifiOpt = $enableAllWifiOpt, wifiSsidList = ${wifiSsidListSettingsState.value}")
                        },
                        enabled = true,
                        label = {
                            Text(
                                text = stringResource(R.string.edit_optimized_wifi_connections),
                                fontWeight = FontWeight.Normal,
                                color = colors.primary
                            )
                        },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = Color.DarkGray,
                            contentColor = Color.White,
                            secondaryContentColor = Color.LightGray
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                ConfirmCancelButtons(
                    onClickCancel = {
                        Log.d(TAG, "OptimizeBatteryonWifiSelectScreen Settings NOT saved")

                        navController.navigate("settings_screen") {
                            popUpTo(("settings_screen")) {
                                inclusive = true
                            }
                        }
                    },
                    onClickConfirm = {
                        // TODO: check why enableWifiBatteryOptimizationState.value is Boolean?
                        val prevWifiOpt: Boolean = enableWifiBatteryOptimizationState.value ?: true
                        val prevAllOpt: Boolean = enableAllWifiBatteryOptimizationState.value ?: true
                        val currWifiOpt = enableWifiBatteryOptimizationSettingsState.value ?: true
                        val currAllOpt = enableAllWifiBatteryOptimizationSettingsState.value ?: true

                        if ((prevWifiOpt != currWifiOpt) || (prevAllOpt != currAllOpt) || (wifiSsidListState.value != wifiSsidListSettingsState.value)) {
                            Log.d(TAG, "Changed sthg: enableWifiOpt(old=$prevWifiOpt, new=$currWifiOpt), enableOptimAllWifi(old=$prevAllOpt, new=$currAllOpt), wifiSsidList(old=${wifiSsidListState.value}, new=${wifiSsidListSettingsState.value})")
                            saveBattOnWifiStringState.postValue(generateSaveBattOnWifiString(currWifiOpt ?: true, currAllOpt ?: true))
                            enableWifiBatteryOptimizationState.postValue(currWifiOpt)
                            enableAllWifiBatteryOptimizationState.postValue(currAllOpt)
                            wifiSsidListState.postValue(wifiSsidListSettingsState.value)
                        } else {
                            Log.d(TAG, "Settings NOT changed, NOT saved")
                        }

                        navController.navigate("settings_screen") {
                            popUpTo(("settings_screen")) {
                                inclusive = true
                            }
                        }

                    })
            }
        }
    }
}

@Composable
fun WifiSsidSelectScreen(navController: NavController) {
    //Log.d(TAG, "WifiSsidSelectScreen called")
    val wifiSsidList by wifiSsidListSettingsState.observeAsState(initial = emptyList())
    val text = stringResource(R.string.enabled_wifi_connections)

    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .align(Alignment.TopCenter)
                    .padding(bottom = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LazyColumn {
                    item {
                        Text(
                            text = text,
                            fontSize = if(text.length!!<20) 15.sp else 13.sp,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal =20.dp, vertical = 10.dp)
                        )
                    }
                    Log.d(TAG, "SSID List = $wifiSsidList")
                    itemsIndexed(wifiSsidList){ index, wifiSsidItem ->
                        WifiSsidItemChip(
                            wifiSsidItem.name,
                            onPrimaryActionClick = {
                                Log.d(TAG, "Remove wifiSsidItem ($wifiSsidItem)")
                                val mutableWifiSsidList = wifiSsidList.toMutableList()
                                mutableWifiSsidList.removeAt(index)
                                wifiSsidListSettingsState.postValue(mutableWifiSsidList.toList())
                            },
                            onSecondaryActionClick = {
                                // Navigate to "edit_wifi_ssid_setting_screen/<index>"
                                //Log.d(TAG, "onSecondaryActionClick called")
                                navController.navigate("edit_wifi_ssid_setting_screen/$index")
                            }
                        )
                    }

                    item {
                        Chip(
                            onClick = { navController.navigate("wifi_ssid_setting_screen") {
                                popUpTo(("wifi_ssid_setting_screen")) {
                                    inclusive = true
                                }
                            } },
                            enabled = true,
                            label = { Text(text = stringResource(R.string.add_network)) },
                            colors = ChipDefaults.chipColors(
                                backgroundColor = Color.DarkGray,
                                contentColor = Color.White,
                                secondaryContentColor = Color.LightGray
                            ),
                            icon = {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Localized description"
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        )
                    }

                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                Button(
                    onClick = { navController.navigate("optimize_battery_on_wifi_screen") {
                        popUpTo(("optimize_battery_on_wifi_screen")) {
                            inclusive = true
                        }
                    } },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                        contentColor = Color.Red
                    ),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "Localized description",
                    )
                }
            }

        }

    }
}

@OptIn(ExperimentalWearMaterialApi::class)
@Composable
fun WifiSsidItemChip(
    ssid: String,
    onPrimaryActionClick: () -> Unit,
    onSecondaryActionClick: () -> Unit
) {
    val revealState = rememberRevealState()
    val scope = rememberCoroutineScope()

    //Log.d(TAG, "WifiSsidItemChip called")
    SwipeToRevealChip(
        revealState = revealState,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                // Use custom actions to make the primary and secondary actions accessible
                customActions = listOf(
                    CustomAccessibilityAction("Delete WiFi SSID") {
                        /* Add the primary action click handler here */
                        true
                    },
                    // TODO: Can we change the MoreOptions Icon to Edit Icon?
                    CustomAccessibilityAction("Edit WiFi SSID") {
                        /* Add the secondary click handler here */
                        true
                    }
                )
            },
        primaryAction = {
            SwipeToRevealPrimaryAction(
                revealState = revealState,
                icon = { Icon(SwipeToRevealDefaults.Delete, "Delete") },
                label = { Text(stringResource(R.string.delete)) },
                onClick = { onPrimaryActionClick(); scope.launch {revealState.animateTo(targetValue = RevealValue.RightRevealed)}}
            )
        },
        secondaryAction = {
            SwipeToRevealSecondaryAction(
                revealState = revealState,
                onClick = { onSecondaryActionClick() }
            ) {
                Icon(SwipeToRevealDefaults.MoreOptions, "More Options")
            }
        },
        undoPrimaryAction = {
            SwipeToRevealUndoAction(
                revealState = revealState,
                label = { Text(stringResource(R.string.undo)) },
                onClick = { /* Add the undo handler for primary action */ }
            )
        },
        undoSecondaryAction = {
            SwipeToRevealUndoAction(
                revealState = revealState,
                label = { Text(stringResource(R.string.undo)) },
                onClick = { /* Add the undo handler for secondary action */ }
            )
        },
        onFullSwipe = {onPrimaryActionClick() }
    ) {
        Chip(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 5.dp),
            onClick = {
                scope.launch {revealState.animateTo(targetValue = RevealValue.RightRevealing)}
            },
            colors = ChipDefaults.chipColors(Color.DarkGray),
            label = { Text(ssid, maxLines = 3) }
        )
    }
}

@Composable
fun NewWiFiSSIDSetting(
    navController: NavController,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        //Log.d(TAG, "NewWiFiSSIDSetting called")
        var name by remember {
            mutableStateOf("")
        }

        OutlinedTextField(
            value = name,
            onValueChange = {text ->
                name = text
            },
            maxLines = 1,
            label = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            placeholder = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            textStyle = TextStyle(color = colors.primary)
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
                    navController.navigate("wifi_ssid_screen") {
                        popUpTo(("wifi_ssid_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    if (name.isNotBlank()) {
                        val mutableWifiSsidList = wifiSsidListSettingsState.value?.toMutableList() ?: emptyList<DataRepository.WifiSsidItem>().toMutableList()
                        mutableWifiSsidList.add(DataRepository.WifiSsidItem(name))
                        wifiSsidListSettingsState.postValue(mutableWifiSsidList.toList())
                    }

                    navController.navigate("wifi_ssid_screen") {
                        popUpTo(("wifi_ssid_screen")) {
                            inclusive = true
                        }
                    }

                })
        }
    }
}

@Composable
fun EditedWiFiSSIDSetting(
    navController: NavController,
    index: Int
) {
    //Log.d(TAG, "EditedWiFiSSIDSetting called")
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val wifiSsidList by wifiSsidListSettingsState.observeAsState(initial = emptyList())
        Log.d(TAG, "EditedWiFiSSIDSetting SSID list: $wifiSsidList")
        var name by remember { mutableStateOf(wifiSsidList.getOrNull(index)?.name.orEmpty()) }
        Log.d(TAG, "text = $name")

        OutlinedTextField(
            value = name,
            onValueChange = { text -> name = text },
            maxLines = 1,
            label = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            placeholder = { Text(text = stringResource(R.string.wifi_ssid), color = colors.primary) },
            textStyle = TextStyle(color = colors.primary)
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
                    navController.navigate("wifi_ssid_screen") {
                        popUpTo(("wifi_ssid_screen")) {
                            inclusive = true
                        }
                    }
                },
                onClickConfirm = {
                    val mutableWifiSsidList = wifiSsidList.toMutableList()
                    mutableWifiSsidList.set(index, DataRepository.WifiSsidItem(name))
                    wifiSsidListSettingsState.postValue(mutableWifiSsidList.toList())

                    navController.navigate("wifi_ssid_screen") {
                        popUpTo(("wifi_ssid_screen")) {
                            inclusive = true
                        }
                    }

                })
        }
    }
}

@Composable
fun LoadingDialog(
    shouldShowDialog: MutableState<Boolean>,
) {
    if (shouldShowDialog.value) {
        Dialog(
            onDismissRequest = { shouldShowDialog.value = false },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.DarkGray)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {

                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.registering_device_with_tak_server),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun ServerRegistrationDialog(
    isNetworkConnected: MutableState<Boolean>,
    shouldShowDialog: MutableState<Boolean>,
    shouldShowLoadingDialog: MutableState<Boolean>,
    navController: NavController,
    isServerConnected: MutableState<Boolean>,
    serverDetailsChanged: MutableState<Boolean>
) {
    val invalidCredText = stringResource(R.string.invalid_credentials_try_again)
    val retryText = stringResource(R.string.retry)
    if (shouldShowDialog.value) {
        Log.d(TAG, "ServerRegistrationDialog: shouldShowDialog=${shouldShowDialog.value}, shouldShowLoadingDialog=${shouldShowLoadingDialog.value}")
        shouldShowLoadingDialog.value = false
        AlertDialog(
            modifier = Modifier.wrapContentHeight(),
            containerColor = Color.DarkGray,
            onDismissRequest = {
                Log.d(TAG, "onDismissRequest")
                shouldShowDialog.value = false
            },
            title = {
                if (isServerConnected.value) {
                    Text(stringResource(R.string.registration_complete), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                } else {
                    Text(stringResource(R.string.registration_failed), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 15.dp))
                }
            },
            text = {
                if (isNetworkConnected.value) {
                    if (isServerConnected.value) {
                        Text(stringResource(R.string.tak_server_registration_succeeded), textAlign = TextAlign.Center)
                    } else {
                        Text(invalidCredText, textAlign = TextAlign.Center, fontSize = if(invalidCredText.length!!<35) 15.sp else 13.sp)
                    }
                } else {
                    Log.d(TAG, "text=(${stringResource(R.string.network_disconnected_try_later)}), isNetworkConnected=${isNetworkConnected.value}, isServerConnected=${isServerConnected.value}")
                    Text(stringResource(R.string.registration_failed), textAlign = TextAlign.Center)
                }
            },
            confirmButton = {
                Log.d(TAG, "ServerRegistrationDialog confirmButton: isNetworkConnected=${isNetworkConnected.value}, isServerConnected=${isServerConnected.value}, shouldShowDialog=${shouldShowDialog.value}")
                if (isNetworkConnected.value && !isServerConnected.value) {
                    // Create retry button only if network is connected && server is NOT connected
                    Log.d(TAG, "ServerRegistrationDialog confirmButton: RetryButton is shown")
                    Button(
                        onClick = { shouldShowDialog.value = false
                            serverDetailsChanged.value = false
                        },
                        modifier = Modifier.fillMaxWidth(0.45f),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.LightGray,
                        ),
                    ) {
                        Text(text = retryText, fontSize = if(retryText.length!!<9) 15.sp else 13.sp, color = Color.Black)
                    }
                } else {
                    Log.d(TAG, "ServerRegistrationDialog confirmButton: RetryButton is NOT shown")
                }
            },

            dismissButton = {
                Button(
                    onClick = {
                        shouldShowDialog.value = false
                        serverDetailsChanged.value = false
                        navController.navigate("tak_server_screen") {
                            popUpTo(("tak_server_screen")) {
                                inclusive = true
                            }
                        }
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = if (isServerConnected.value) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.45f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray,
                    )
                ) {
                    if (isServerConnected.value) {
                        Text(text = stringResource(R.string.okay))
                    } else {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            }
        )
    }
}

@Composable
fun ConfirmationDialog(
    shouldShowDialog: MutableState<Boolean>,
    navController: NavController,
    description: String,
    icon: ImageVector,
    confirmColor: Color,
    path: String,
    isConfirmed: MutableState<Boolean>
) {
    if (shouldShowDialog.value) {
        AlertDialog(
            containerColor = colors.background,
            modifier = Modifier.fillMaxSize(),
            onDismissRequest = {
                shouldShowDialog.value = false
            },
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
                        shouldShowDialog.value = false
                        isConfirmed.value = true
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
                        shouldShowDialog.value = false
                        isConfirmed.value = false
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
}

@Composable
fun LicenseKeyScreen(navController: NavController, licenseLiveDataState: MutableLiveData<String>, context: Context) {
    var offsetX by remember { mutableStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(bottom = 16.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {

        var licenseTextFieldValue by remember {
            mutableStateOf(
                TextFieldValue(
                    licenseLiveDataState.value ?: "null"
                )
            )
        }

        OutlinedTextField(
            value = licenseTextFieldValue,
            onValueChange = {
                licenseTextFieldValue = it
            },
            maxLines = 1,
            label = { Text(text = stringResource(R.string.license_key), color = colors.primary) },
            textStyle = TextStyle(color = colors.primary)
        )
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            var settingsChanged: Boolean = false
            var licenseChanged: Boolean = false
            ConfirmCancelButtons(
                onClickCancel = {
                    Log.d(TAG, "Settings NOT saved")
                    settingsChanged = false; licenseChanged = false
                    if (isLicenseValid()) {
                        navController.navigate("settings_screen") {
                            popUpTo(("settings_screen")) {
                                inclusive = true
                            }
                        }
                    } else {
                        navController.navigate("invalid_license_screen")
                    }
                },
                onClickConfirm = {
                    val enteredLicense = licenseTextFieldValue.text
                    val currentLicense = licenseSettingsState.value
                    Log.d(
                        TAG, """
                            === === Licensing Information === ===
                            License Entered: $enteredLicense
                            License Current: $currentLicense
                            """.trimIndent()
                    )

                    if (enteredLicense != currentLicense) {
                        settingsChanged = true; licenseChanged = true
                        license = licenseTextFieldValue.text ?: license
                        Log.d(TAG, "License Changed. Updating DataRepository.")
                        CoroutineScope(Dispatchers.Main).launch {
                            licenseSettingsState.postValue(license)
                            licenseLiveDataState.postValue(license)
                            dataRepository.storeLicense(license)
                        }
                        requestCallsignComplicationUpdate(context)
                        requestLocationComplicationUpdate(context)
                        requestTakServerComplicationUpdate(context)
                        requestHeartRateComplicationUpdate(context)

                        Log.d(TAG, "Sending License for Validation.")
                    } else {
                        Log.d(TAG, "License Did not Change.")
                    }
                    validateLicense(license)

                    if (isLicenseValid()) {
                        Log.d(
                            TAG, """
                        License is valid. Navigating to Settings Screen & Starting Application Services. 
                        =====================================
                        """.trimIndent()
                        )
                        navController.navigate("settings_screen") {
                            popUpTo(("invalid_license_screen")) {
                                inclusive = true
                            }
                        }
                    } else {
                        Log.d(
                            TAG, """
                            License is invalid. Navigating to Invalid License Screen & Stopping Services if started
                            =====================================
                            """.trimIndent()
                        )
                        navController.navigate("invalid_license_screen"){
                            popUpTo("settings_screen") { inclusive = true }
                        }
                    }
                    startStopServicesLicense(context)
                }
            )
        }
    }
}

@Composable
fun InvalidLicenseScreen (navController: NavController) {
    WearTAKCIVTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.invalid_license_key),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(5.dp))
            //Maybe add info here for how to contact Ascent for the key?
            Text(
                text = stringResource(R.string.please_add_correct_key_to_use_weartak),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(10.dp))
            Button(
                onClick = { navController.navigate("license_setting_screen") {
                    popUpTo("license_setting_screen") { inclusive = true }
                } },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = Color.Black
                )
            ) {
                Text (
                    text = stringResource(R.string.update_license),
                    color = Color.Black,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun SuccessDialog(shouldShowDialog: MutableState<Boolean>, successBoolean : MutableState<Boolean>, isConfirmed : MutableState<Boolean>) {
    if (shouldShowDialog.value) {
        Dialog(onDismissRequest = { shouldShowDialog.value = false }) {
            LaunchedEffect(Unit) {
                delay(2000) // Delay for 5 seconds
                shouldShowDialog.value = false // Dismiss the dialog
                isConfirmed.value = false
            }

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
}

@Composable
fun SettingSwitchToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String,
    enabled: Boolean = true,
) {
    // The primary label should have a maximum 3 lines of text
    // and the secondary label should have max 2 lines of text.
    ToggleChip(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        label = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = {
            Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        checked = checked,
        // For Switch  toggle controls the Wear Material UX guidance is to set the
        // unselected toggle control color to ToggleChipDefaults.switchUncheckedIconColor()
        // rather than the default.
        colors =
        ToggleChipDefaults.toggleChipColors(
            uncheckedToggleControlColor = ToggleChipDefaults.SwitchUncheckedIconColor
        ),
        toggleControl = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        },
        onCheckedChange = onCheckedChange,
        enabled = true,
    )
}

//Contains functionality for settings screen
@Composable
fun SettingsScreen(
    navController: NavController,
    callsignLiveDataState: MutableLiveData<String>,
    licenseLiveDataState: MutableLiveData<String>,
    context: Context
) {
    //Log.d(TAG, "SettingsScreen called")
    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(bottom = 16.dp, top = 15.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            var locationChecked by remember{ mutableStateOf(enableLocationService)}
            var biometricsChecked by remember{ mutableStateOf(enableBiometricsService)}
            var dittoChecked by remember{ mutableStateOf(enableDittoService)}
            val displayedSecondaryText by saveBattOnWifiStringState.observeAsState()
            val text = stringResource(R.string.callsign_settings)

//            when (displayedSecondaryText) {
//                ALL_WIFI_NETWORKS -> { ALL_WIFI_NETWORKS = stringResource(R.string.all_wifi_connections) }
//                SOME_WIFI_NETWORKS -> { SOME_WIFI_NETWORKS = stringResource(R.string.some_wifi_connections) }
//                else -> { NO_WIFI_NETWORKS = stringResource(R.string.no_wifi_connections) }
//            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .align(Alignment.TopCenter)
                    .padding(bottom = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = text,
                    fontSize = if(text.length!!<20) 15.sp else 13.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                CallsignRoleTeamChip(
                    "callsign_screen",
                    stringResource(R.string.callsign),
                    callsignSettingsState,
                    navController
                )
                CallsignRoleTeamChip("team_screen",
                    stringResource(R.string.team), teamSettingsState, navController)
                CallsignRoleTeamChip("role_screen",
                    stringResource(R.string.role), roleSettingsState, navController)

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = stringResource(R.string.connection_settings),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(7.dp)
                )

                Chip(
                    onClick = {
                        takServerListState.postValue(takServerList)
                        navController.navigate("tak_server_screen") },
                    enabled = true,
                    label = { Text(text = stringResource(R.string.tak_servers)) },
                    secondaryLabel = {
//                        Text(text = name)
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray,
                        contentColor = Color.White,
                        secondaryContentColor = Color.LightGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 3.dp)
                )

                Chip(

                    onClick = {
                        enableWifiBatteryOptimizationSettingsState.postValue(enableWifiBatteryOptimizationState.value)
                        enableAllWifiBatteryOptimizationSettingsState.postValue(enableAllWifiBatteryOptimizationState.value)
                        wifiSsidListSettingsState.postValue(wifiSsidListState.value)
                        navController.navigate("optimize_battery_on_wifi_screen") {
                            popUpTo("optimize_battery_on_wifi_screen") { inclusive = true }
                        } },
                    enabled = true,
                    label = { Text(text = stringResource(R.string.save_battery_on_wifi)) },
                    secondaryLabel = {Text(
                        text = if (displayedSecondaryText == ALL_WIFI_NETWORKS) stringResource(R.string.all_wifi_connections)
                        else if (displayedSecondaryText == SOME_WIFI_NETWORKS) stringResource(R.string.some_wifi_connections)
                        else stringResource(R.string.no_wifi_connections)
                    )
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray,
                        contentColor = Color.White,
                        secondaryContentColor = Color.LightGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 3.dp)
                )

                val licenseText: String? by licenseSettingsState.observeAsState()

                Chip(
                    onClick = { navController.navigate("license_setting_screen") {
                        popUpTo("license_setting_screen") { inclusive = true }
                    } },
                    enabled = true,
                    label = { Text(text = stringResource(R.string.license_key)) },
                    secondaryLabel = {
                        Text(text = licenseText.toString(), maxLines =1, overflow = TextOverflow.Ellipsis)
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color.DarkGray,
                        contentColor = Color.White,
                        secondaryContentColor = Color.LightGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 3.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = stringResource(R.string.general_settings),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(7.dp)
                )

                SettingSwitchToggle(
                    checked = locationChecked, onCheckedChange = { locationChecked = it },
                    title = stringResource(R.string.location_service), description = stringResource(R.string.service_on_off)
                )

                SettingSwitchToggle(
                    checked = biometricsChecked, onCheckedChange = { biometricsChecked = it },
                    title = stringResource(R.string.biometrics),
                    description = stringResource(R.string.service_on_off),
                )

                SettingSwitchToggle(
                    checked = dittoChecked, onCheckedChange = { dittoChecked = it },
                    title = stringResource(R.string.ditto),
                    description = stringResource(R.string.service_on_off),
                )

                Text(
                    modifier = Modifier.padding(top = 7.dp),
                    text = stringResource(R.string.version, versionNumber),
                    fontSize = 11.sp,
                    color = colors.primary,
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                ConfirmCancelButtons(
                    onClickCancel = {
                        Log.d(TAG, "SettingsScreen Settings NOT saved")
//                        navController.navigate("main_screen") {
//                            popUpTo(("main_screen")) {
//                                inclusive = true
//                            }
//                        }
                        context.startActivity(Intent(context, MainActivity::class.java))
                    },
                    onClickConfirm = {
                        var settingsChanged: Boolean = false
                        var callsignChanged: Boolean = false
                        var teamChanged = false
                        var roleChanged = false
                        var licenseChanged: Boolean = false
                        val maxReportRate: Int = 100000000
                        val minReportRate: Int = 1
                        var locationCheckedChanged: Boolean = false
                        var biometricsCheckedChanged: Boolean = false
                        var dittoCheckedChanged: Boolean = false
                        var saveBattOnWifiChanged = false
                        var takServerListChanged = false

                        if (!callsign.equals(callsignSettingsState.value)) {
                            settingsChanged = true; callsignChanged = true
                            callsign = callsignSettingsState.value ?: callsign
                            requestCallsignComplicationUpdate(context)

                            runBlocking {
                                callsignLiveDataState.postValue(callsign)
                            }
                            EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))
                        }
                        if (!team.equals(teamSettingsState.value)) {
                            settingsChanged = true; teamChanged = true
                            team = teamSettingsState.value ?: team
                            var teamIndex = teamEntriesValuesList.ltEntries.indexOf(team)
                            // This cannot happen, but still...
                            if (teamIndex == -1) {
                                Log.e(TAG, "ERROR SettingsScreen: teamIndex is -1. Setting team entry and value to that of index 0")
                                teamIndex = 0
                                team = teamEntriesValuesList.ltEntries[0]
                                teamSettingsState.postValue(team)
                            }
                            teamValue = teamEntriesValuesList.ltValues[teamIndex]
                            runBlocking {
                                teamState.postValue(team)
                            }
                            EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))
                        }
                        if (!role.equals(roleSettingsState.value)) {
                            settingsChanged = true; roleChanged = true
                            role = roleSettingsState.value ?: role
                            var roleIndex = roleEntriesValuesList.lrEntries.indexOf(role)
                            // This cannot happen, but still...
                            if (roleIndex == -1) {
                                Log.e(TAG, "ERROR SettingsScreen: roleIndex is -1. Setting role entry and value to that of index 0")
                                roleIndex = 0
                                role = roleEntriesValuesList.lrEntries[0]
                                roleSettingsState.postValue(role)
                            }
                            roleValue = roleEntriesValuesList.lrValues[roleIndex]
                            runBlocking {
                                roleState.postValue(role)
                            }
                            EventBus.getDefault().post(MessageEvent(CALLSIGN_TEAM_ROLE_CHANGED, 0))
                        }

                        if ((saveBattOnWifiString != saveBattOnWifiStringState.value) || (wifiSsidList != wifiSsidListState.value)) {
                            settingsChanged = true; saveBattOnWifiChanged = true
                            wifiSsidList = wifiSsidListState.value ?: wifiSsidList
                            saveBattOnWifiString = saveBattOnWifiStringState.value ?: saveBattOnWifiString
                            enableWifiBatteryOptimization = enableWifiBatteryOptimizationState.value ?: enableWifiBatteryOptimization
                            enableAllWifiBatteryOptimization = enableAllWifiBatteryOptimizationState.value ?: enableAllWifiBatteryOptimization
                        }
//                            if (!license.equals(licenseSettingsState.value)) {
//                                settingsChanged = true; licenseChanged = true
//                                license = licenseSettingsState.value ?: license
//                                licenseLiveDataState.postValue(license)
//                            }

                        if (takServerList != takServerListState.value) {
                            settingsChanged = true; takServerListChanged = true
                            takServerList = takServerListState.value ?: takServerList
                        }
                        Log.d(TAG, "takServerList: $takServerList")
                        Log.d(TAG, "takServerListState: " + takServerListState.value)
                        Log.d(TAG, "takServerListChanged: $takServerListChanged")


                        if (enableLocationService != locationChecked) {
                            settingsChanged = true; locationCheckedChanged = true
                            enableLocationService = locationChecked
                            requestLocationComplicationUpdate(context)
                            if (enableLocationService) {
                                Log.d(TAG, "Trying to start location service")
                                startLocationService()
                            } else {
                                Log.d(TAG, "Trying to stop location service")
                                mainActivity.stopService(Intent(mainActivity, LocationService::class.java))
                            }
                            enableLocationServiceLiveData.postValue(enableLocationService)
                        }

                        if (enableBiometricsService != biometricsChecked) {
                            settingsChanged = true; biometricsCheckedChanged = true
                            enableBiometricsService = biometricsChecked
                            if (enableBiometricsService) {
                                Log.d(TAG, "Trying to start biometric service")
                                startBiometricService()
                            } else {
                                Log.d(TAG, "Trying to stop biometric service")
                                mainActivity.stopService(Intent(mainActivity, HeartrateService::class.java))
                            }
                            enableBiometricsServiceLiveData.postValue(enableBiometricsService)
                            requestHeartRateComplicationUpdate(context)
                        }

                        if (enableDittoService != dittoChecked) {
                            settingsChanged = true; dittoCheckedChanged = true
                            enableDittoService = dittoChecked
                            if (enableDittoService) {
                                Log.d(TAG, "Trying to start ditto service")
                                startDittoService()
                            } else {
                                Log.d(TAG, "Trying to stop ditto service")
                                mainActivity.stopService(Intent(mainActivity, DittoService::class.java))
                            }
                        }

                        if (settingsChanged) {
                            CoroutineScope(Dispatchers.Main).launch {
                                if (callsignChanged) {
                                    Log.d(TAG, "Storing Callsign ($callsign)")
                                    dataRepository.storeCallsign(callsign)
                                }
                                if (teamChanged) {
                                    Log.d(TAG, "Storing Team ($team)")
                                    dataRepository.storeTeam(team)
                                }
                                if (roleChanged) {
                                    Log.d(TAG, "Storing Role ($role)")
                                    dataRepository.storeRole(role)
                                }
//                                    if(licenseChanged) {
//                                        dataRepository.storeLicense(license)
//
//                                    }
                                if (saveBattOnWifiChanged) {
                                    Log.d(TAG, "Storing batt optimization on wifi: enable($enableWifiBatteryOptimization), allWifi($enableAllWifiBatteryOptimization), wifiSsidList=($wifiSsidList)")
                                    runBlocking {
                                        dataRepository.storeEnableWifiBatteryOptimization(
                                            enableWifiBatteryOptimization
                                        )
                                        dataRepository.storeEnableAllWifiBatteryOptimization(
                                            enableAllWifiBatteryOptimization
                                        )
                                        dataRepository.storeWifiSsidList(wifiSsidList)
                                    }
                                    EventBus.getDefault().post(MessageEvent(
                                        OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED, 0))
                                }
                                if (takServerListChanged) {
                                    Log.d(TAG, "Storing updated TAK Server List: $takServerList")
                                    dataRepository.storeTakServerList(takServerList)
                                }

                                if (locationCheckedChanged) {
                                    Log.d(TAG, "Storing enableLocationService $enableLocationService")
                                    dataRepository.storeEnableLocationService(enableLocationService)
                                }
                                if (biometricsCheckedChanged) {
                                    Log.d(TAG, "Storing enableBiometricsService $enableBiometricsService")
                                    dataRepository.storeEnableBiometricsService(
                                        enableBiometricsService
                                    )
                                }
                                if (dittoCheckedChanged) {
                                    Log.d(TAG, "Storing enableDittoService $enableDittoService")
                                    dataRepository.storeEnableDittoService(enableDittoService)
                                }

                                Log.d(TAG, "Finished saving settings")
                            }
                        } else {
                            Log.v(TAG, "Settings not changed and hence NOT saved")
                        }
//                        navController.navigate("main_screen") {
//                            popUpTo(("main_screen")) {
//                                inclusive = true
//                            }
//                        }
                          context.startActivity(Intent(context, MainActivity::class.java))

                    })
            }

        }
    }
}

@Composable
fun ConfirmCancelButtons(
    onClickCancel: () -> Unit,
    onClickConfirm: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
    ) {
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            IconButton(
                onClick = onClickCancel,
                modifier = Modifier
                    .size(50.dp)
                    .border(1.dp, color = Color.Black, shape = CircleShape),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.Red,
                    containerColor = Color.DarkGray,
                ),
            ) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Close icon")

            }
            Spacer(modifier = Modifier.width(20.dp))
            IconButton(
                onClick = onClickConfirm,
                modifier = Modifier
                    .size(50.dp)
                    .border(1.dp, color = Color.Black, shape = CircleShape),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.Black,
                    containerColor = colors.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Confirm icon",
                    tint = Color.Black
                )

            }
        }
    }
}

fun requestHeartRateComplicationUpdate(context: Context) {
    val componentName = ComponentName(context, HeartRateComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}