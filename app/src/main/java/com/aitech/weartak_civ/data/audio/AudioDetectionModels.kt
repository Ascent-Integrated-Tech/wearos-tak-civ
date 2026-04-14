package com.aitech.weartak_civ.data.audio

import com.aitech.weartak_civ.Utils.AlertChipState
import com.aitech.weartak_civ.data.DataRepository

data class AudioDetectionChunkRef(
    val sessionId: String,
    val seq: Long,
    val timestampMs: Long,
    val sampleRateHz: Int,
    val channels: Int
)

data class AudioDetectionEvent(
    val detectorKey: String,
    val timestamp: Long,
    val confidence: Float,
    val loc: DataRepository.LocationSnapshot?,
    val uid: String,
    var alertLevel: AlertChipState,
    val chunkRef: AudioDetectionChunkRef,
    val triggerPcmPath: String?,
    val audioSource: String?,
    val inferStartElapsedMs: Long? = null,
    val inferEndElapsedMs: Long? = null
)

enum class AudioDetectionUserLabel { YES, NO, UNKNOWN, TIMEOUT }

data class AudioDetectionFeedback(
    val eventUid: String,
    val label: AudioDetectionUserLabel,
    val respondedAtMs: Long,
    val responseLatencyMs: Long,
    val extra: Map<String, Any?> = emptyMap()
)
