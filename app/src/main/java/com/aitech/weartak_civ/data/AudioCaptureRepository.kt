package com.aitech.weartak_civ.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "data")
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class AudioCaptureRepository(private val context: Context) {

    private val TAG = "WearTAK-" + AudioCaptureRepository::class.java.simpleName
    enum class RecordAction {
        START,
        STOP,
        PAUSE
    }

    enum class RecordingState {
        RECORDING,
        PAUSED,
        PLAYING,
        AVAILABLE,
        UNAVAILABLE
    }

    // AudoCapService listens to UI recording requests
    private val _recordAudioRequest = MutableSharedFlow<RecordAction>(0)
    val getRecordRequest: SharedFlow<RecordAction> = _recordAudioRequest

    // So the UI knows what buttons to show the user (e.g. stop, pause, record)
    private val _recordingState = MutableSharedFlow<RecordingState>(1)
    val getRecordingState: SharedFlow<RecordingState> = _recordingState

    // So the UI knows when to start/stop showing the user that a recording session is in session. Like the macro event of a recording sesh.
    private val _recordingSessionState = MutableSharedFlow<Boolean>(1)
    val getRecordingSessionState: SharedFlow<Boolean> = _recordingSessionState

    suspend fun requestStartRecording() {
        _recordAudioRequest.emit(RecordAction.START)
    }

    suspend fun requestStopRecording() {
        _recordAudioRequest.emit(RecordAction.STOP)
    }

    suspend fun requestPauseRecording() {
        _recordAudioRequest.emit(RecordAction.PAUSE)
    }

    suspend fun updateRecordingSessionState(isSessionRunnning: Boolean) {
        _recordingSessionState.emit(isSessionRunnning)
    }

    suspend fun updateRecordingState(state: RecordingState) {
        _recordingState.emit(state)
    }
}