/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.presentation

import android.content.Intent
import androidx.compose.ui.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.ipc.AtosMapMarkerResult
import com.aitech.weartak_civ.ipc.toAtosMapMarkerResult
import com.aitech.weartak_civ.presentation.MainActivity.Constants.team
import com.aitech.weartak_civ.presentation.MapActivity.Constants.TAG
import com.aitech.weartak_civ.presentation.MapActivity.Constants.brown
import com.aitech.weartak_civ.presentation.MapActivity.Constants.dark_blue
import com.aitech.weartak_civ.presentation.MapActivity.Constants.dark_green
import com.aitech.weartak_civ.presentation.MapActivity.Constants.maroon
import com.aitech.weartak_civ.presentation.MapActivity.Constants.orange
import com.aitech.weartak_civ.presentation.MapActivity.Constants.purple
import com.aitech.weartak_civ.presentation.MapActivity.Constants.teal
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.provider.RepoLocationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationPuckColors
import org.maplibre.compose.location.rememberUserLocationState
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.material3.ScaleBar
import org.maplibre.compose.style.BaseStyle
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import androidx.compose.runtime.*
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import com.aitech.weartak_civ.MARKER_FRIENDLY
import com.aitech.weartak_civ.MARKER_HOSTILE
import com.aitech.weartak_civ.MARKER_NEUTRAL
import com.aitech.weartak_civ.MARKER_UNKNOWN
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.presentation.MarkerActivity.Constants.markerType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.aitech.weartak_civ.Utils.latLonToMgrs
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DataRepository.MarkerType
import com.aitech.weartak_civ.events.LocationUpdateRequired
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import org.greenrobot.eventbus.EventBus
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.expressions.dsl.Feature.get
import org.maplibre.compose.expressions.dsl.all
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Feature.Companion.getStringProperty
import org.maplibre.compose.expressions.dsl.collator
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.expressions.value.SymbolZOrder
import kotlin.Long

class MapActivity : ComponentActivity() {

    companion object Constants {
        val TAG = "WearTAK-" + MapActivity::class.java.simpleName
        lateinit var mapActivity: MapActivity
        val orange = Color(0xFFFF7700)
        val maroon = Color(0xFF7F0000)
        val purple = Color(0xFF7F007F)
        val dark_blue = Color(0xFF00007F)
        val teal = Color(0xFF007F7F)
        val dark_green = Color(0xFF007F00)
        val brown = Color(0xFF964B00)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mapActivity = this
        Log.i(TAG, "MapActivity onCreate called")

        enableEdgeToEdge()
        setContent {
            WearTAKCIVTheme {
                MapApp({finishAndRemoveTask()})
            }
        }
    }
}
@Composable
fun rememberMarkerFeatures(markers: List<MapMarkerItem>) =
    remember(markers) {
        FeatureCollection(
            features = markers.map { m ->
                Feature(
                    geometry = Point(coordinates = Position(m.lon ?: 0.0, m.lat ?: 0.0)),
                    properties = JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(m.uid),
                            "title" to JsonPrimitive(m.title),
                            "markerType" to JsonPrimitive(m.markerType),
                            "enQueuedToBeSent" to JsonPrimitive(m.enQueuedToBeSent),
                            "cotType" to JsonPrimitive(m.cotType.name))
                    )
                )
            }
        )
    }

