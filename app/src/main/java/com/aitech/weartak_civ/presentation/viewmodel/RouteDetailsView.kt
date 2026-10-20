package com.aitech.weartak_civ.presentation.viewmodel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.model.TakRoute

@Composable
fun RouteDetailsView(
    route: TakRoute,
    onPoint: (Int) -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit
) {
    var confirmingRemoval by remember(route.serverId, route.uid) { mutableStateOf(false) }
    val listState = key(confirmingRemoval) { rememberScalingLazyListState() }
    BackHandler {
        if (confirmingRemoval) confirmingRemoval = false else onBack()
    }
    Scaffold(
        modifier = Modifier.background(Color.Black),
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("route-details"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    route.name.ifBlank { stringResource(R.string.route_unnamed) },
                    textAlign = TextAlign.Center
                )
            }
            if (confirmingRemoval) {
                item {
                    Text(stringResource(R.string.route_remove_explanation), textAlign = TextAlign.Center)
                }
                item {
                    Chip(
                        onClick = onRemove,
                        label = { Text(stringResource(R.string.route_remove_confirm)) },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth().testTag("route-remove-confirm")
                    )
                }
                item {
                    Chip(
                        onClick = { confirmingRemoval = false },
                        label = { Text(stringResource(R.string.route_cancel)) },
                        modifier = Modifier.fillMaxWidth().testTag("route-remove-cancel")
                    )
                }
            } else {
                item {
                    Text(stringResource(R.string.route_point_count, route.points.size),
                        textAlign = TextAlign.Center)
                }
                item {
                    Chip(
                        onClick = { confirmingRemoval = true },
                        label = { Text(stringResource(R.string.route_remove)) },
                        modifier = Modifier.fillMaxWidth().testTag("route-remove")
                    )
                }
                items(route.points.size) { index ->
                    val point = route.points[index]
                    Chip(
                        onClick = { onPoint(index) },
                        label = {
                            Text(
                                point.name?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.route_point_number, index + 1),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        secondaryLabel = { Text(stringResource(R.string.route_point_open)) },
                        modifier = Modifier.fillMaxWidth().testTag("route-point-$index")
                    )
                }
                item {
                    Chip(
                        onClick = onBack,
                        label = { Text(stringResource(R.string.route_back)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
