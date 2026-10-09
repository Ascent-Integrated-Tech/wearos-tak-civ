package com.aitech.weartak_civ.data.model

data class TakRoute(
    val uid: String,
    val name: String,
    val points: List<TakRoutePoint>,
    val serverId: String,
    val missionName: String? = null
)

data class TakRoutePoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val name: String? = null
)