package com.aitech.weartak_civ.presentation

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Icon
import com.aitech.weartak_civ.R

private val settingsShortcutPages = setOf("reporting_strategy", "physiological_alerts")

internal fun settingsShortcutIntent(context: Context, route: String): Intent {
    require(route in settingsShortcutPages)
    return Intent(context, WearTAKSettingsActivity::class.java)
        .putExtra(WearTAKSettingsActivity.EXTRA_OPEN_SETTINGS_PAGE, route)
}

internal fun settingsShortcutRoute(intent: Intent): String? =
    intent.getStringExtra(WearTAKSettingsActivity.EXTRA_OPEN_SETTINGS_PAGE)?.takeIf { it in settingsShortcutPages }

@Composable
internal fun ReportingRateLocationIcon(enabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val label = stringResource(R.string.open_reporting_rate)
    Box(modifier.size(48.dp).clickable(onClickLabel = label, role = Role.Button) {
        context.startActivity(settingsShortcutIntent(context, "reporting_strategy"))
    }, contentAlignment = Alignment.Center) {
        Icon(painterResource(if (enabled) R.drawable.location_on else R.drawable.location_off),
            contentDescription = label, modifier = Modifier.size(20.dp),
            tint = if (enabled) Color.White else Color.DarkGray)
    }
}
