package com.aitech.weartak_civ.presentation

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Icon
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.CompanionAppRepository.BleConnectionState
import com.aitech.weartak_civ.data.DataRepository.SitxServerStatus
import com.aitech.weartak_civ.manager.NetworkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.viewmodel.WearTAKSettingsViewModel
import com.aitech.weartak_civ.service.DittoService
import com.aitech.weartak_civ.service.TAKConnectService

internal enum class ConnectionRoute(val icon: Int, val label: Int) {
    DITTO(R.drawable.ic_integration_ditto, R.string.ditto),
    COMPANION(R.drawable.weartak_companion_plugin, R.string.companion_app),
    TAK(R.drawable.tak_server_connected, R.string.tak_servers),
    MULTICAST(R.drawable.multicast_connected, R.string.connection_route_multicast),
    SITX(R.drawable.ic_connection_sitx, R.string.connection_route_sitx)
}

internal enum class RouteConnectionState(val label: Int) {
    CONNECTED(R.string.tak_status_connected),
    CONNECTING(R.string.companion_status_connecting),
    OFF(R.string.tak_status_not_connected),
    SUPPRESSED(R.string.connection_route_suppressed),
    PARTIAL(R.string.some_servers_connected)
}

internal data class ConnectionRouteStatus(
    val route: ConnectionRoute,
    val state: RouteConnectionState,
    val connectedServers: Int = 0,
    val totalServers: Int = 0
)

internal data class RouteTakServerStatus(val enabled: Boolean, val status: String = "DISCONNECTED")

internal data class ConnectionRouteSnapshot(
    val dittoEnabled: Boolean = false,
    val dittoSyncActive: Boolean = false,
    val dittoReconfiguring: Boolean = false,
    val companionEnabled: Boolean = false,
    val companionReady: Boolean = false,
    val companionBle: BleConnectionState = BleConnectionState.NOT_CONNECTED,
    val disconnectGrace: Boolean = false,
    val takEnabled: Boolean = false,
    val takServers: List<RouteTakServerStatus> = emptyList(),
    val multicastEnabled: Boolean = false,
    val multicastRunning: Boolean = false,
    val sitxEnabled: Boolean = false,
    val sitxState: SitxServerStatus = SitxServerStatus.DISCONNECTED
)

internal fun connectionRouteStatuses(snapshot: ConnectionRouteSnapshot): List<ConnectionRouteStatus> =
    with(snapshot) {
        val suppressed = companionReady || disconnectGrace
        val takServerStates = takServers.filter { it.enabled }.map { it.status }
        fun state(connected: Boolean, connecting: Boolean = false, direct: Boolean = true) = when {
            direct && suppressed -> RouteConnectionState.SUPPRESSED
            connected -> RouteConnectionState.CONNECTED
            connecting -> RouteConnectionState.CONNECTING
            else -> RouteConnectionState.OFF
        }
        buildList {
            if (dittoEnabled) add(ConnectionRouteStatus(ConnectionRoute.DITTO,
                state(dittoSyncActive && !dittoReconfiguring, dittoReconfiguring)))
            if (companionEnabled) add(ConnectionRouteStatus(ConnectionRoute.COMPANION,
                state(companionReady, companionBle in setOf(
                    BleConnectionState.ADVERTISING, BleConnectionState.CONNECTING,
                    BleConnectionState.CONNECTED, BleConnectionState.SERVICES_READY
                ), direct = false)))
            if (takEnabled && takServerStates.isNotEmpty()) {
                val connected = takServerStates.count { it == "CONNECTED" }
                val status = when {
                    suppressed -> RouteConnectionState.SUPPRESSED
                    connected == takServerStates.size -> RouteConnectionState.CONNECTED
                    connected > 0 -> RouteConnectionState.PARTIAL
                    takServerStates.any { it == "CONNECTING" } -> RouteConnectionState.CONNECTING
                    else -> RouteConnectionState.OFF
                }
                add(ConnectionRouteStatus(ConnectionRoute.TAK, status, connected, takServerStates.size))
            }
            if (multicastEnabled) add(ConnectionRouteStatus(ConnectionRoute.MULTICAST,
                state(multicastRunning)))
            if (sitxEnabled) add(ConnectionRouteStatus(ConnectionRoute.SITX,
                state(sitxState == SitxServerStatus.CONNECTED, sitxState == SitxServerStatus.CONNECTING)))
        }
    }