data class MapMarkerItem(
    val uid: String,
    val markerType: String,
    val title: String,
    val lat: Double?,
    val lon: Double?,
    val enQueuedToBeSent: Boolean,
    val cotType: DataRepository.MarkerCotType
)
@Composable
fun grayMarkerIconFromString(markerType: String): Painter {
    val painter: Painter = when (markerType) {
        MARKER_UNKNOWN -> painterResource(R.drawable.unknown_gray)
        MarkerType.UNKNOWN.type -> painterResource(R.drawable.unknown_gray)
        MARKER_FRIENDLY -> painterResource(R.drawable.friendly_gray)
        MarkerType.FRIENDLY.type -> painterResource(R.drawable.friendly_gray)
        MARKER_HOSTILE -> painterResource(R.drawable.hostile_gray)
        MarkerType.HOSTILE.type -> painterResource(R.drawable.hostile_gray)
        MARKER_NEUTRAL -> painterResource(R.drawable.neutral_gray)
        MarkerType.NEUTRAL.type -> painterResource(R.drawable.neutral_gray)
        "b-m-p-s-p-i" -> painterResource(R.drawable.spi1_icon)
        else ->  //TODO: Change to error icon
            painterResource(R.drawable.unknown_gray)
    }
    return painter
}
@Composable
fun MarkersOnMap(
    markers: List<MapMarkerItem>,
    onMarkerClick: (id: String, title: String) -> Unit = { _, _ -> },
    camera: CameraState,
) {
    if (markers.isEmpty()) return
    val fc = rememberMarkerFeatures(markers)
    val typeGroups = remember(markers) { markers.groupBy { it.markerType } }

    val source = rememberGeoJsonSource(
        data = GeoJsonData.Features(fc)
    )
    val c = remember { collator(caseSensitive = true, diacriticSensitive = true, locale = "en") }

    typeGroups.forEach { (markerType, group) ->
        val iconRes = markerIconFromString(markerType)
        val iconResGray = grayMarkerIconFromString(markerType)
        SymbolLayer(
            id = group.first().uid,
            zOrder = const(SymbolZOrder.Source),
            sortKey = const(0),
            source = source,
            filter = eq(get("markerType").asString(),const(markerType), c),
            iconImage =
                switch(
                    condition(
                        eq(get("cotType").asString(), const("CLEAR"), c),
                        image(iconResGray)
                    ),
                    fallback = image(iconRes)
                ),
            iconOpacity = switch(
                condition(
                    get("enQueuedToBeSent").asBoolean(),
                    const(.5F)
                ),
                fallback = const(1F)
            ),
            iconAllowOverlap = const(true),
            iconIgnorePlacement = const(true),
            textAllowOverlap = const(true),
            textIgnorePlacement = const(true),
            onClick = { features ->
                val f = features.firstOrNull() ?: return@SymbolLayer ClickResult.Pass
                onMarkerClick(f.getStringProperty("id").orEmpty(), f.getStringProperty("title").orEmpty())
                ClickResult.Pass
            },
            iconSize = const(1),
            textField = if (camera.position.zoom > 10.0) {
                get("title").asString()
            } else {
                const("")
            },
            textSize = const(0.6.em),
            textAnchor = const(SymbolAnchor.Bottom),
            textColor = const(Color.White),
            textHaloColor = const(Color.Black),
            textHaloWidth =const(2.dp),
            textOffset = offset(0f.em, (-1f).em),

        )
    }
}

@Composable
fun rememberATOSFeatures(markers: List<ATOSItem>) =
    remember(markers) {
        FeatureCollection(
            features = markers.map { m ->
                Feature(
                    geometry = Point(coordinates = Position(m.lon ?: 0.0, m.lat ?: 0.0)),
                    properties = JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(m.uid),
                            "title" to JsonPrimitive(m.title),
                            "type" to JsonPrimitive(m.iconType),
                            "color" to JsonPrimitive(m.color),
                            "hae" to JsonPrimitive(m.hae),
                            "gpsOn" to JsonPrimitive((m.hae != 9999999.0).toString())
                        )
                )
                )
            }
        )
    }

data class ATOSItem(
    val uid: String?,
    val iconType: String?,
    val title: String?,
    val lat: Double?,
    val lon: Double?,
    val color: String?,
    val hae: Double?
//    val enQueuedToBeSent: Boolean,
//    val cotType: DataRepository.MarkerCotType
)

private data class AtosMarkerVisualSpec(
    val gpsOnIcon: Int,
    val gpsOffIcon: Int
)

// ATOS tag_type drives icon identity; each CoT type resolves to the corresponding watch drawable.
private val atosMarkerVisualsByCotType = mapOf(
    "a-f-G" to AtosMarkerVisualSpec(R.drawable.ic_track_pax, R.drawable.ic_pax_no_gps),
    "a-f-G-U-C-I" to AtosMarkerVisualSpec(R.drawable.ic_track_pax, R.drawable.ic_pax_no_gps),
    "a-f-G-A" to AtosMarkerVisualSpec(R.drawable.ic_track_hound, R.drawable.ic_track_hound_no_gps),
    "a-f-G-U-C-I-I" to AtosMarkerVisualSpec(R.drawable.ic_track_veh, R.drawable.ic_track_veh_no_gps),
    "a-g-G-E" to AtosMarkerVisualSpec(R.drawable.ic_track_equipment, R.drawable.ic_track_equipment_no_gps),
    "a-f-G-U-S-M" to AtosMarkerVisualSpec(R.drawable.ic_track_medical, R.drawable.ic_track_medical_no_gps),
    "a-f-G-E-W" to AtosMarkerVisualSpec(R.drawable.ic_track_weapon, R.drawable.ic_track_weapon_no_gps),
    "a-f-G-c" to AtosMarkerVisualSpec(R.drawable.ic_track_custom, R.drawable.ic_track_custom_no_gps),
    "a-f-A" to AtosMarkerVisualSpec(R.drawable.ic_track_bundle, R.drawable.ic_track_bundle_no_gps),
    "a-f-S" to AtosMarkerVisualSpec(R.drawable.ic_track_boat, R.drawable.ic_track_boat_no_gps),
    "a-f-A-C-F" to AtosMarkerVisualSpec(R.drawable.ic_track_fixed, R.drawable.ic_track_fixed_no_gps),
    "a-f-A-C-H" to AtosMarkerVisualSpec(R.drawable.ic_track_rotary, R.drawable.ic_track_rotary_no_gps),
    "a-f-A-M" to AtosMarkerVisualSpec(R.drawable.ic_track_uas, R.drawable.ic_track_uas_no_gps),
)

