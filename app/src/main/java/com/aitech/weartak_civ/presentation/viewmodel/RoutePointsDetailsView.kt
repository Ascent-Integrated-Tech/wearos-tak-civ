package com.aitech.weartak_civ.presentation.viewmodel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import com.aitech.weartak_civ.data.model.TakRoute

data class RoutePointDetails(
    val routeUid: String,
    val pointIndex: Int,
    val title: String,
    val latitude: Double,
    val longitude: Double,
    val droppedBy: String? = null,
    val missionName: String? = null,
    val serverId: String = ""
)

fun findRoutePoint(
    routes: List<TakRoute>,
    serverId: String,
    routeUid: String,
    pointIndex: Int
): RoutePointDetails? {
    val route = routes.firstOrNull { it.serverId == serverId && it.uid == routeUid } ?: return null
    val point = route.points.getOrNull(pointIndex) ?: return null
    return RoutePointDetails(
        routeUid = route.uid,
        pointIndex = pointIndex,
        title = point.name.orEmpty(),
        latitude = point.latitude,
        longitude = point.longitude,
        droppedBy = route.sender,
        missionName = route.missionName,
        serverId = route.serverId
    )
}

@Composable
fun RoutePointsDetailsView(
    pointName: String,
    latitude: Double,
    longitude: Double,
    sender: String? = null,
    missionName: String? = null,
    onChat: () -> Unit = {},
    onBloodhound: () -> Unit = {},
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(
                top = 24.dp,
                bottom = 70.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                DetailCircle("Point", pointName)
            }

            item {
                DetailCircle("Latitude", "%.5f".format(latitude))
            }

            item {
                DetailCircle("Longitude", "%.5f".format(longitude))
            }

            item {
                DetailCircle("Sent by", sender ?: "?")
            }

            item {
                DetailCircle("Mission", missionName ?: "?")
            }

            item {
                ActionCircle("Chat", onChat)
            }

            item {
                ActionCircle("Bloodhound", onBloodhound)
            }
        }

        // Back button stays at the bottom of the screen
        Button(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .size(width = 92.dp, height = 38.dp)
        ) {
            Text(
                text = "Back",
                fontSize = 12.sp,
                color = Color.White
            )
        }
    }
}

@Composable
private fun DetailCircle(
    label: String,
    value: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .height(64.dp)
            .background(
                color = Color.DarkGray,
                shape = RoundedCornerShape(32.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 6.dp
            )
        ) {
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                color = Color.LightGray,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )

            Text(
                text = value,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
@Composable
private fun ActionCircle(
    label: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .height(64.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color.DarkGray
        )
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}