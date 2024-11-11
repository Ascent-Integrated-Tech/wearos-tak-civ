/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter and
 * https://github.com/android/wear-os-samples/tree/main/ComposeAdvanced to find the most up to date
 * changes to the libraries and their usages.
 */

package com.aitech.weartak_civ.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            WearTAKCivApp("User")
        }
    }
}

@Composable
fun WearTAKCivApp(greetingName: String) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "main_screen") {
        composable("main_screen",
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() }) {
            MainScreen(navController, greetingName)
        }
        composable("settings_screen",
            enterTransition = { slideInVertically() },
            exitTransition = { slideOutVertically() }) {
            SettingsScreen(navController)
        }
    }
}

@Composable
fun MainScreen(navController: NavController, greetingName: String) {
    WearTAKCIVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            Greeting(greetingName = greetingName)
            Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings",
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.BottomCenter)
                    .clickable {
                        navController.navigate("settings_screen")
                    })
        }
    }
}

@Composable
fun Greeting(greetingName: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.primary,
        text = stringResource(R.string.hello_world, greetingName)
    )
}

@Composable
fun SettingsScreen(navController: NavController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        TimeText()
        Text(text = "This is the settings screen",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.primary,)
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Done",
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        navController.navigate("main_screen")
                    })
            Spacer(modifier = Modifier.width(12.dp))
            Icon(imageVector = Icons.Default.Check, contentDescription = "Done",
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        navController.navigate("main_screen")
                    })
        }
    }
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearTAKCivApp("Preview Android")
}