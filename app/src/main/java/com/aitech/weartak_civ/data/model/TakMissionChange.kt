package com.aitech.weartak_civ.data.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class TakMissionChange(
    val type: String = "",
    val contentUid: String? = null,
    val missionName: String = "",
    val timestamp: String? = null,
    val creatorUid: String? = null
)