@Composable
fun ATOSTagsOnMap(
    tags: List<ATOSItem>,
    onClick: (id: String, title: String) -> Unit = { _, _ -> },
    camera: CameraState,
) {
    if (tags.isEmpty()) return
    val fc = rememberATOSFeatures(tags)
    val iconGroups = remember(tags) {
        tags.groupBy { tag ->
            Triple(
                tag.iconType,
                tag.color,
                tag.hae != 9999999.0
            )
        }
    }
    val source = rememberGeoJsonSource(
        data = GeoJsonData.Features(fc)
    )
    val c = remember { collator(caseSensitive = true, diacriticSensitive = true, locale = "en") }

    iconGroups.forEach { (key, group) ->
        val type = key.first
        val colorName = key.second
        val gpsOn = key.third

        SymbolLayer(
            id = "atos-$type-$colorName-$gpsOn",
            source = source,
            filter = all(
                eq(get("type").asString(), const(type.toString()), c),
                eq(get("color").asString(), const(colorName.toString()), c),
                eq(get("gpsOn").asString(), const(gpsOn.toString()), c)
            ),
            iconImage = image(atosMarkerPainter(colorName, type, gpsOn)),
            iconAllowOverlap = const(true),
            iconIgnorePlacement = const(true),
            textAllowOverlap = const(true),
            textIgnorePlacement = const(true),
            onClick = { features ->
                val f = features.firstOrNull() ?: return@SymbolLayer ClickResult.Pass
                onClick(f.getStringProperty("id").orEmpty(), f.getStringProperty("title").orEmpty())
                ClickResult.Pass
            },
            iconSize = const(1.2F),
            textField = if (camera.position.zoom > 10.0) {
                get("title").asString()
            } else {
                const("")
            },
            textSize = const(0.6.em),
            textAnchor = const(SymbolAnchor.Bottom),
            textColor = const(Color.White),
            textHaloColor = const(Color.Black),
            textHaloWidth =const(2.dp),
            textOffset = offset(0f.em, (-1f).em),
            )
    }
}

private fun atosMarkerVisualSpec(cotType: String?): AtosMarkerVisualSpec {
    return atosMarkerVisualsByCotType[cotType] ?: AtosMarkerVisualSpec(
        gpsOnIcon = R.drawable.ic_track_custom,
        gpsOffIcon = R.drawable.ic_track_custom_no_gps
    )
}

private fun atosMarkerColor(colorName: String?): Color {
    return when (colorName) {
        "White" -> Color(0xFFFFFFFF)
        "Yellow" -> Color(0xFFFFFF00)
        "Orange" -> Color(0xFFFFA500)
        "Magenta" -> Color(0xFFFF00FF)
        "Red" -> Color(0xFFFF0000)
        "Maroon" -> Color(0xFF800000)
        "Purple" -> Color(0xFF800080)
        "Blue" -> Color(0xFF0000FF)
        "Dark Blue" -> dark_blue
        "Cyan" -> Color(0xFF00FFFF)
        "Teal" -> Color(0xFF00B2B2)
        "Green" -> Color(0xFF00FF00)
        "Dark Green" -> Color(0xFF004C00)
        else -> Color.White
    }
}

@Composable
fun atosMarkerPainter(colorName: String?, iconType: String?, gpsOn: Boolean): Painter {
    val visualSpec = remember(iconType) {
        atosMarkerVisualSpec(iconType)
    }
    val drawableId = if (gpsOn) visualSpec.gpsOnIcon else visualSpec.gpsOffIcon
    return tintedPainter(drawableId, atosMarkerColor(colorName))
}