@Composable
internal fun HomeConnectionStatus(settingsVm: WearTAKSettingsViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as WearTakApplication
    val dittoEnabled by settingsVm.savedEnableDittoService.collectAsStateWithLifecycle()
    val companionEnabled by settingsVm.savedEnableCompanionAppService.collectAsStateWithLifecycle()
    val takEnabled by settingsVm.savedEnableTakEndpoint.collectAsStateWithLifecycle()
    val multicastEnabled by settingsVm.savedEnableMulticast.collectAsStateWithLifecycle()
    val sitxEnabled by settingsVm.savedEnableSitx.collectAsStateWithLifecycle()
    val location by settingsVm.savedEnableLocationService.collectAsStateWithLifecycle()
    val servers by settingsVm.savedTakServerList.collectAsStateWithLifecycle()
    val runtimes by TAKConnectService.takServerRuntimeDetails.collectAsStateWithLifecycle()
    val serverStates = servers.map { server ->
        key(server.serverId) {
            val runtime = runtimes.firstOrNull { it.serverId == server.serverId }
            val status = if (server.isEnabled) runtime?.connectionStatusLiveData?.observeAsState()?.value else null
            RouteTakServerStatus(server.isEnabled, status ?: "DISCONNECTED")
        }
    }
    val companionReady by dataRepository.isCompanionConnected.collectAsStateWithLifecycle()
    val grace by dataRepository.companionDisconnectGraceActive.collectAsStateWithLifecycle()
    val ble by app.capRepo.bleConnectionState.collectAsStateWithLifecycle()
    val dittoActive by DittoService.syncActive.collectAsStateWithLifecycle()
    val dittoReconfiguring by DittoService.isReconfiguring.collectAsStateWithLifecycle()
    val multicastRunning by dataRepository.multicastPublisherRunning.collectAsStateWithLifecycle()
    val sitxState by dataRepository.sitxStatus.collectAsStateWithLifecycle()
    val wifi by NetworkManager.isWifiConnectedLiveData.observeAsState(false)
    val cell by NetworkManager.isCellularConnectedLiveData.observeAsState(false)
    val airplane by dataRepository.airplaneOnSuccess.collectAsStateWithLifecycle()
    val battery by dataRepository.nonNullBatteryPercentage.collectAsStateWithLifecycle(initialValue = 100)
    val routes = connectionRouteStatuses(ConnectionRouteSnapshot(
        dittoEnabled = dittoEnabled,
        dittoSyncActive = dittoActive,
        dittoReconfiguring = dittoReconfiguring,
        companionEnabled = companionEnabled,
        companionReady = companionReady,
        companionBle = ble,
        disconnectGrace = grace,
        takEnabled = takEnabled,
        takServers = serverStates,
        multicastEnabled = multicastEnabled,
        multicastRunning = multicastRunning,
        sitxEnabled = sitxEnabled,
        sitxState = sitxState
    ))
    LaunchedEffect(wifi, cell, companionReady, location, serverStates, sitxState, multicastRunning, airplane) {
        requestStatusIconsComplicationUpdate(context)
    }
    ConnectionStatusIcons(
        routes = routes,
        wifi = wifi == true,
        cell = cell == true,
        bluetooth = ble == BleConnectionState.CONNECTED || ble == BleConnectionState.SERVICES_READY,
        showBluetooth = companionEnabled,
        airplane = airplane,
        location = location,
        battery = battery ?: 100,
        onNetworkPreferences = {
            context.startActivity(Intent(context, WearTAKSettingsActivity::class.java).putExtra(
                WearTAKSettingsActivity.EXTRA_OPEN_NETWORK_PREFERENCES, true))
        })
}

