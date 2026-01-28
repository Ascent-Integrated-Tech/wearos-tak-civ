package com.aitech.weartak_civ.data

import android.content.Context
import com.aitech.weartak_civ.Utils.AlertChipState
import com.aitech.weartak_civ.WearTakApplication
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID
import kotlin.math.round

class OnnxRepository(private val context: Context) {

    private val TAG = "WearTAK-OnnxRepo"

    private val repo by lazy {
        try {
            (context as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(context)
        }
    }

    data class OnnxGunshotEvent (
        val timestamp: Long ,                        // system time when gunshot was detected
        val confidence: Float,                       // how confident the model is
        val loc: DataRepository.LocationSnapshot?,   // curr location snapshot
        val uid: String,                             // for event manipulation event, sorting, etc
        var alertLevel: AlertChipState,              // NONE, WARNING (Alert wearer YELLOW), EMERGENCY (alert wearer RED & dispatch to tak)

        // TODO: Are the following fields useful beyond testing / research?
        val chunkRef: GunshotChunkRef,
        val triggerPcmPath: String?,
        val audioSource: String?,

        // --- debug timing (monotonic) ---
        val inferStartElapsedMs: Long? = null,   // SystemClock.elapsedRealtime()
        val inferEndElapsedMs: Long? = null      // SystemClock.elapsedRealtime()
    )

    data class GunshotChunkRef(
        val sessionId: String,
        val seq: Long,
        val timestampMs: Long,
        val sampleRateHz: Int,
        val channels: Int
    )

    suspend fun createOnnxGunshotEvent(
        uid: String,
        confidence: Float,
        pcmChunk: AudioCaptureRepository.AudioPcmChunk,
        triggerPcmPath: String?,
        audioSource: String?,
        inferStart: Long? = null,
        inferEnd: Long? = null
    ): OnnxGunshotEvent {
        val gs = OnnxGunshotEvent(
            timestamp = System.currentTimeMillis(),
            confidence = (round(confidence * 10000f) / 10000f),
            loc = repo.snapshotLocation(),
            uid = uid,
            alertLevel = AlertChipState.Warning,
            chunkRef = GunshotChunkRef(
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
        _onnxGunshotEvents.emit(gs)
        upsertRecentGunshot(gs)
        return gs
    }

    // From main activity / UI
    private val _gunshotFeedback =
        MutableSharedFlow<GunshotFeedback>(
            replay = 0,
            extraBufferCapacity = 20,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val gunshotFeedback: SharedFlow<GunshotFeedback> = _gunshotFeedback

    fun tryEmitGunshotFeedback(feedback: GunshotFeedback): Boolean =
        _gunshotFeedback.tryEmit(feedback)

    private val _onnxGunshotEvents =
        kotlinx.coroutines.flow.MutableSharedFlow<OnnxGunshotEvent>(
            replay = 0,
            extraBufferCapacity = 10,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
        )

    val gunshotEvents: kotlinx.coroutines.flow.SharedFlow<OnnxGunshotEvent> = _onnxGunshotEvents

    private val recentGunshotsMap = java.util.concurrent.ConcurrentHashMap<String, OnnxGunshotEvent>()

    private val _recentGunshotsFlow =
        kotlinx.coroutines.flow.MutableStateFlow<List<OnnxGunshotEvent>>(emptyList())

    val recentGunshotsFlow: kotlinx.coroutines.flow.StateFlow<List<OnnxGunshotEvent>> = _recentGunshotsFlow

    fun upsertRecentGunshot(event: OnnxGunshotEvent) {
        recentGunshotsMap[event.uid] = event
        _recentGunshotsFlow.value = recentGunshotsMap.values
            .sortedByDescending { it.timestamp }   // newest first
    }

    fun getEventFromUid(uid: String): OnnxGunshotEvent? {
        return recentGunshotsMap[uid]
    }

    fun setGunshotEventAlertLevel(uid: String, level: AlertChipState): Boolean {
        val cur = recentGunshotsMap[uid] ?: return false
        // Create updated copy; keep the map immutable-ish from the outside
        val updated = cur.copy(alertLevel = level)
        recentGunshotsMap[uid] = updated
        _recentGunshotsFlow.value = recentGunshotsMap.values.sortedByDescending { it.timestamp }
        return true
    }

    fun removeGunshot(uid: String): Boolean {
        val removed = recentGunshotsMap.remove(uid) != null
        if (removed) {
            _recentGunshotsFlow.value = recentGunshotsMap.values.sortedByDescending { it.timestamp }
        }
        return removed
    }

    fun clearAllGunshots() {
        recentGunshotsMap.clear()
        _recentGunshotsFlow.value = emptyList()
    }

    enum class GunshotUserLabel { YES, NO, UNKNOWN, TIMEOUT }

    data class GunshotFeedback(
        val eventUid: String,
        val label: GunshotUserLabel,
        val respondedAtMs: Long,
        val responseLatencyMs: Long,
        val extra: Map<String, Any?> = emptyMap()
    )

}