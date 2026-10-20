package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.RouteFollow
import com.aitech.weartak_civ.data.RouteFollowSession
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.TakRoutePoint
import com.aitech.weartak_civ.ui.formatter.DistanceFormatter

@Composable
internal fun routeDistanceText(meters: Double?): String = if (meters == null) {
    stringResource(R.string.route_bloodhound_distance_unknown)
} else DistanceFormatter.format(meters.div(1000.0))
    .text(metersLabel = " " + stringResource(R.string.m), kilometersLabel = " " + stringResource(R.string.km))

private val PickerGreen = Color(0xFF216C32)
private val PickerRed = Color(0xFFB3261E)
private val PickerAmber = Color(0xFFFFC107)

@Composable
fun BloodhoundPickerList(
    bloodhounds: List<Utils.BloodhoundItem>,
    routes: List<TakRoute>,
    location: TakRoutePoint?,
    onTarget: (String) -> Unit,
    onRoute: (TakRoute) -> Unit,
    modifier: Modifier = Modifier,
    session: RouteFollowSession? = null,
    onMapTarget: (String) -> Unit = {},
    onMapRoute: (TakRoute) -> Unit = {},
    onStartTarget: (String) -> Unit = {},
    onChooseRoute: (TakRoute) -> Unit = {},
    onStopTarget: (String) -> Unit = {},
    onStopRoute: () -> Unit = {},
    header: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {}
) {
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { header() }
        items(routes, key = { "route:${it.serverId.length}:${it.serverId}:${it.uid}" }) { route ->
            RouteBloodhoundChip(route, location, { onRoute(route) }, session,
                { onMapRoute(route) }, { onChooseRoute(route) }, onStopRoute)
        }
        items(bloodhounds, key = { "target:${it.uid}" }) { item ->
            val kind = when (item.targetKind) {
                Utils.BloodhoundTargetKind.CONTACT -> stringResource(R.string.bloodhound_picker_contact)
                Utils.BloodhoundTargetKind.POINT -> stringResource(R.string.bloodhound_picker_point)
                Utils.BloodhoundTargetKind.ALERT -> item.alertCategory ?: stringResource(R.string.remote_emergency_alert)
                Utils.BloodhoundTargetKind.MARKER -> stringResource(R.string.bloodhound_picker_marker)
            }
            val subtitle = if (item.isStale) stringResource(R.string.remote_emergency_stale_category, kind)
                else stringResource(R.string.bloodhound_picker_target_summary, kind,
                    routeDistanceText(item.distance?.times(1000.0).takeIf { item.hasLocation }))
            BloodhoundPickerRow(item.target, subtitle, "?", item.bloodhounding,
                onBody = { onTarget(item.uid) }, onMap = { onMapTarget(item.uid) },
                onHound = { if (item.bloodhounding) onStopTarget(item.uid) else onStartTarget(item.uid) },
                actionsEnabled = item.hasLocation, tag = "bloodhound-target-${item.uid}")
        }
        item { footer() }
    }
}

@Composable
fun RouteBloodhoundChip(
    route: TakRoute,
    location: TakRoutePoint?,
    onClick: () -> Unit,
    session: RouteFollowSession? = null,
    onMap: () -> Unit = {},
    onChoose: () -> Unit = {},
    onStop: () -> Unit = {}
) {
    val nearestMeters = remember(route.points, location) {
        location?.takeIf(RouteFollow::validLocation)?.let { current ->
            route.points.minOfOrNull { RouteFollow.distanceMeters(current, it) }
        }
    }
    val selected = session?.takeIf { it.matches(route) && !it.navigation.completed }
    val subtitle = if (selected != null) stringResource(R.string.bloodhound_picker_route_progress,
        selected.navigation.pointNumber, selected.navigation.points.size, routeDistanceText(selected.remainingMeters))
    else stringResource(R.string.bloodhound_picker_route_summary, route.points.size, routeDistanceText(nearestMeters))
    BloodhoundPickerRow(route.name.ifBlank { stringResource(R.string.route_unnamed) }, subtitle, "?",
        selected != null, onClick, onMap, { if (selected != null) onStop() else onChoose() },
        actionsEnabled = route.points.isNotEmpty(), tag = "bloodhound-route")
}

