package com.aitech.weartak_civ.data.model

data class TakServerMissions(
    val serverId: String,
    val serverName: String,
    val serverAddress: String,
    val missions: List<TakMissionState> = emptyList(),
    val status: TakMissionStatus = TakMissionStatus.LOADING,
    val errorMessage: String? = null
)