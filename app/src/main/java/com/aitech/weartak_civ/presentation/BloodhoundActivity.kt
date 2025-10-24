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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.presentation.BloodhoundActivity.Constants.bloodhoundDefaultList
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.MutableLiveData
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material.ChipDefaults.gradientBackgroundChipColors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.complication.BloodhoundComplicationService
import com.aitech.weartak_civ.presentation.BloodhoundActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.BloodhoundActivity.Constants.activeBhoundItem
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableChatServiceSettingsState

class BloodhoundActivity  : ComponentActivity() {
    companion object Constants{
        lateinit var bloodhoundActivity : BloodhoundActivity
        val TAG = "WearTAK-" + BloodhoundActivity::class.java.simpleName
        var bloodhoundDefaultList = mutableStateListOf(
            Utils.BloodhoundItem(
                target = "Denny's",
                uid = "",
                callsign = "TAB-01",
                timeReceived = 1760459784,
                distance = 89.4,
                orientation = 104F,
                bloodhounding = false
            ),
            Utils.BloodhoundItem(
                target = "John",
                uid = "",
                callsign = "PHN-34",
                timeReceived = 1760459784,
                distance = 103.1,
                orientation = 54F,
                bloodhounding = false
            ),
            Utils.BloodhoundItem(
                target = "Hostile Marker",
                uid = "",
                callsign = "ALPHACRISIS",
                timeReceived = 1760459784,
                distance = 174.3,
                orientation = 134F,
                bloodhounding = false
            )
        )
        var activeBhoundItem = MutableLiveData<Utils.BloodhoundItem>(null)

    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bloodhoundActivity = this
        enableEdgeToEdge()
        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    BloodhoundCivApp(exitActivity = {finishAndRemoveTask()}, context)
                }
            }
        }
    }
}


@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun BloodhoundCivApp(exitActivity: () -> Unit, context: Context) {
    val navController = rememberSwipeDismissableNavController()
    val licenseKey by dataRepository.getLicense.collectAsState(initial = "License")
    val chatEnabledLive by enableChatServiceSettingsState.observeAsState()

//    val bloodhoundDefaultList by dataRepository.bloodhoundItemsFlow.collectAsState(initial = emptyList())
    // react to changes in the license.
    LaunchedEffect(licenseKey) {
        val currentDestination = navController.currentBackStackEntry?.destination?.route
        val isValid = isLicenseValid()

        when {
            !isValid && currentDestination != "invalid_license_screen" -> {
                context.startActivity(Intent(context, SettingsActivity::class.java))
                exitActivity()
            }
            isValid && chatEnabledLive == true && currentDestination != "bloodhound_screen" -> {
                navController.navigate("bloodhound_screen") {
                    popUpTo(0) { inclusive = true } // clears the back stack
                }
            }
            isValid && chatEnabledLive == false && currentDestination != "bloodhound_disabled_screen" -> {
                navController.navigate("bloodhound_disabled_screen") {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination =
            if (isLicenseValid()) {
                if (chatEnabledLive == true) "bloodhound_screen" else "bloodhound_disabled_screen"
            } else {
                "invalid_license_screen"
            }
    ) {
        composable("bloodhound_screen" ) {
            BloodhoundScreen(bloodhoundDefaultList, navController, exitActivity, context)
        }

        composable("bloodhound_disabled_screen") {
//            ChatDisabledScreen(navController, exitActivity)
            ActivityDisabledScreen(
                navController,
                exitActivity,
                stringResource(R.string.bloodhound_disabled),
                "Please enable Chat in order to use this feature"
            )
        }

        composable(
            "bloodhound_screen_detailed/{index}",
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: -1
            Log.d(TAG, "bloodhound_screen_detailed index: $index")
            BloodhoundScreenDetailed(navController, index, bloodhoundDefaultList, context,{ exitActivity() })
        }

        composable(
            "invalid_license_screen") {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}


@Composable
fun BloodhoundScreen(
    bloodhounds: List<Utils.BloodhoundItem>,
    navController: NavController,
    exitActivity: () -> Unit,
    context: Context
) {
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
                    .padding(bottom = 30.dp)
                    .verticalScroll(rememberScrollState()),

                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    painter = painterResource(R.drawable.bloodhound),
                    contentDescription = "Favorite icon",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(25.dp),
                )
                Text(
                    text = stringResource(R.string.bloodhound),
                    fontSize = if ("text".length!! < 20) 15.sp else 13.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(vertical = 10.dp, horizontal = 20.dp)
                        .align(Alignment.CenterHorizontally)
                )
                LazyColumn(modifier = Modifier
                    .height(200.dp)){
                    itemsIndexed(bloodhounds){ index, bloodhoundItem ->
                        BloodhoundChip(
                            bloodhoundItem.target,
                            bloodhoundItem.distance,
                            bloodhoundItem.bloodhounding,
                            bloodhoundItem.orientation,
                            {navController.navigate("bloodhound_screen_detailed/$index")}
                        )
                    }
                }
            }
            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                BackButton(
                    onClick = {
                        exitActivity()
                        context.startActivity(Intent(context, MainActivity::class.java))
                })
            }
        }
    }
}

