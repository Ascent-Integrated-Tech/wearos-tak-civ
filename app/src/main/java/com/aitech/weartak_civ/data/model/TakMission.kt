package com.aitech.weartak_civ.data.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class TakMission(
    val name: String = "",
    val creatorUid: String? = null,
    val createTime: String? = null,
    val keywords: List<String> = emptyList(),
    val uids: List<TakMissionUid> = emptyList(),
    val contents: List<TakMissionContent> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TakMissionUid(
    val data: String = "",
    val timestamp: String? = null,
    val creatorUid: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TakMissionContent(
    val data: TakMissionContentData? = null,
    val timestamp: String? = null,
    val creatorUid: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TakMissionContentData(
    val name: String? = null,
    val mimeType: String? = null,
    val submissionTime: String? = null,
    val submitter: String? = null,
    val uid: String? = null,
    val creatorUid: String? = null,
    val hash: String? = null,
    val size: Long? = null,
    val keywords: List<String> = emptyList()
)