@Composable
fun BloodhoundPickerRow(
    title: String,
    subtitle: String,
    glyph: String,
    active: Boolean,
    onBody: () -> Unit,
    onMap: () -> Unit,
    onHound: () -> Unit,
    actionsEnabled: Boolean = true,
    tag: String = "bloodhound-row"
) {
    val shape = RoundedCornerShape(28.dp)
    val houndDescription = stringResource(
        if (active) R.string.bloodhound_picker_stop else R.string.bloodhound_picker_start, title)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .background(if (active) Color(0xFF3B2D00) else Color(0xFF202124), shape)
            .then(if (active) Modifier.border(1.dp, PickerAmber, shape) else Modifier)
            .padding(horizontal = 4.dp, vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f).clickable(onClick = onBody).padding(start = 4.dp, end = 2.dp, top = 8.dp, bottom = 8.dp)
            .testTag("$tag-body"), verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, color = Color.White, modifier = Modifier.padding(end = 4.dp))
            Column {
                Text(title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp)
                Text(subtitle, color = if (active) Color(0xFFFFE082) else Color(0xFFCDD0D4),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
            }
        }
        Button(onClick = onMap, enabled = actionsEnabled, modifier = Modifier.size(40.dp).testTag("$tag-map"),
            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF35383C))) {
            Icon(painterResource(R.drawable.atak_ic_crosshair),
                contentDescription = stringResource(R.string.bloodhound_picker_show_map, title),
                tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(4.dp))
        Button(onClick = onHound, enabled = actionsEnabled || active,
            modifier = Modifier.size(40.dp).testTag("$tag-hound")
                .semantics { contentDescription = houndDescription },
            colors = ButtonDefaults.buttonColors(backgroundColor = if (active) PickerRed else PickerGreen)) {
            if (active) {
                Box(Modifier.size(12.dp).background(Color.White).testTag("$tag-stop-icon"))
            } else {
                Icon(painterResource(R.drawable.atak_nav_bloodhound),
                    contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
fun RouteBloodhoundDetails(
    route: TakRoute,
    session: RouteFollowSession?,
    location: TakRoutePoint?,
    target: Utils.BloodhoundItem?,
    onStart: (RouteFollow.StartMode) -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    choosingStart: Boolean = false
) {
    val selected = session?.takeIf { it.matches(route) }
    val following = selected != null && !selected.navigation.completed
    val validRoute = remember(route.points) {
        route.points.isNotEmpty() && route.points.all(RouteFollow::validLocation)
    }
    val hasLocation = location?.let(RouteFollow::validLocation) == true
    val listState = rememberScalingLazyListState()
    Scaffold(
        modifier = Modifier.background(Color.Black),
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("route-bloodhound-details"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item { Text(route.name.ifBlank { stringResource(R.string.route_unnamed) }, textAlign = TextAlign.Center) }
            item { Text(stringResource(if (choosingStart) R.string.bloodhound_picker_start_title
                else R.string.route_bloodhound_summary, route.points.size), textAlign = TextAlign.Center) }
            if (selected != null) {
                item { Text(stringResource(R.string.route_bloodhound_progress,
                    selected.navigation.pointNumber, selected.navigation.points.size), textAlign = TextAlign.Center) }
                if (selected.navigation.completed) {
                    item { Text(stringResource(R.string.route_bloodhound_complete), textAlign = TextAlign.Center) }
                } else {
                    item { Text(stringResource(R.string.route_bloodhound_remaining,
                        routeDistanceText(selected.remainingMeters)), textAlign = TextAlign.Center) }
                    if (target?.uid == selected.uid) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RotatingArrowIcon(target.orientation, Color.White, Modifier.size(ChipDefaults.IconSize))
                                Spacer(Modifier.width(8.dp))
                                Text(routeDistanceText(target.distance?.times(1000.0)))
                            }
                        }
                    }
                }
            }
            if (following) {
                item {
                    Chip(onClick = onStop, label = { Text(stringResource(R.string.route_bloodhound_stop)) },
                        modifier = Modifier.fillMaxWidth().testTag("route-follow-stop"))
                }
            } else {
                if (!validRoute) {
                    item { Text(stringResource(R.string.route_bloodhound_invalid), textAlign = TextAlign.Center) }
                }
                if (!hasLocation) {
                    item { Text(stringResource(R.string.route_bloodhound_no_location), textAlign = TextAlign.Center) }
                }
                item {
                    Chip(onClick = { onStart(RouteFollow.StartMode.NEAREST) }, enabled = validRoute && hasLocation,
                        label = { Text(stringResource(R.string.route_bloodhound_start_nearest)) },
                        icon = { Icon(painterResource(R.drawable.atak_nav_bloodhound), contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(24.dp)) },
                        colors = ChipDefaults.chipColors(backgroundColor = PickerGreen, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().testTag("route-follow-nearest"))
                }
                item {
                    Chip(onClick = { onStart(RouteFollow.StartMode.BEGINNING) }, enabled = validRoute,
                        label = { Text(stringResource(R.string.route_bloodhound_start_beginning)) },
                        modifier = Modifier.fillMaxWidth().testTag("route-follow-beginning"))
                }
            }
            item {
                Chip(onClick = onBack, label = { Text(stringResource(
                    if (choosingStart) R.string.route_cancel else R.string.route_back)) },
                    modifier = Modifier.fillMaxWidth().testTag("route-follow-back"))
            }
        }
    }
}
