package com.aitech.weartak_civ.data

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class AudioCaptureRepository(private val context: Context) {

    private val TAG = "WearTAK-" + AudioCaptureRepository::class.java.simpleName

    private val repo by lazy {
        try {
            (context.applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(context.applicationContext)
        }
    }

    sealed interface CaptureCommand {
        data object StartModelCapture : CaptureCommand
        data object StopModelCapture : CaptureCommand
        data object StartFieldSession : CaptureCommand
        data object StopFieldSession : CaptureCommand
        data object PauseFieldSession : CaptureCommand
        data class ApplyAudioSource(val source: AudioSourceMode) : CaptureCommand
    }

    enum class RecordingState {
        RECORDING,
        PAUSED,
        PLAYING,
        AVAILABLE,
        UNAVAILABLE
    }

    enum class AudioSourceMode(
        val recorderValue: Int,
        val shortLabel: String,
        val displayLabel: String
    ) {
        DEFAULT(
            MediaRecorder.AudioSource.DEFAULT,
            "DEF",
            "Default"
        ),
        VOICE_COMMUNICATION(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "VC",
            "Voice Communication"
        ),
        VOICE_RECOGNITION(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "VR",
            "Voice Recognition"
        ),
        UNPROCESSED(
            MediaRecorder.AudioSource.UNPROCESSED,
            "RAW",
            "Unprocessed"
        ),
        CAMCORDER(
            MediaRecorder.AudioSource.CAMCORDER,
            "CAM",
            "Camcorder"
        );

        companion object {
            val DEFAULT_SELECTION = DEFAULT

            fun fromRecorderValue(value: Int?): AudioSourceMode =
                entries.firstOrNull { it.recorderValue == value } ?: DEFAULT_SELECTION
        }
    }

    // PCM_16BIT
    data class AudioPcmChunk(
        val sessionId: String,
        val seq: Long,
        val timestampMs: Long,
        val timestampElMs: Long,
        val sampleRateHz: Int,     // 44100 in current gunshot config
        val channels: Int,         // 1
        val samples: ShortArray    // PCM16 mono little-endian
    )
    private val _pcmChunks =
        MutableSharedFlow<AudioPcmChunk>(
            replay = 0,
            extraBufferCapacity = 50, // ~2 seconds-ish depending on READ_SIZE
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
        )
    fun tryEmitChunk(chunk: AudioPcmChunk): Boolean = _pcmChunks.tryEmit(chunk)
    val getPcmChunks: SharedFlow<AudioPcmChunk> = _pcmChunks

    private val _numFilesWrittenSinceFieldStart = MutableStateFlow<Int>(0)
    val numFilesWrittenSinceFieldStart: StateFlow<Int> = _numFilesWrittenSinceFieldStart

    fun resetNumFilesWrittenSinceFieldStart() {
        Log.i(TAG, "resetNumFilesWrittenSinceFieldStart()")
        _numFilesWrittenSinceFieldStart.value = 0
    }

    fun incrementNumFilesWrittenSinceFieldStart(increment: Int = 1) {
        _numFilesWrittenSinceFieldStart.value = _numFilesWrittenSinceFieldStart.value + increment
        Log.i(TAG, "numFilesWrittenSinceFieldStart=${_numFilesWrittenSinceFieldStart.value}")
    }

    private val _writeErrorInFieldSession = MutableStateFlow<Boolean>(false)
    val writeErrorInFieldSession: StateFlow<Boolean> = _writeErrorInFieldSession

    fun resetWriteErrorsSinceFieldStart() {
        Log.i(TAG, "resetWriteErrorsSinceFieldStart()")
        _writeErrorInFieldSession.value = false
    }

    fun setWriteErrorDuringFieldSession() {
        _writeErrorInFieldSession.value = true
        Log.i(TAG, "setWriteErrorInFieldSession=${_writeErrorInFieldSession.value}")
    }

    data class FieldRecordingStatus(
        val active: Boolean = false,
        val startedAtMs: Long? = null,
        val sessionId: String? = null,
        val selectedSource: AudioSourceMode = AudioSourceMode.DEFAULT_SELECTION,
        val segmentIndex: Int = 0
    )

    private val _captureCommands = MutableSharedFlow<CaptureCommand>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val captureCommands: SharedFlow<CaptureCommand> = _captureCommands

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.AVAILABLE)
    val getRecordingState: StateFlow<RecordingState> = _recordingState

    private val _recordingSessionState = MutableStateFlow<Boolean>(false)
    val getRecordingSessionState: StateFlow<Boolean> = _recordingSessionState

    private val _fieldRecordingStatus = MutableStateFlow(FieldRecordingStatus())
    val fieldRecordingStatus: StateFlow<FieldRecordingStatus> = _fieldRecordingStatus

    private val _recordingError = MutableStateFlow<String?>(null)
    val recordingError: StateFlow<String?> = _recordingError

    private val _selectedAudioSource = MutableStateFlow(AudioSourceMode.DEFAULT_SELECTION)
    val selectedAudioSource: StateFlow<AudioSourceMode> = _selectedAudioSource

    val audioSource: Int
        get() = _selectedAudioSource.value.recorderValue

    init {
        scope.launch {
            repo.getAudioCaptureSource
                .distinctUntilChanged()
                .collect { sourceValue ->
                    val source = AudioSourceMode.fromRecorderValue(sourceValue)
                    _selectedAudioSource.value = source
                    _fieldRecordingStatus.value =
                        _fieldRecordingStatus.value.copy(selectedSource = source)
                }
        }
    }

    suspend fun requestStartRecording() {
        _captureCommands.emit(CaptureCommand.StartModelCapture)
    }

    suspend fun requestStopRecording() {
        _captureCommands.emit(CaptureCommand.StopModelCapture)
    }

    suspend fun requestPauseRecording() {
        _captureCommands.emit(CaptureCommand.PauseFieldSession)
    }

    suspend fun requestStartFieldRecording() {
        clearRecordingError()
        _captureCommands.emit(CaptureCommand.StartFieldSession)
    }

    suspend fun requestStopFieldRecording() {
        _captureCommands.emit(CaptureCommand.StopFieldSession)
    }

    suspend fun requestSelectAudioSource(source: AudioSourceMode) {
        repo.storeAudioCaptureSource(source.recorderValue)
        Log.i(TAG, "AUDIO_SOURCE_SELECTED source=${source.name} recorderValue=${source.recorderValue}")
        _selectedAudioSource.emit(source)
        _fieldRecordingStatus.emit(_fieldRecordingStatus.value.copy(selectedSource = source))
        _captureCommands.emit(CaptureCommand.ApplyAudioSource(source))
    }

    suspend fun updateRecordingSessionState(isSessionRunnning: Boolean) {
        _recordingSessionState.emit(isSessionRunnning)
    }

    suspend fun updateRecordingState(state: RecordingState) {
        _recordingState.emit(state)
    }

    suspend fun updateFieldRecordingStatus(status: FieldRecordingStatus) {
        _fieldRecordingStatus.emit(status.copy(selectedSource = _selectedAudioSource.value))
    }

    suspend fun clearFieldRecordingStatus() {
        _fieldRecordingStatus.emit(FieldRecordingStatus(selectedSource = _selectedAudioSource.value))
    }

    suspend fun updateRecordingError(message: String?) {
        if (!message.isNullOrBlank()) {
            Log.e(TAG, "AUDIO_CAPTURE_ERROR $message")
        }
        _recordingError.emit(message)
    }

    suspend fun clearRecordingError() {
        _recordingError.emit(null)
    }
}