@Composable
fun tintedPainter(@DrawableRes id: Int, color: Color): Painter {
    val basePainter = painterResource(id = id)

    return remember(basePainter, color) {
        object : Painter() {
            override val intrinsicSize = basePainter.intrinsicSize

            override fun DrawScope.onDraw() {
                with(basePainter) {
                    draw(
                        size = size,
                        colorFilter = ColorFilter.tint(color, BlendMode.SrcIn)
                    )
                }
            }
        }
    }
}


@Composable
fun rememberOtherUsersFeatures(users: List<MapUserItem>) =
    remember(users) {
        FeatureCollection(
            features = users.map { m ->
                Feature(
                    geometry = Point(coordinates = Position(m.lon ?: 0.0, m.lat ?: 0.0)),
                    properties = JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(m.uid),
                            "title" to JsonPrimitive(m.title),
                            "type" to JsonPrimitive(m.type),
                            "team" to JsonPrimitive(m.team)),
                    )
                )
            }
        )
    }

data class MapUserItem(
    val uid: String,
//    val markerType: String,
    val type: String,
    val team: String,
    val title: String,
    val lat: Double?,
    val lon: Double?,
//    val enQueuedToBeSent: Boolean,
//    val cotType: DataRepository.MarkerCotType
)

@Composable
fun UsersOnMap(
    users: List<MapUserItem>,
    onUserClick: (id: String, title: String) -> Unit = { _, _ -> },
    camera: CameraState,
) {
    if (users.isEmpty()) return
    val fc = rememberOtherUsersFeatures(users)
    val typeGroups = remember(users) { users.groupBy { it.type } }

    val source = rememberGeoJsonSource(
        data = GeoJsonData.Features(fc)
    )
    val c = remember { collator(caseSensitive = true, diacriticSensitive = true, locale = "en") }

    typeGroups.forEach { (type, group) ->
        SymbolLayer(
            id = group.first().uid,
            source = source,
            sortKey = const(0),
            zOrder = const(SymbolZOrder.Source),
            filter = eq(get("type").asString(),const(type), c),
            iconImage = switch(
                condition(eq(get("team").asString(), const("White"), c), image(painterResource(R.drawable.user_white))),
                condition(eq(get("team").asString(), const("Yellow"), c), image(painterResource(R.drawable.user_yellow))),
                condition(eq(get("team").asString(), const("Orange"), c), image(painterResource(R.drawable.user_orange))),
                condition(eq(get("team").asString(), const("Magenta"), c), image(painterResource(R.drawable.user_magenta))),
                condition(eq(get("team").asString(), const("Red"), c), image(painterResource(R.drawable.user_red))),
                condition(eq(get("team").asString(), const("Maroon"), c), image(painterResource(R.drawable.user_maroon))),
                condition(eq(get("team").asString(), const("Purple"), c), image(painterResource(R.drawable.user_purple))),
                condition(eq(get("team").asString(), const("Dark Blue"), c), image(painterResource(R.drawable.user_dark_blue))),
                condition(eq(get("team").asString(), const("Blue"), c), image(painterResource(R.drawable.user_blue))),
                condition(eq(get("team").asString(), const("Cyan"), c), image(painterResource(R.drawable.user_cyan))),
                condition(eq(get("team").asString(), const("Teal"), c), image(painterResource(R.drawable.user_yellow))),
                condition(eq(get("team").asString(), const("Green"), c), image(painterResource(R.drawable.user_green))),
                condition(eq(get("team").asString(), const("Dark Green"), c), image(painterResource(R.drawable.user_dark_green))),
                condition(eq(get("team").asString(), const("Brown"), c), image(painterResource(R.drawable.user_brown))),
                fallback = image(painterResource(R.drawable.user_orange)),
            ),
            iconAllowOverlap = const(true),
            iconIgnorePlacement = const(true),
            iconHaloColor = const(Color.Black),
            textAllowOverlap = const(true),
            textIgnorePlacement = const(true),
            onClick = { features ->
                val f = features.firstOrNull() ?: return@SymbolLayer ClickResult.Pass
                onUserClick(f.getStringProperty("id").orEmpty(), f.getStringProperty("title").orEmpty())
                ClickResult.Pass
            },
            iconSize = const(1),
            textField = if (camera.position.zoom > 10.0) {
                get("title").asString()
            } else {
                const("")
            },
            textSize = const(0.6.em),
            textAnchor = const(SymbolAnchor.Bottom),
            textColor = const(Color.White),
            textHaloColor = const(Color.Black),
            textHaloWidth =const(2.dp),
            textOffset = offset(0f.em, (-1f).em),
            )
    }
}

