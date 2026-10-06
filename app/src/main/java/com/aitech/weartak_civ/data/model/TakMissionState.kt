package com.aitech.weartak_civ.data.model

enum class TakMissionStatus {
    LOADING,
    READY,
    EMPTY,
    ERROR
}

data class TakMissionState(
    val mission: TakMission,
    val status: TakMissionStatus = TakMissionStatus.READY,
    val errorMessage: String? = null
)