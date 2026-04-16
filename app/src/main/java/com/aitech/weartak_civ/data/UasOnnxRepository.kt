package com.aitech.weartak_civ.data

import android.content.Context
import com.aitech.weartak_civ.Utils.AlertChipState
import com.aitech.weartak_civ.WearTakApplication
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.round

class UasOnnxRepository(private val context: Context) {

    private val repo by lazy {
        try {
            (context as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(context)
        }
    }

    data class UasOnnxEvent(
        val timestamp: Long,
        val confidence: Float,
        val loc: DataRepository.LocationSnapshot?,
        val uid: String,
        var alertLevel: AlertChipState,
        val chunkRef: UasChunkRef,
        val triggerPcmPath: String?,
        val audioSource: String?,
        val inferStartElapsedMs: Long? = null,
        val inferEndElapsedMs: Long? = null
    )

    data class UasChunkRef(
        val sessionId: String,
        val seq: Long,
        val timestampMs: Long,
        val sampleRateHz: Int,
        val channels: Int
    )

    suspend fun createUasEvent(
        uid: String,
        confidence: Float,
        pcmChunk: AudioCaptureRepository.AudioPcmChunk,
        triggerPcmPath: String?,
        audioSource: String?,
        inferStart: Long? = null,
        inferEnd: Long? = null
    ): UasOnnxEvent {
        val event = UasOnnxEvent(
            timestamp = System.currentTimeMillis(),
            confidence = (round(confidence * 10000f) / 10000f),
            loc = repo.snapshotLocation(),
            uid = uid,
            alertLevel = AlertChipState.Warning,
            chunkRef = UasChunkRef(
                sessionId = pcmChunk.sessionId,
                seq = pcmChunk.seq,
                timestampMs = pcmChunk.timestampMs,
                sampleRateHz = pcmChunk.sampleRateHz,
                channels = pcmChunk.channels
            ),
            triggerPcmPath = triggerPcmPath,
            audioSource = audioSource,
            inferStartElapsedMs = inferStart,
            inferEndElapsedMs = inferEnd
        )
        _uasEvents.emit(event)
        upsertRecentUas(event)
        return event
    }

    private val _uasFeedback =
        MutableSharedFlow<UasFeedback>(
            replay = 0,
            extraBufferCapacity = 20,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val uasFeedback: SharedFlow<UasFeedback> = _uasFeedback

    fun tryEmitUasFeedback(feedback: UasFeedback): Boolean =
        _uasFeedback.tryEmit(feedback)

    private val _uasEvents =
        MutableSharedFlow<UasOnnxEvent>(
            replay = 0,
            extraBufferCapacity = 10,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val uasEvents: SharedFlow<UasOnnxEvent> = _uasEvents

    private val recentUasMap = java.util.concurrent.ConcurrentHashMap<String, UasOnnxEvent>()

    private val _recentUasFlow =
        kotlinx.coroutines.flow.MutableStateFlow<List<UasOnnxEvent>>(emptyList())

    val recentUasFlow: kotlinx.coroutines.flow.StateFlow<List<UasOnnxEvent>> = _recentUasFlow

    fun upsertRecentUas(event: UasOnnxEvent) {
        recentUasMap[event.uid] = event
        _recentUasFlow.value = recentUasMap.values.sortedByDescending { it.timestamp }
    }

    fun getEventFromUid(uid: String): UasOnnxEvent? {
        return recentUasMap[uid]
    }

    fun setUasEventAlertLevel(uid: String, level: AlertChipState): Boolean {
        val current = recentUasMap[uid] ?: return false
        val updated = current.copy(alertLevel = level)
        recentUasMap[uid] = updated
        _recentUasFlow.value = recentUasMap.values.sortedByDescending { it.timestamp }
        return true
    }

    fun removeUas(uid: String): Boolean {
        val removed = recentUasMap.remove(uid) != null
        if (removed) {
            _recentUasFlow.value = recentUasMap.values.sortedByDescending { it.timestamp }
        }
        return removed
    }

    fun clearAllUas() {
        recentUasMap.clear()
        _recentUasFlow.value = emptyList()
    }

    enum class UasUserLabel { YES, NO, UNKNOWN, TIMEOUT }

    data class UasFeedback(
        val eventUid: String,
        val label: UasUserLabel,
        val respondedAtMs: Long,
        val responseLatencyMs: Long,
        val extra: Map<String, Any?> = emptyMap()
    )
}