fun TeamColors(team: String) : Color {
   val teamColor =  when (team){
        "White" -> Color.White
        "Yellow" -> Color.Yellow
        "Orange" -> orange
        "Magenta" -> Color.Magenta
        "Red" -> Color.Red
        "Maroon" -> maroon
        "Purple" ->purple
        "Dark Blue" -> dark_blue
        "Blue" -> Color.Blue
        "Cyan" -> Color.Cyan
        "Teal" -> teal
        "Green" -> Color.Green
        "Dark Green" -> dark_green
        "Brown" -> brown
        else -> Color.Unspecified
    }
    return teamColor
}
@Composable
fun MapApp(exitActivity: () -> Unit) {
    val context = LocalContext.current
    val dataRepository = remember(context) {
        (context.applicationContext as WearTakApplication).repo
    }
    val locationFlow by dataRepository.latestLocation.collectAsState()
    // TODO: Will these always be not null?
    val lat = locationFlow?.latitude ?: 0.0
    val lon = locationFlow?.longitude ?: 0.0
    //Log.i(TAG, "latitude = $lat | longitude = $lon")
    //Log.i(TAG, "${locationFlow?.latitude ?: 0.0}")
    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(lon, lat),
            zoom = 12.0,
        )
    )
    val scope = rememberCoroutineScope()
    val markerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val locationProvider = remember(scope) {
        RepoLocationProvider(dataRepository.latestLocation, scope)
    }
    val userLocationState = rememberUserLocationState(locationProvider)
    var showPopup by remember { mutableStateOf(false) }
    val projection = cameraState.projection
    val puckScreenOffset: DpOffset? = remember(
        locationFlow,
        projection,
        cameraState.position
    ) {
        val loc = locationFlow
        val proj = projection

        if (loc != null && proj != null) {
            proj.screenLocationFromPosition(
                Position(
                    latitude = loc.latitude,
                    longitude = loc.longitude
                )
            )
        } else {
            null
        }
    }
    val droppedMarkerListSetting by dataRepository
        .droppedMarkerList
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val receivedMarkerListSetting by dataRepository
        .receivedMarkerList
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val receivedAtosTagsSetting by dataRepository.receivedAtosTagList.collectAsStateWithLifecycle(initialValue = emptyList())
    val receivedUserListSetting by MainActivity.Constants.dataRepository
        .receivedUserList
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val droppedMapMarkers = remember(droppedMarkerListSetting) {
        buildList {
            addAll(
                droppedMarkerListSetting.map { marker ->
                    MapMarkerItem(
                        uid = marker.uid,
                        markerType = marker.markerType.type,
                        title = marker.title,
                        lat = marker.location?.lat,
                        lon = marker.location?.lon,
                        marker.enqueuedToBeSent,
                        marker.cotType
                    )
                }
            )
        }
    }
    val receivedMapMarkers = remember(receivedMarkerListSetting) {
        buildList {
            addAll(
                receivedMarkerListSetting.mapNotNull { marker ->
                    val location = marker.location ?: return@mapNotNull null
                    MapMarkerItem(
                        uid = marker.uid,
                        markerType = marker.markerType,
                        title = marker.title,
                        lat = location.lat,
                        lon = location.lon,
                        false,
                        //TODO: Is this okay to have here?
                        DataRepository.MarkerCotType.DROP

                    )
                }
            )
        }
    }

    val receivedAtosTags = remember(receivedAtosTagsSetting) {
        buildList {
            addAll(
                receivedAtosTagsSetting.mapNotNull { tag ->
                    when (val markerResult = tag.toAtosMapMarkerResult()) {
                        is AtosMapMarkerResult.Success -> {
                            val marker = markerResult.marker
                            ATOSItem(
                                uid = marker.uid,
                                iconType = marker.iconCotType,
                                title = marker.title ?: marker.uid,
                                lat = marker.lat,
                                lon = marker.lon,
                                color = marker.color,
                                hae = marker.hae
                            )
                        }
                        is AtosMapMarkerResult.Failure -> {
                            Log.w(
                                TAG,
                                "ATOS local map render skipped uid=${tag.uid ?: "null"} type=${tag.type ?: "null"} tagType=${tag.atosDetail?.tagType ?: "null"} lat=${tag.point?.lat ?: "null"} lon=${tag.point?.lon ?: "null"} hae=${tag.point?.hae ?: "null"} reason=${markerResult.reason} warnings=${tag.warnings.joinToString(separator = " | ")}"
                            )
                            null
                        }
                    }
                }
            )
        }
    }


    val receivedMapUsers = remember(receivedUserListSetting) {
        buildList {
            addAll(
                receivedUserListSetting.mapNotNull { user ->
                    val location = user.location ?: return@mapNotNull null
                        MapUserItem(
                            uid = user.uid,
                            type = user.type,
                            team = user.team,
                            title = user.title,
                            lat = location.lat,
                            lon = location.lon
                        )
                }
            )
        }
    }
    Log.d(TAG, receivedMapUsers.toString())
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Log.i(TAG, "MapApp Lifecycle.Event.ON_RESUME called")
                EventBus.getDefault().post(LocationUpdateRequired())
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(Modifier.fillMaxSize()) {
        var pointerOffset by remember { mutableStateOf<DpOffset?>(null) }
        val anyEndpointConnected by dataRepository.anyEndpointConnected.collectAsStateWithLifecycle()

        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Uri("asset://style.json"),
            cameraState = cameraState,
            options = MapOptions(
                ornamentOptions = OrnamentOptions.AllDisabled,
                gestureOptions = GestureOptions.RotationLocked),
            zoomRange = 0f..25.5f, // important so your +5 doesn't exceed max
            onMapLongClick = { coordinates, offset ->
                Utils.vibrateWatch(vibDurationMs = 100L, numberOfVibes = 1, timeBtwnVibesMs = 10L)
                pointerOffset = offset
                val time = System.currentTimeMillis()

                Log.d(TAG, "LongClick Map before launch")
                 markerScope.launch {
                    try {
                        Log.d(TAG, "LongClick coroutine started")
                        Log.d(TAG, "About to call requestMarkerDrop type=$markerType time=$time coord=$coordinates")

                        dataRepository.requestMarkerDrop(
                            markerType,
                            "",
                            "",
                            time,
                            lat = coordinates.latitude,
                            lon = coordinates.longitude,
                        )

                        Log.d(TAG, "requestMarkerDrop finished")
                    } catch (e: Exception) {
                        Log.e(TAG, "requestMarkerDrop failed", e)
                    }
                }
//                if (!anyEndpointConnected) {
//                    Toast.makeText(
//                        mainActivity,
//                        "No endpoint connected, will send point when reconnected", Toast.LENGTH_LONG).show()
//                }
                ClickResult.Pass
            }

        ) {
            if (locationProvider.location.collectAsState().value != null) {
                LocationPuck(
                    idPrefix = "me",
                    locationState = userLocationState,
                    cameraState = cameraState,
                    colors = LocationPuckColors(
                        dotFillColorCurrentLocation = TeamColors(team)),
                    onClick = {
                        scope.launch {
                            val current = cameraState.position
                            cameraState.animateTo(
                                CameraPosition(
                                    target = Position(
                                        latitude = lat,
                                        longitude = lon
                                    ),
                                    zoom = current.zoom,
                                    bearing = current.bearing,
                                    tilt = current.tilt,

                                    // More right padding pushes the target visually left
                                    padding = PaddingValues(
                                        start = 0.dp,
                                        top = 0.dp,
                                        end = 84.dp,
                                        bottom = 30.dp
                                    )
                                )
                            )

                            showPopup = true
                        }
                    }
                )
            }
            MarkersOnMap(
                markers = droppedMapMarkers.filter{ (null != it.lat) && (null != it.lon) },
                onMarkerClick = { id, title ->
                    val index = droppedMarkerListSetting.indexOfFirst { it.uid == id }
                    if (droppedMapMarkers.get(index).cotType != DataRepository.MarkerCotType.CLEAR) {
                        Log.d(TAG, "Index from map: $index")
                        val intent = Intent(context, MarkerActivity::class.java).apply {
                            putExtra("marker_index", index)
                            putExtra("start_route", "edit_marker_screen/$index")
                            putExtra("from_maps", true)
                        }
                        context.startActivity(intent)
                    }
                },
                cameraState
            )
            MarkersOnMap(
                markers = receivedMapMarkers,
                camera = cameraState
            )
            ATOSTagsOnMap(
                tags = receivedAtosTags,
                camera = cameraState
            )
            UsersOnMap(
                users = receivedMapUsers,
                camera = cameraState
            )
        }
        if (showPopup && puckScreenOffset != null) {
           PopupBox(
               showPopup = showPopup,
                anchor = puckScreenOffset,
               lat = lat,
               lon = lon,
                onClickOutside = { showPopup = false }
            )
        }
