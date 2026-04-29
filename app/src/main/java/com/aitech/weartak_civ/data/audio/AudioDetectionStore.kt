/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data.audio

import com.aitech.weartak_civ.Utils.AlertChipState
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

class AudioDetectionStore(
    private val detectorKey: String,
    private val repo: DataRepository
) {
    private val _feedback =
        MutableSharedFlow<AudioDetectionFeedback>(
            replay = 0,
            extraBufferCapacity = 20,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val feedback: SharedFlow<AudioDetectionFeedback> = _feedback

    private val _events =
        MutableSharedFlow<AudioDetectionEvent>(
            replay = 0,
            extraBufferCapacity = 10,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val events: SharedFlow<AudioDetectionEvent> = _events

    private val recentEventsMap = ConcurrentHashMap<String, AudioDetectionEvent>()
    private val _recentEventsFlow = MutableStateFlow<List<AudioDetectionEvent>>(emptyList())
    val recentEventsFlow: StateFlow<List<AudioDetectionEvent>> = _recentEventsFlow

    suspend fun createEvent(
        uid: String,
        confidence: Float,
        pcmChunk: AudioCaptureRepository.AudioPcmChunk,
        triggerPcmPath: String?,
        audioSource: String?,
        inferStart: Long? = null,
        inferEnd: Long? = null,
        classification: AudioClassificationResult? = null
    ): AudioDetectionEvent {
        val event = AudioDetectionEvent(
            detectorKey = detectorKey,
            timestamp = System.currentTimeMillis(),
            confidence = (round(confidence * 10000f) / 10000f),
            loc = repo.snapshotLocation(),
            uid = uid,
            alertLevel = AlertChipState.Warning,
            chunkRef = AudioDetectionChunkRef(
                sessionId = pcmChunk.sessionId,
                seq = pcmChunk.seq,
                timestampMs = pcmChunk.timestampMs,
                sampleRateHz = pcmChunk.sampleRateHz,
                channels = pcmChunk.channels
            ),
            triggerPcmPath = triggerPcmPath,
            audioSource = audioSource,
            inferStartElapsedMs = inferStart,
            inferEndElapsedMs = inferEnd,
            classification = classification
        )
        _events.emit(event)
        upsertRecent(event)
        return event
    }

    fun tryEmitFeedback(feedback: AudioDetectionFeedback): Boolean =
        _feedback.tryEmit(feedback)

    fun upsertRecent(event: AudioDetectionEvent) {
        recentEventsMap[event.uid] = event
        _recentEventsFlow.value = recentEventsMap.values.sortedByDescending { it.timestamp }
    }

    fun getEventFromUid(uid: String): AudioDetectionEvent? = recentEventsMap[uid]

    fun setEventAlertLevel(uid: String, level: AlertChipState): Boolean {
        val current = recentEventsMap[uid] ?: return false
        recentEventsMap[uid] = current.copy(alertLevel = level)
        _recentEventsFlow.value = recentEventsMap.values.sortedByDescending { it.timestamp }
        return true
    }

    fun remove(uid: String): Boolean {
        val removed = recentEventsMap.remove(uid) != null
        if (removed) {
            _recentEventsFlow.value = recentEventsMap.values.sortedByDescending { it.timestamp }
        }
        return removed
    }

    fun clearAll() {
        recentEventsMap.clear()
        _recentEventsFlow.value = emptyList()
    }
}
