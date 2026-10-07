package com.aitech.weartak_civ.data.model

enum class TakMissionStatus {
    LOADING,
    READY,
    EMPTY,
    IDLE,
    SYNCING,
    ERROR
}

data class TakMissionState(
    val mission: TakMission,
    val subscribed: Boolean = false,
    val lastSyncTime: Long? = null,
    val processedChangeKeys: Set<String> = emptySet(),
    val status: TakMissionStatus = TakMissionStatus.READY,
    val errorMessage: String? = null
)