@Composable
fun BloodhoundChip(
    title: String,
    distance: Double,
    bloodhounding: Boolean,
    directionDegrees: Float,
    onClick: () -> Unit
) {
    val gradientColors = listOf(
        MaterialTheme.colors.surface
            .copy(alpha = 0f)
            .compositeOver(MaterialTheme.colors.surface),
        MaterialTheme.colors.primary
            .copy(alpha = 0.5f)
            .compositeOver(MaterialTheme.colors.surface),
    )
    Chip(
        icon = {
            RotatingArrowIcon(
                degrees = directionDegrees,
                color = Color.White,
                modifier = Modifier.size(ChipDefaults.IconSize)
            )
        },
        colors =
            if (bloodhounding) {
                gradientBackgroundChipColors(
                    startBackgroundColor =
                        colors.primary
                            .copy(alpha = 0.5f)
                            .compositeOver(colors.surface),
                    endBackgroundColor =
                        colors.surface
                            .copy(alpha = 0f)
                            .compositeOver(colors.surface),
                )
            } else {
                ChipDefaults.chipColors(
                    backgroundColor = Color.DarkGray,
                    contentColor = Color.White,
                    secondaryContentColor = Color.LightGray
                )
            },
        label = {
            Text(
                text = title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.CenterVertically),
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        },
        secondaryLabel = {
            Text(text = "$distance" + stringResource(R.string.km),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                fontSize = 13.sp
            )
        },
        enabled = true,
        onClick = { onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 3.dp)
    )
    if (bloodhounding){
        Spacer(modifier = Modifier.height(10.dp))

    }
}