//        PopupBox(showPopup = showPopup, onClickOutside = {showPopup = false})

        Image(
            painter = painterResource(R.drawable.north_arrow),
            contentDescription = "North Arrow",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(40.dp),
            alignment = Alignment.TopCenter
        )
        val current = cameraState.position
        Box(Modifier
            .fillMaxSize()
            .padding(8.dp),
            contentAlignment = Alignment.Center
        ){

            ScaleBar(
                cameraState.metersPerDpAtTarget,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(100.dp),
                color = colors.primary,
                textStyle = MaterialTheme.typography.body2,
                barWidth = 1.dp
            )

            Row(modifier = Modifier.align(Alignment.BottomCenter)) {
                BackButton(
                    onClick = {
                        exitActivity()
                    })
            }
        }
//        MapUi(
//            moveUp = {
//                val cur = cameraState.position
//                val curTarget = cur.target
//
//                val stepLat = 14.0 / (1 shl cur.zoom.toInt().coerceIn(0, 20))  // rough heuristic
//                val newLat = (curTarget.latitude + stepLat).coerceIn(-90.0, 90.0)
//                val newTarget = Position(curTarget.longitude, newLat)
//
//                scope.launch {
//                    cameraState.animateTo(
//                        cur.copy(target = newTarget),
//                        duration = 120.milliseconds
//                    )
//                }
//            },
//            moveDown = { val cur = cameraState.position
//                val curTarget = cur.target
//
//                val stepLat = -14.0 / (1 shl cur.zoom.toInt().coerceIn(0, 20))  // rough heuristic
//                val newLat = (curTarget.latitude + stepLat).coerceIn(-90.0, 90.0)
//                val newTarget = Position(curTarget.longitude, newLat)
//
//                scope.launch {
//                    cameraState.animateTo(
//                        cur.copy(target = newTarget),
//                        duration = 120.milliseconds
//                    )
//                } },
//            moveLeft = {
//                val cur = cameraState.position
//                val curTarget = cur.target
//
//                val stepLong = -14.0 / (1 shl cur.zoom.toInt().coerceIn(0, 20))  // rough heuristic
//                val newLong = (curTarget.longitude + stepLong).coerceIn(-180.0, 180.0)
//                val newTarget = Position(newLong, curTarget.latitude)
//
//                scope.launch {
//                    cameraState.animateTo(
//                        cur.copy(target = newTarget),
//                        duration = 120.milliseconds
//                    )
//                } },
//            moveRight = {
//                val cur = cameraState.position
//                val curTarget = cur.target
//
//                val stepLong = 14.0 / (1 shl cur.zoom.toInt().coerceIn(0, 20))  // rough heuristic
//                val newLong = (curTarget.longitude + stepLong).coerceIn(-180.0, 180.0)
//                val newTarget = Position(newLong, curTarget.latitude)
//
//                scope.launch {
//                    cameraState.animateTo(
//                        cur.copy(target = newTarget),
//                        duration = 120.milliseconds
//                    )
//                }
//            },
//            zoomIn = {
//                val nextZoom = (current.zoom + 1f).coerceAtMost(25.5)
//                scope.launch {
//                    cameraState.animateTo(
//                        finalPosition = current.copy(zoom = nextZoom),
//                        duration = 120.milliseconds
//                    )
//                }
//                Log.d(TAG, "zoom = $nextZoom")
//            },
//            zoomOut = { val nextZoom = (current.zoom - 1f).coerceAtLeast(0.0)
//                scope.launch {
//                    cameraState.animateTo(
//                        finalPosition = current.copy(zoom = nextZoom),
//                        duration = 120.milliseconds
//                    )
//                }
//                Log.d(TAG, "zoom = $nextZoom") }
//        )
    }
}
@Composable
fun PopupBox(
    anchor: DpOffset,
    showPopup: Boolean,
    lat: Double,
    lon: Double,
    onClickOutside: () -> Unit,
){
    val density = LocalDensity.current

    if (showPopup) {
        // full screen background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10F),
            contentAlignment = Alignment.Center
        ) {
            // popup
            Popup(
                alignment = Alignment.TopStart,
                offset = with(density) {
                    IntOffset(
                        x = (anchor.x + 12.dp).roundToPx(), // move popup to the right
                        y = anchor.y.roundToPx()
                    )
                },
                properties = PopupProperties(
                    excludeFromSystemGesture = true,
                ),
                // to dismiss on click outside
                onDismissRequest = { onClickOutside() }
            ) {
                Box(
                    Modifier
                        .wrapContentSize()
                        .background(Color.Black.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column (modifier = Modifier.padding(5.dp)){
                        Text(
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            lineHeight = 10.sp,
                            text = "Lat: $lat\nLon: $lon\n\n${latLonToMgrs(lat, lon)}")
                    }
                }
            }
        }
    }
}
@Composable
fun MapUi(
    moveUp: () -> Unit,
    moveDown: () -> Unit,
    moveLeft: () -> Unit,
    moveRight: () -> Unit,
    zoomIn: () -> Unit,
    zoomOut: () -> Unit,
    ){

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }

        val radiusPx = min(wPx, hPx) / 2f
        val centerX = wPx / 2f
        val centerY = hPx / 2f

        // We pull the button *in* by half its size so it stays fully visible,
        // while still visually "touching" the circular edge.
        val btnPx = with(density) { 40.dp.toPx() }
        val btnR = btnPx / 2f
        val edgeDist = radiusPx - btnR

        fun polarOffset(angleDeg: Float, dist: Float = edgeDist): IntOffset {
            val a = Math.toRadians(angleDeg.toDouble())
            val x = centerX + (dist * cos(a)).toFloat() - btnR
            val y = centerY + (dist * sin(a)).toFloat() - btnR
            return IntOffset(x.roundToInt(), y.roundToInt())
        }

        val context = LocalContext.current

        val interactionSource = remember { MutableInteractionSource() }

        val viewConfiguration = LocalViewConfiguration.current


        LaunchedEffect(interactionSource) {
            var isLongClick = false

            interactionSource.interactions.collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        isLongClick = false
                        delay(viewConfiguration.longPressTimeoutMillis)
                        isLongClick = true
                        Toast.makeText(context, "LONG", Toast.LENGTH_SHORT).show()

                    }

                    is PressInteraction.Release -> {
                        if (isLongClick.not()) {
                            Toast.makeText(context, "click", Toast.LENGTH_SHORT).show()
                        }

                    }

                }
            }
            while(isLongClick){
                moveUp()
                delay(5000) // Delay for 5 seconds
                Log.d(TAG, "isLongClick = $isLongClick")
            }
        }


        // --- 4 arrow buttons on top/bottom/left/right ---
        Button(
//            interactionSource = interactionSource,
            onClick = { moveUp() },
            modifier = Modifier
                .offset { polarOffset(-90f) }
                .size(40.dp), // top,
            shape = RoundedCornerShape(0.dp,0.dp,40.dp,40.dp),
            ) { Text("▲") }

        Button(
//            interactionSource = interactionSource,
            onClick = { moveDown() },
            modifier = Modifier
                .offset { polarOffset(90f) }
                .size(40.dp),  // bottom
            shape = RoundedCornerShape(40.dp,40.dp,0.dp,0.dp),
            ) { Text("▼") }

        Button(
//            interactionSource = interactionSource,
            onClick = { moveLeft() },
            modifier = Modifier
                .offset { polarOffset(180f) }
                .size(40.dp), // left
            shape = RoundedCornerShape(0.dp,40.dp,40.dp,0.dp),
            ) { Text("◀") }

        Button(
//            interactionSource = interactionSource,
            onClick = { moveRight() },
            modifier = Modifier
                .offset { polarOffset(0f) }
                .size(40.dp),   // right
            shape = RoundedCornerShape(40.dp,0.dp,0.dp,40.dp),
            ) { Text("▶") }

        // --- + / − on lower-right ---
        // Place a *cluster anchor* on the circle at 315° (lower-right),
        // then offset + and − up/down from that anchor.
        val gapPx = with(density) { 10.dp.toPx() }
        val stackDelta = (btnPx + gapPx) / 2f

        // Pull the anchor inward so the top/bottom buttons don't clip out of the circle.
        val clusterDist = edgeDist - stackDelta * 0.6f
        val anchor = polarOffset(angleDeg = 315f, dist = clusterDist)

        Button(
            onClick = { zoomIn() },
            modifier = Modifier
                .offset { polarOffset(33f) }
                .size(40.dp)
                .rotate(45f),  // bottom
            shape = RoundedCornerShape(20.dp,20.dp,0.dp,0.dp),
        ) { Text("+", modifier = Modifier.rotate(-45f))}

        Button(
            onClick = { zoomOut() },
            modifier = Modifier
                .offset { polarOffset(57f) }
                .size(40.dp)
                .rotate(45f),  // bottom
            shape = RoundedCornerShape(0.dp,0.dp,20.dp,20.dp),
            ) { Text("−", modifier = Modifier.rotate(-45f)) }
    }
}


@Preview(widthDp = 192, heightDp = 192, showBackground = true)
@Composable
fun MyAppPreview() {
    PopupBox(showPopup = true, anchor = DpOffset.Unspecified, lat = 41.8832, lon = -87.6324, onClickOutside = {false})
}