@Composable
internal fun ConnectionStatusIcons(
    routes: List<ConnectionRouteStatus>,
    wifi: Boolean,
    cell: Boolean,
    bluetooth: Boolean,
    showBluetooth: Boolean,
    airplane: Boolean,
    location: Boolean,
    battery: Int,
    onNetworkPreferences: () -> Unit
) {
    val openNetwork = stringResource(R.string.network_preferences)
    Box(Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = 182.dp).height(80.dp)) {
        if (routes.isNotEmpty()) {
            // Five 16dp glyphs + four 4dp gaps fit the top chord of a 192dp round watch.
            Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                .clickable(onClickLabel = openNetwork, role = Role.Button, onClick = onNetworkPreferences),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                routes.forEach { RouteStatusIcon(it) }
            }
        }
        Column(Modifier.align(Alignment.TopStart).offset(x = 14.dp, y = 34.dp)
            .clickable(onClickLabel = openNetwork, role = Role.Button, onClick = onNetworkPreferences),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (wifi) MediumIcon(R.drawable.wifi, R.string.wifi)
                if (cell) MediumIcon(R.drawable.cell, R.string.cell)
                if (!wifi && !cell) MediumIcon(
                    if (airplane) R.drawable.airplane_mode else R.drawable.no_cell_wifi,
                    if (airplane) R.string.airplane_mode else R.string.no_connection)
            }
            if (showBluetooth) MediumIcon(R.drawable.bluetooth, R.string.bluetooth,
                if (bluetooth) Color.White else Color.Gray,
                if (bluetooth) R.string.tak_status_connected else R.string.tak_status_not_connected)
        }
        ReportingRateLocationIcon(location, Modifier.align(Alignment.TopEnd).offset(x = (-22).dp, y = 30.dp))
        val batteryIcon = when {
            battery <= 20 -> R.drawable.battery_1_bar
            battery <= 40 -> R.drawable.battery_3
            battery <= 60 -> R.drawable.battery_4_bar
            battery <= 80 -> R.drawable.battery_5
            else -> R.drawable.battery_full
        }
        Icon(painterResource(batteryIcon), stringResource(R.string.connection_battery, battery),
            Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = 58.dp).size(18.dp),
            tint = if (battery <= 20) Color.Red else Color.White)
    }
}

@Composable
private fun MediumIcon(icon: Int, label: Int, tint: Color = Color.White, state: Int? = null) {
    val description = if (state == null) stringResource(label) else
        stringResource(R.string.connection_route_status, stringResource(label), stringResource(state))
    Icon(painterResource(icon), description, Modifier.size(16.dp), tint = tint)
}

@Composable
private fun RouteStatusIcon(status: ConnectionRouteStatus) {
    val state = status.state
    val tint = when (state) {
        RouteConnectionState.CONNECTED, RouteConnectionState.PARTIAL -> Color.White
        RouteConnectionState.CONNECTING -> Color(0xFFFFC107)
        RouteConnectionState.OFF, RouteConnectionState.SUPPRESSED -> Color.Gray
    }
    val description = if (status.route == ConnectionRoute.TAK &&
        state in setOf(RouteConnectionState.CONNECTED, RouteConnectionState.PARTIAL)) {
        stringResource(R.string.connection_servers_status, status.connectedServers, status.totalServers)
    } else stringResource(R.string.connection_route_status,
        stringResource(status.route.label), stringResource(state.label))
    Box(Modifier.size(16.dp)) {
        Icon(painterResource(status.route.icon), description, Modifier.fillMaxSize(), tint = tint)
        Canvas(Modifier.align(Alignment.BottomEnd).size(6.dp)) {
            drawCircle(Color.Black)
            val center = this.center
            val radius = size.minDimension / 3
            when (state) {
                RouteConnectionState.CONNECTED -> drawCircle(Color.Green, radius)
                RouteConnectionState.CONNECTING, RouteConnectionState.PARTIAL ->
                    drawCircle(Color(0xFFFFC107), radius, style = Stroke(1.dp.toPx()))
                RouteConnectionState.OFF ->
                    drawLine(Color.Gray, Offset(0f, center.y), Offset(size.width, center.y), 1.dp.toPx())
                RouteConnectionState.SUPPRESSED ->
                    drawLine(Color.Gray, Offset(0f, size.height), Offset(size.width, 0f), 1.dp.toPx())
            }
        }
    }
}