@Composable
fun BloodhoundScreenDetailed(
    navController: NavController,
    index: Int,
    bloodhoundList: List<Utils.BloodhoundItem>,
    context: Context,
    exitActivity: () -> Unit
) {
    val bloodhoundItem = bloodhoundList.getOrNull(index)
    val bhoundTarget = bloodhoundItem?.target.orEmpty()
    val bhoundCallsign = bloodhoundItem?.callsign.orEmpty()
    val bhoundTimeReceived = bloodhoundItem!!.timeReceived
    val bhoundDistance = bloodhoundItem.distance
    val bhoundOrientation = bloodhoundItem.orientation
    var bhoundBloodhounding = bloodhoundItem.bloodhounding

    WearTakCivScreenDimensions(
    columnContent = {
        Text(
            //TODO: WEAR-161: Translate "Status" to stringResource that already exists when merged
            text = "Status: " + bloodhoundingTextFromString(bhoundBloodhounding),
            fontSize = 14.sp,
            overflow = TextOverflow.Ellipsis,
            color = if (bhoundBloodhounding) Color.White else Color.LightGray,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        DetailedBloodhoundInfo(
            distance = bhoundDistance,
            callsign = bhoundCallsign,
            timeReceived = bhoundTimeReceived,
            orientation = bhoundOrientation
        )
        if (bhoundBloodhounding){
            Button(
                onClick = {
                    exitActivity()
                    bloodhoundDefaultList.removeAt(index)
                    activeBhoundItem.value = null
                    requestBloodhoundComplicationUpdate(context)
                },
                modifier = Modifier.padding(top = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = Color.Black
                ),
            ) {
                Text(
                    text = stringResource(R.string.npos),
                    fontSize = 15.sp,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.Black,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            //TODO: WEAR-161: Add when chat backend is finished
//            Button(
//                onClick = {},
//                modifier = Modifier.padding(top = 7.dp),
//                colors = ButtonDefaults.buttonColors(
//                    containerColor = Color.DarkGray,
//                    contentColor = Color.White
//                ),
//                contentPadding = PaddingValues(10.dp)
//            ) {
//                Icon(
//                    painterResource(R.drawable.chat_nomessage),
//                    modifier = Modifier.size(30.dp),
//                    contentDescription = "Chat",
//                    tint = colors.primary
//                )
//                Spacer(modifier = Modifier.width(5.dp))
//                Text(
//                    text = stringResource(R.string.view_chat),
//                    fontSize = 13.sp,
//                    overflow = TextOverflow.Ellipsis,
//                    fontWeight = FontWeight.SemiBold,
//                )
//            }
        } else {
            Button(
                onClick = {
                    activeBhoundItem.value = bloodhoundItem
                    requestBloodhoundComplicationUpdate(context)
                    setActiveBloodhound(index)
                    Log.d(TAG, bloodhoundDefaultList.toString())
                    exitActivity()
                },
                modifier = Modifier.padding(top = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = Color.Black
                ),
            ) {
                Text(
                    text = stringResource(R.string.rgr),
                    fontSize = 15.sp,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.Black,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    },
    rowContent = {
        BackButton(
            onClick = {
                navController.navigate("bloodhound_screen") {
                    popUpTo("bloodhound_screen") { inclusive = true }
                }
            })
    },
    text = bhoundTarget
)
}

@Composable
fun bloodhoundingTextFromString(bloodhounding: Boolean?): String {
    val bhoundString: String = when (bloodhounding) {
        true -> stringResource(R.string.bloodhounding)
        false -> stringResource(R.string.not_bloodhounding)
        null -> "NULL"
    }
    return bhoundString
}

@Composable
fun DetailedBloodhoundInfo(
    distance: Double?,
    callsign: String,
    timeReceived: Long,
    orientation: Float
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ){
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ){
            RotatingArrowIcon(
                    degrees = orientation,
                    color = Color.White,
                    modifier = Modifier.size(ChipDefaults.IconSize)
            )
            Text(
                text = orientation.toString(),
                fontSize = 13.sp,
                overflow = TextOverflow.Ellipsis,
                color = Color.LightGray,
                fontWeight = FontWeight.Normal
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(){
            Text(
                text = distance.toString() + stringResource(R.string.km),
                fontSize = 18.sp,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = callsign,
                fontSize = 15.sp,
                overflow = TextOverflow.Ellipsis,
                color = Color.LightGray,
                fontWeight = FontWeight.Normal
            )
            Text(
                text = stringResource(R.string.sent) + utcToHoursMinutes(timeReceived),
                fontSize = 15.sp,
                overflow = TextOverflow.Ellipsis,
                color = Color.LightGray,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

@Composable
fun RotatingArrowIcon(
    degrees: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = degrees.mod(360f),
        label = "bearing-rotation"
    )

    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = rotation }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val pad = minOf(w, h) * 0.1f
            val iw = w - pad * 2
            val ih = h - pad

            // Same arrowhead, thinner shaft
            val path = Path().apply {
                // Tip
                moveTo(pad + iw * 0.5f, pad)
                // Right wing outer
                lineTo(pad + iw * 0.8f, pad + ih * 0.45f)
                // Shaft right (thinner)
                lineTo(pad + iw * 0.55f, pad + ih * 0.45f)
                lineTo(pad + iw * 0.55f, pad + ih)
                // Shaft left (thinner)
                lineTo(pad + iw * 0.45f, pad + ih)
                lineTo(pad + iw * 0.45f, pad + ih * 0.45f)
                // Left wing outer
                lineTo(pad + iw * 0.2f, pad + ih * 0.45f)
                close()
            }

            drawPath(path = path, color = color, style = Fill)
        }
    }
}



fun requestBloodhoundComplicationUpdate(context: Context) {
    Log.d(MainActivity.Constants.TAG, "requestBloodhoundComplicationUpdate called")
    val componentName = ComponentName(context, BloodhoundComplicationService::class.java)

    val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    requester.requestUpdateAll()
}

fun isAnyBloodhoundingActive(/*items: List<Utils.BloodhoundItem>*/): Boolean {
    val items = bloodhoundDefaultList
    return items.any { it.bloodhounding }
}

fun setActiveBloodhound(index: Int) {
    // Iterate and update each item
    val bloodhoundList = bloodhoundDefaultList
//    for (i in bloodhoundList.indices) {
//        val currentItem = bloodhoundList[i]
//        // Only modify if state actually changes (to avoid redundant recompositions)
//        if (currentItem.bloodhounding != (i == index)) {
//            bloodhoundList[i] = currentItem.copy(bloodhounding = (i == index))
//        }
//    }
    if (index !in bloodhoundList.indices) return

    // Copy the selected item and set it to active
    val selected = bloodhoundList[index].copy(bloodhounding = true)

    // Build a list of all non-active items, setting them to false and sorting by distance
    val others = bloodhoundList
        .filterIndexed { i, _ -> i != index }
        .map { it.copy(bloodhounding = false) }
        .sortedBy { it.distance }

    // Combine the selected item first, followed by the sorted others
    val reorderedList = listOf(selected) + others

    // Update the state list to trigger recomposition
    bloodhoundList.clear()
    bloodhoundList.addAll(reorderedList)
}

@Preview(widthDp = 192, heightDp = 300, showBackground = true)
@Composable
fun BloodhoundScreenPreview() {
    BloodhoundScreen(bloodhoundDefaultList, rememberSwipeDismissableNavController(), {}, LocalContext.current)
}
@Preview(widthDp = 192, heightDp = 300, showBackground = true)
@Composable
fun BloodhoundDetailedScreenPreview() {
    BloodhoundScreenDetailed(rememberSwipeDismissableNavController(), 0, bloodhoundDefaultList, LocalContext.current, {})
}
