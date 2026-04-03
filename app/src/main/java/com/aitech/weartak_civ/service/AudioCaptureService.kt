package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.ArtifactStorage
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.BufferedOutputStream
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureService : Service() {

    companion object {
        val isRunning = AtomicBoolean(false)
    }

    private val SAMPLE_RATE_HZ = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val READ_SIZE = 2048
    private val MIN_INTERNAL_BUFFER_MULT = 2
    private val SEGMENT_DURATION_SEC = 60
    private val SEGMENT_SAMPLE_COUNT = SAMPLE_RATE_HZ * SEGMENT_DURATION_SEC
    private val SEGMENT_MIN_FREE_BYTES = SEGMENT_SAMPLE_COUNT * 2L + 32_768L

    private val NOTIFICATION_ID = 201
    private val TAG = "WearTAK-AudioCapSrvc"

    private val modelCaptureRequested = AtomicBoolean(false)
    private val fieldSessionRequested = AtomicBoolean(false)
    private val isCollecting = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val fieldSessionMutex = Mutex()
    private var fieldSessionRuntime: FieldSessionRuntime? = null

    private data class SegmentRuntime(
        val index: Int,
        val pcmFile: File,
        val jsonFile: File,
        val output: BufferedOutputStream,
        val openedAtMs: Long,
        var sampleCount: Long = 0,
        var firstChunkTimestampMs: Long? = null,
        var lastChunkTimestampMs: Long? = null
    )

    private data class FieldSessionRuntime(
        val sessionId: String,
        val sessionDir: File,
        val startedAtMs: Long,
        val source: AudioCaptureRepository.AudioSourceMode,
        var nextSegmentIndex: Int = 1,
        var currentSegment: SegmentRuntime? = null
    )

    private val repo by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }

    private val audioRepo by lazy {
        try {
            (applicationContext as WearTakApplication).audioRepo
        } catch (_: Throwable) {
            AudioCaptureRepository(applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(NOTIFICATION_ID, importance = NotificationManager.IMPORTANCE_LOW)
        collectAudioFlows()
        serviceScope.launch {
            audioRepo.clearFieldRecordingStatus()
            audioRepo.clearRecordingError()
        }
        if (!hasRecordAudioPermission()) {
            updateRecordingState(AudioCaptureRepository.RecordingState.UNAVAILABLE)
        } else {
            updateRecordingState(AudioCaptureRepository.RecordingState.AVAILABLE)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() action=${intent?.action}")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        runBlocking {
            destroyCleanly()
        }
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_DETACH)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun collectAudioFlows() {
        serviceScope.launch {
            audioRepo.captureCommands.collect { command ->
                when (command) {
                    AudioCaptureRepository.CaptureCommand.StartModelCapture -> handleStartModelCapture()
                    AudioCaptureRepository.CaptureCommand.StopModelCapture -> handleStopModelCapture()
                    AudioCaptureRepository.CaptureCommand.StartFieldSession -> handleStartFieldSession()
                    AudioCaptureRepository.CaptureCommand.StopFieldSession -> handleStopFieldSession()
                    AudioCaptureRepository.CaptureCommand.PauseFieldSession -> handlePauseFieldSession()
                    is AudioCaptureRepository.CaptureCommand.ApplyAudioSource ->
                        handleApplyAudioSource(command.source)
                }
            }
        }

        serviceScope.launch {
            audioRepo.getRecordingState.collect { state ->
                Log.i(TAG, "RECORDING_STATE state=$state")
            }
        }

        serviceScope.launch {
            audioRepo.getRecordingSessionState.collect { active ->
                Log.i(TAG, "CAPTURE_SESSION_ACTIVE active=$active")
            }
        }

        serviceScope.launch {
            audioRepo.fieldRecordingStatus.collect { status ->
                Log.i(
                    TAG,
                    "FIELD_SESSION_STATUS active=${status.active} sessionId=${status.sessionId} " +
                        "source=${status.selectedSource.name} segmentIndex=${status.segmentIndex}"
                )
            }
        }
    }

    private suspend fun handleStartModelCapture() {
        if (modelCaptureRequested.getAndSet(true)) {
            Log.i(TAG, "MODEL_CAPTURE demand already active")
            return
        }
        syncCaptureState("model_start")
    }

    private suspend fun handleStopModelCapture() {
        if (!modelCaptureRequested.getAndSet(false)) {
            Log.i(TAG, "MODEL_CAPTURE demand already inactive")
            return
        }
        syncCaptureState("model_stop")
    }

    private suspend fun handleStartFieldSession() {
        if (fieldSessionRequested.getAndSet(true)) {
            Log.i(TAG, "FIELD_AUDIO start ignored; session already active")
            return
        }

        audioRepo.clearRecordingError()
        val started = if (isCollecting.get()) true else startAudioCollection("field_start")
        if (!started) {
            fieldSessionRequested.set(false)
            audioRepo.clearFieldRecordingStatus()
            return
        }

        fieldSessionMutex.withLock {
            startNewFieldSessionLocked("field_start")
        }
    }

    private suspend fun handleStopFieldSession() {
        if (!fieldSessionRequested.getAndSet(false)) {
            Log.i(TAG, "FIELD_AUDIO stop ignored; session not active")
            return
        }

        fieldSessionMutex.withLock {
            closeFieldSessionLocked("field_stop")
        }
        syncCaptureState("field_stop")
    }

    private suspend fun handlePauseFieldSession() {
        Log.i(TAG, "FIELD_AUDIO pause requested but not enabled for WEAR-454")
        audioRepo.updateRecordingError("Pause / Resume is not enabled for WEAR-454 MVP.")
    }

    private suspend fun handleApplyAudioSource(source: AudioCaptureRepository.AudioSourceMode) {
        Log.i(
            TAG,
            "AUDIO_SOURCE_APPLY source=${source.name} collecting=${isCollecting.get()} " +
                "fieldActive=${fieldSessionRequested.get()} modelActive=${modelCaptureRequested.get()}"
        )

        audioRepo.clearRecordingError()

        if (!isCollecting.get()) {
            Log.i(TAG, "AUDIO_SOURCE_APPLIED source=${source.name} restarted=false")
            return
        }

        val resumeFieldSession = fieldSessionRequested.get()
        fieldSessionMutex.withLock {
            if (resumeFieldSession) {
                closeFieldSessionLocked("source_change")
            }
        }

        stopAudioCollection("source_change")
        val restarted = startAudioCollection("source_change")

        if (!restarted) {
            if (resumeFieldSession) {
                fieldSessionRequested.set(false)
                audioRepo.clearFieldRecordingStatus()
            }
            return
        }

        if (resumeFieldSession) {
            fieldSessionMutex.withLock {
                startNewFieldSessionLocked("source_change")
            }
        }

        Log.i(TAG, "AUDIO_SOURCE_APPLIED source=${source.name} restarted=true")
    }

    private suspend fun syncCaptureState(reason: String) {
        val shouldCapture = modelCaptureRequested.get() || fieldSessionRequested.get()

        if (shouldCapture) {
            if (!isCollecting.get()) {
                startAudioCollection(reason)
            }
        } else if (isCollecting.get()) {
            stopAudioCollection(reason)
        }
    }

    private suspend fun destroyCleanly() {
        modelCaptureRequested.set(false)
        fieldSessionRequested.set(false)
        fieldSessionMutex.withLock {
            closeFieldSessionLocked("service_destroy")
        }
        stopAudioCollection("service_destroy")
        audioRepo.updateRecordingState(AudioCaptureRepository.RecordingState.UNAVAILABLE)
    }

    private suspend fun startAudioCollection(reason: String): Boolean {
        if (!isRunning.get()) {
            Log.w(TAG, "AUDIO_CAPTURE start ignored; service not running")
            return false
        }
        if (isCollecting.getAndSet(true)) {
            Log.i(TAG, "AUDIO_CAPTURE start ignored; already collecting")
            return true
        }

        if (!hasRecordAudioPermission()) {
            val message = "RECORD_AUDIO permission not granted"
            Log.e(TAG, message)
            isCollecting.set(false)
            updateRecordingState(AudioCaptureRepository.RecordingState.UNAVAILABLE)
            audioRepo.updateRecordingError(message)
            return false
        }

        val selectedSource = audioRepo.selectedAudioSource.value
        Log.i(
            TAG,
            "AUDIO_CAPTURE_INIT reason=$reason source=${selectedSource.name} " +
                "recorderValue=${selectedSource.recorderValue}"
        )

        try {
            initAudioRecord(selectedSource)
        } catch (t: Throwable) {
            val message = "Audio source ${selectedSource.name} failed to initialize: ${t.message}"
            Log.e(TAG, message, t)
            isCollecting.set(false)
            updateRecordingState(AudioCaptureRepository.RecordingState.AVAILABLE)
            audioRepo.updateRecordingError(message)
            return false
        }

        audioRepo.resetNumFilesWrittenSinceFieldStart()
        audioRepo.resetWriteErrorsSinceFieldStart()

        recordingJob = serviceScope.launch {
            try {
                audioRecord?.startRecording()
                updateRecordingSessionState(true)
                updateRecordingState(AudioCaptureRepository.RecordingState.RECORDING)
                audioRepo.clearRecordingError()
                Log.i(
                    TAG,
                    "AUDIO_CAPTURE_STARTED reason=$reason source=${selectedSource.name} " +
                        "recorderValue=${selectedSource.recorderValue}"
                )
            } catch (t: Throwable) {
                val message = "startRecording failed for ${selectedSource.name}: ${t.message}"
                Log.e(TAG, message, t)
                isCollecting.set(false)
                updateRecordingSessionState(false)
                updateRecordingState(AudioCaptureRepository.RecordingState.AVAILABLE)
                audioRepo.updateRecordingError(message)
                if (fieldSessionRequested.getAndSet(false)) {
                    fieldSessionMutex.withLock {
                        closeFieldSessionLocked("start_failed")
                    }
                }
                return@launch
            }

            val shortBuf = ShortArray(READ_SIZE / 2)
            var seq = 0L
            val sessionId = "audio_${System.currentTimeMillis()}"

            while (isCollecting.get()) {
                val readShorts = try {
                    audioRecord?.read(shortBuf, 0, shortBuf.size)
                        ?: AudioRecord.ERROR_INVALID_OPERATION
                } catch (t: Throwable) {
                    Log.e(TAG, "AudioRecord.read(short[]) error: ${t.message}", t)
                    AudioRecord.ERROR
                }

                when {
                    readShorts > 0 -> {
                        val nowMs = System.currentTimeMillis()
                        val chunk = AudioCaptureRepository.AudioPcmChunk(
                            sessionId = sessionId,
                            seq = seq++,
                            timestampMs = nowMs,
                            timestampElMs = android.os.SystemClock.elapsedRealtime(),
                            sampleRateHz = SAMPLE_RATE_HZ,
                            channels = 1,
                            samples = shortBuf.copyOf(readShorts)
                        )

                        if (fieldSessionRequested.get()) {
                            try {
                                writeChunkToFieldSession(chunk)
                            } catch (t: Throwable) {
                                val message = "Field audio write failed: ${t.message}"
                                Log.e(TAG, message, t)
                                fieldSessionRequested.set(false)
                                fieldSessionMutex.withLock {
                                    closeFieldSessionLocked("write_error")
                                }
                                audioRepo.updateRecordingError(message)
                                syncCaptureState("field_write_error")
                            }
                        }

                        val emitted = audioRepo.tryEmitChunk(chunk)
                        if (!emitted) {
                            Log.w(
                                TAG,
                                "PCM chunk dropped seq=${seq - 1} sampleCount=$readShorts " +
                                    "source=${selectedSource.name}"
                            )
                        }
                    }

                    readShorts == AudioRecord.ERROR_INVALID_OPERATION ||
                        readShorts == AudioRecord.ERROR_BAD_VALUE -> {
                        yield()
                    }

                    readShorts == AudioRecord.ERROR -> {
                        delay(5)
                    }
                }
            }
        }

        return true
    }

    private suspend fun stopAudioCollection(reason: String) {
        if (!isCollecting.getAndSet(false)) {
            return
        }

        Log.i(TAG, "AUDIO_CAPTURE_STOP reason=$reason")

        recordingJob?.cancelAndJoin()
        recordingJob = null

        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }

        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }

        audioRecord = null
        updateRecordingSessionState(false)
        updateRecordingState(
            if (hasRecordAudioPermission()) {
                AudioCaptureRepository.RecordingState.AVAILABLE
            } else {
                AudioCaptureRepository.RecordingState.UNAVAILABLE
            }
        )
    }

    private suspend fun writeChunkToFieldSession(chunk: AudioCaptureRepository.AudioPcmChunk) {
        fieldSessionMutex.withLock {
            val runtime = fieldSessionRuntime ?: startNewFieldSessionLocked("lazy")
            var offset = 0

            while (offset < chunk.samples.size) {
                val segment = ensureSegmentLocked(runtime)
                val remaining = SEGMENT_SAMPLE_COUNT - segment.sampleCount.toInt()
                val copyCount = minOf(remaining, chunk.samples.size - offset)

                writePcmSamples(segment.output, chunk.samples, offset, copyCount)

                segment.sampleCount += copyCount.toLong()
                segment.firstChunkTimestampMs = segment.firstChunkTimestampMs ?: chunk.timestampMs
                segment.lastChunkTimestampMs = chunk.timestampMs
                offset += copyCount

                if (segment.sampleCount >= SEGMENT_SAMPLE_COUNT.toLong()) {
                    closeCurrentSegmentLocked(runtime, "segment_full")
                }
            }
        }
    }

    private suspend fun startNewFieldSessionLocked(reason: String): FieldSessionRuntime {
        val source = audioRepo.selectedAudioSource.value
        val nowMs = System.currentTimeMillis()
        val sessionId = buildSessionId(nowMs, source)
        val rootDir = ArtifactStorage.fieldAudioRoot(this)
        ensureUsableSpace(rootDir)

        val sessionDir = File(rootDir, sessionId).apply { mkdirs() }
        val runtime = FieldSessionRuntime(
            sessionId = sessionId,
            sessionDir = sessionDir,
            startedAtMs = nowMs,
            source = source
        )

        fieldSessionRuntime = runtime
        audioRepo.updateFieldRecordingStatus(
            AudioCaptureRepository.FieldRecordingStatus(
                active = true,
                startedAtMs = nowMs,
                sessionId = sessionId,
                selectedSource = source,
                segmentIndex = 0
            )
        )

        Log.i(
            TAG,
            "FIELD_AUDIO_SESSION_STARTED reason=$reason sessionId=$sessionId " +
                "source=${source.name} dir=${sessionDir.absolutePath}"
        )

        return runtime
    }

    private suspend fun closeFieldSessionLocked(reason: String) {
        val runtime = fieldSessionRuntime ?: run {
            audioRepo.clearFieldRecordingStatus()
            return
        }

        closeCurrentSegmentLocked(runtime, reason)
        Log.i(
            TAG,
            "FIELD_AUDIO_SESSION_STOPPED reason=$reason sessionId=${runtime.sessionId} " +
                "dir=${runtime.sessionDir.absolutePath}"
        )
        fieldSessionRuntime = null
        audioRepo.clearFieldRecordingStatus()
        audioRepo.resetWriteErrorsSinceFieldStart()
    }

    private suspend fun ensureSegmentLocked(runtime: FieldSessionRuntime): SegmentRuntime {
        runtime.currentSegment?.let { return it }

        ensureUsableSpace(runtime.sessionDir)

        val segmentIndex = runtime.nextSegmentIndex++
        val sourceLabel = runtime.source.name.lowercase(Locale.US)
        val segmentBase = "chunk_${segmentIndex.toString().padStart(6, '0')}_$sourceLabel"
        val pcmFile = File(runtime.sessionDir, "$segmentBase.pcm")
        val jsonFile = File(runtime.sessionDir, "$segmentBase.json")
        val output = BufferedOutputStream(FileOutputStream(pcmFile, false), 32 * 1024)

        val segment = SegmentRuntime(
            index = segmentIndex,
            pcmFile = pcmFile,
            jsonFile = jsonFile,
            output = output,
            openedAtMs = System.currentTimeMillis()
        )

        runtime.currentSegment = segment
        audioRepo.updateFieldRecordingStatus(
            AudioCaptureRepository.FieldRecordingStatus(
                active = true,
                startedAtMs = runtime.startedAtMs,
                sessionId = runtime.sessionId,
                selectedSource = runtime.source,
                segmentIndex = segmentIndex
            )
        )

        Log.i(
            TAG,
            "FIELD_AUDIO_SEGMENT_OPENED sessionId=${runtime.sessionId} " +
                "segmentIndex=$segmentIndex source=${runtime.source.name}"
        )

        return segment
    }

    private suspend fun closeCurrentSegmentLocked(
        runtime: FieldSessionRuntime,
        reason: String
    ) {
        val segment = runtime.currentSegment ?: return

        runtime.currentSegment = null
        try {
            segment.output.flush()
        } finally {
            segment.output.close()
        }

        if (segment.sampleCount <= 0L) {
            segment.pcmFile.delete()
            segment.jsonFile.delete()
            return
        }

        val location = repo.snapshotLocation()
        val metadata = buildSegmentMetadata(runtime, segment, location)
        segment.jsonFile.writeText(metadata.toString(2), Charsets.UTF_8)

        Log.i(
            TAG,
            "FIELD_AUDIO_SEGMENT_CLOSED reason=$reason sessionId=${runtime.sessionId} " +
                "segmentIndex=${segment.index} samples=${segment.sampleCount} " +
                "path=${segment.pcmFile.absolutePath}"
        )
        audioRepo.incrementNumFilesWrittenSinceFieldStart()

        /*
        // TODO: Remove. Only for test
        if (audioRepo.numFilesWrittenSinceFieldStart.value == 1) {
            audioRepo.setWriteErrorDuringFieldSession()
        } else {
            audioRepo.resetWriteErrorsSinceFieldStart()
        } */
    }

    private fun buildSegmentMetadata(
        runtime: FieldSessionRuntime,
        segment: SegmentRuntime,
        location: DataRepository.LocationSnapshot?
    ): JSONObject {
        val json = JSONObject()
        json.put("session_id", runtime.sessionId)
        json.put("segment_index", segment.index)
        json.put("file_name", segment.pcmFile.name)
        json.put("device_uid", safeDeviceUid())
        json.put("audio_source", runtime.source.name)
        json.put("audio_source_display", runtime.source.displayLabel)
        json.put("sample_rate_hz", SAMPLE_RATE_HZ)
        json.put("channels", 1)
        json.put("pcm_encoding", "PCM_16BIT_LE")
        json.put("sample_count", segment.sampleCount)
        json.put("segment_opened_at_ms", segment.openedAtMs)
        json.put("first_chunk_timestamp_ms", segment.firstChunkTimestampMs ?: segment.openedAtMs)
        json.put("last_chunk_timestamp_ms", segment.lastChunkTimestampMs ?: segment.openedAtMs)
        json.put("battery_percent", repo.nonNullBatteryPercentage.value)

        if (location != null) {
            val locJson = JSONObject()
            locJson.put("time_millis", location.timeMillis)
            locJson.put("lat", location.lat)
            locJson.put("lon", location.lon)
            locJson.put("hae", location.hae)
            locJson.put("ce", location.ce)
            locJson.put("le", location.le)
            locJson.put("course", location.course)
            locJson.put("speed", location.speed)
            json.put("location", locJson)
        } else {
            json.put("location", JSONObject.NULL)
        }

        return json
    }

    private fun writePcmSamples(
        output: BufferedOutputStream,
        samples: ShortArray,
        offset: Int,
        count: Int
    ) {
        val bytes = ByteArray(count * 2)
        var srcIndex = offset
        var dstIndex = 0
        repeat(count) {
            val value = samples[srcIndex++].toInt()
            bytes[dstIndex++] = (value and 0xFF).toByte()
            bytes[dstIndex++] = ((value shr 8) and 0xFF).toByte()
        }
        try {
            output.write(bytes)
        } catch (ioException: IOException) {
            Log.e(TAG, "writePcmSamples failed: ${ioException.message}", ioException)
            audioRepo.setWriteErrorDuringFieldSession()
        }
    }

    private fun buildSessionId(
        nowMs: Long,
        source: AudioCaptureRepository.AudioSourceMode
    ): String {
        val utcFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "${safeDeviceUid()}_${utcFormatter.format(Date(nowMs))}_${source.name.lowercase(Locale.US)}"
    }

    private fun ensureUsableSpace(targetDir: File) {
        val availableBytes = runCatching {
            StatFs(targetDir.absolutePath).availableBytes
        }.getOrNull()

        val usableBytes = availableBytes?.takeIf { it > 0 } ?: targetDir.usableSpace.takeIf { it > 0 }

        if (usableBytes == null) {
            Log.w(
                TAG,
                "Unable to determine usable space for ${targetDir.absolutePath}; skipping free-space guard"
            )
            return
        }

        if (usableBytes < SEGMENT_MIN_FREE_BYTES) {
            Log.w(
                TAG,
                "Not enough space for new field-audio segment (available=$usableBytes threshold=$SEGMENT_MIN_FREE_BYTES)"
            )
            throw IOException("Insufficient free space for next audio segment")
        }
    }

    private fun safeDeviceUid(): String =
        runCatching { device_uid }
            .getOrDefault("UNKNOWN_DEVICE")

    private fun hasRecordAudioPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun updateRecordingSessionState(isSessionRunning: Boolean) {
        serviceScope.launch {
            audioRepo.updateRecordingSessionState(isSessionRunning)
        }
    }

    private fun updateRecordingState(state: AudioCaptureRepository.RecordingState) {
        serviceScope.launch {
            audioRepo.updateRecordingState(state)
        }
    }

    private fun initAudioRecord(source: AudioCaptureRepository.AudioSourceMode) {
        if (audioRecord != null) return

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
        require(minBuf != AudioRecord.ERROR && minBuf != AudioRecord.ERROR_BAD_VALUE) {
            "Invalid min buffer size"
        }
        val bufferSize = maxOf(minBuf * MIN_INTERNAL_BUFFER_MULT, READ_SIZE)

        audioRecord = AudioRecord(
            source.recorderValue,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        val state = audioRecord?.state
        require(state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord init failed for ${source.name} (state=$state)"
        }

        Log.i(
            TAG,
            "AUDIO_RECORD_INITIALIZED source=${source.name} " +
                "recorderValue=${source.recorderValue} sampleRate=$SAMPLE_RATE_HZ"
        )
    }
}
