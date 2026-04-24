package com.aitech.weartak_civ.service.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

data class AudioDetectionSpec(
    val detectorKey: String,
    val assetName: String,
    val threshold: Float,
    val debugDirName: String,
    val logTag: String,
    val triggerPrefix: String,
    val feedbackPrefix: String,
    val targetSr: Int = 44_100,
    val targetSamples: Int = 44_100,
    val windowSamples: Int = 44_100,
    val hopSamples: Int = windowSamples / 2,
    val triggerCooldownMs: Long = 3000L,
    val metricsSegmentMaxBytes: Long = 4L * 1024L * 1024L,
    val metricsDirMaxBytes: Long = 32L * 1024L * 1024L,
    val triggerPcmDirMaxBytes: Long = 16L * 1024L * 1024L
) {
    init {
        require(windowSamples > 0) { "windowSamples must be > 0" }
        require(hopSamples in 1..windowSamples) { "hopSamples must be within 1..windowSamples" }
    }
}

data class AudioDetectionInferenceResult(
    val inferenceSucceeded: Boolean,
    val detected: Boolean,
    val confidence: Float?,
    val errorMessage: String? = null
)

data class AudioDetectionTrigger(
    val uid: String,
    val confidence: Float,
    val pcmChunk: AudioCaptureRepository.AudioPcmChunk,
    val triggerPcmPath: String,
    val audioSource: String,
    val inferStart: Long,
    val inferEnd: Long
)

class AudioDetectionRuntime(
    private val context: Context,
    private val spec: AudioDetectionSpec,
    private val scope: CoroutineScope,
    private val audioRepo: AudioCaptureRepository,
    private val pcmFlow: SharedFlow<AudioCaptureRepository.AudioPcmChunk>,
    private val thresholdProvider: () -> Float,
    private val onTrigger: suspend (AudioDetectionTrigger) -> Unit
) {
    private lateinit var env: OrtEnvironment
    private var detectorSession: OrtSession? = null
    @Volatile private var detectorLoaded: Boolean = false

    private val lock = ReentrantLock()
    private val windowBuffer = SlidingWindowBuffer()
    private val pcmWriteGate = kotlinx.coroutines.sync.Semaphore(1)
    private val metricsLogger = AudioDetectionMetricsLogger(context, spec)

    private var lastSeq: Long? = null
    private var lastTriggerMs = 0L
    private var pcmCollectJob: Job? = null
    private var streamSegmentIndex = 0
    private var nextWindowIndex = 0L
    private var nextWindowStartSample = 0L

    fun start() {
        env = OrtEnvironment.getEnvironment()
        ensureDetectorLoaded()
        scope.launch { audioRepo.requestStartRecording() }
        pcmCollectJob = scope.launch { collectPcmStream() }
    }

    fun shutdown() {
        pcmCollectJob?.cancel()
        metricsLogger.close()
        lock.withLock {
            try { detectorSession?.close() } catch (_: Throwable) {}
            detectorSession = null
            detectorLoaded = false
        }
    }

    private suspend fun collectPcmStream() {
        pcmFlow.collect { chunk ->
            val prev = lastSeq
            lastSeq = chunk.seq

            if (prev != null && chunk.seq != prev + 1) {
                Log.w(spec.logTag, "PCM GAP: prev=$prev now=${chunk.seq} (dropping window until refilled)")
                windowBuffer.reset()
                streamSegmentIndex += 1
                nextWindowIndex = 0L
                nextWindowStartSample = 0L
            }

            windowBuffer.append(chunk.samples)

            while (windowBuffer.hasWindow(spec.windowSamples)) {
                val windowStartSample = nextWindowStartSample
                val windowEndSample = windowStartSample + spec.windowSamples
                val windowPcm = windowBuffer.peekWindow(spec.windowSamples)
                val threshold = thresholdProvider().coerceIn(0f, 1f)

                val inferStart = SystemClock.elapsedRealtime()
                val cpuStartMs = AudioDetectionMetricsLogger.currentProcessCpuTimeMs()
                val detection = runDetectorOnPcm16(windowPcm, chunk.sampleRateHz, threshold)
                val cpuEndMs = AudioDetectionMetricsLogger.currentProcessCpuTimeMs()
                val inferEnd = SystemClock.elapsedRealtime()

                val inferMs = inferEnd - inferStart
                val audioAgeMs = inferStart - chunk.timestampElMs
                val audioSource = audioRepo.audioSource.toString()
                val shouldTrigger = detection.detected && (inferEnd - lastTriggerMs) > spec.triggerCooldownMs
                val cooldownSuppressed = detection.detected && !shouldTrigger
                var triggerUid: String? = null
                var triggerPcmPath: String? = null

                if (shouldTrigger) {
                    lastTriggerMs = inferEnd

                    val uid = UUID.randomUUID().toString()
                    val windowFile = metricsLogger.triggerPcmFile(uid)
                    triggerUid = uid
                    triggerPcmPath = windowFile.absolutePath

                    Log.i(
                        spec.logTag,
                        "${spec.triggerPrefix}_TRIGGER uid=$uid conf=${"%.4f".format(detection.confidence ?: 0f)} " +
                            "chunkSeq=${chunk.seq} readSamples=${chunk.samples.size} sr=${chunk.sampleRateHz} " +
                            "audioToDetectWallMs=$audioAgeMs inferMs=$inferMs " +
                            "windowIndex=$nextWindowIndex windowStartSample=$windowStartSample"
                    )

                    onTrigger(
                        AudioDetectionTrigger(
                            uid = uid,
                            confidence = detection.confidence ?: 0f,
                            pcmChunk = chunk,
                            triggerPcmPath = triggerPcmPath,
                            audioSource = audioSource,
                            inferStart = inferStart,
                            inferEnd = inferEnd
                        )
                    )

                    val windowCopy = windowPcm.copyOf()
                    launchPcmWrite(uid, windowFile, windowCopy)
                }

                metricsLogger.logInferenceWindow(
                    chunk = chunk,
                    streamSegmentIndex = streamSegmentIndex,
                    windowIndex = nextWindowIndex,
                    windowStartSample = windowStartSample,
                    windowEndSample = windowEndSample,
                    thresholdUsed = threshold,
                    inferStartElapsedMs = inferStart,
                    inferEndElapsedMs = inferEnd,
                    inference = detection,
                    audioSource = audioSource,
                    triggerUid = triggerUid,
                    triggerPcmPath = triggerPcmPath,
                    triggered = shouldTrigger,
                    cooldownSuppressed = cooldownSuppressed,
                    cpuStartMs = cpuStartMs,
                    cpuEndMs = cpuEndMs
                )

                windowBuffer.discard(spec.hopSamples)
                nextWindowIndex += 1
                nextWindowStartSample += spec.hopSamples
            }
        }
    }

    private fun launchPcmWrite(uid: String, windowFile: File, windowCopy: ShortArray) {
        scope.launch(Dispatchers.IO) {
            if (!pcmWriteGate.tryAcquire()) {
                Log.w(spec.logTag, "${spec.triggerPrefix}_PCM_SAVE_SKIPPED uid=$uid reason=writer_busy")
                return@launch
            }
            try {
                val t0 = SystemClock.elapsedRealtime()
                writePcm16Le(windowFile, windowCopy)
                val t1 = SystemClock.elapsedRealtime()
                Log.i(
                    spec.logTag,
                    "${spec.triggerPrefix}_PCM_SAVED uid=$uid bytes=${windowCopy.size * 2} writeMs=${t1 - t0} path=${windowFile.absolutePath}"
                )
                metricsLogger.onTriggerPcmSaved()
            } catch (t: Throwable) {
                Log.e(spec.logTag, "${spec.triggerPrefix}_PCM_SAVE_FAIL uid=$uid err=${t.message}", t)
            } finally {
                pcmWriteGate.release()
            }
        }
    }

    private fun writePcm16Le(windowFile: File, windowCopy: ShortArray) {
        FileOutputStream(windowFile, false).use { out ->
            val bytes = ByteArray(windowCopy.size * 2)
            var j = 0
            for (sample in windowCopy) {
                val value = sample.toInt()
                bytes[j++] = (value and 0xFF).toByte()
                bytes[j++] = ((value shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
        }
    }

    private fun ensureDetectorLoaded(): Boolean {
        if (detectorLoaded && detectorSession != null) return true

        return lock.withLock {
            if (detectorLoaded && detectorSession != null) return@withLock true

            try {
                detectorSession?.close()
                detectorSession = null
                detectorLoaded = false

                val modelBytes = context.assets.open(spec.assetName).use { it.readBytes() }
                val opts = OrtSession.SessionOptions()
                detectorSession = env.createSession(modelBytes, opts)

                detectorLoaded = true
                Log.d(spec.logTag, "Detector loaded: inputs=${detectorSession!!.inputNames}, outputs=${detectorSession!!.outputNames}")
                true
            } catch (t: Throwable) {
                Log.e(spec.logTag, "Failed to load detector model from assets: ${spec.assetName}", t)
                detectorLoaded = false
                detectorSession = null
                false
            }
        }
    }

    private fun runDetectorOnPcm16(
        audioPcm: ShortArray,
        sampleRateHz: Int,
        threshold: Float
    ): AudioDetectionInferenceResult {
        val session = detectorSession

        if (!detectorLoaded || session == null) {
            Log.e(spec.logTag, "Detector model not loaded")
            return AudioDetectionInferenceResult(
                inferenceSucceeded = false,
                detected = false,
                confidence = null,
                errorMessage = "Detector model not loaded"
            )
        }

        val floatData = pcm16ToFloat(audioPcm)
        val modelInput = forceOneSecond(floatData, sampleRateHz)

        return try {
            val score = lock.withLock { runModelScalar(session, modelInput) }
            AudioDetectionInferenceResult(
                inferenceSucceeded = true,
                detected = score >= threshold,
                confidence = score
            )
        } catch (t: Throwable) {
            Log.e(spec.logTag, "Detector inference error", t)
            AudioDetectionInferenceResult(
                inferenceSucceeded = false,
                detected = false,
                confidence = null,
                errorMessage = t.message ?: t.javaClass.simpleName
            )
        }
    }

    private fun pcm16ToFloat(pcm: ShortArray): FloatArray =
        FloatArray(pcm.size) { i -> pcm[i] / 32768f }

    private fun forceOneSecond(x: FloatArray, inputSr: Int): FloatArray {
        val resampled = if (inputSr == spec.targetSr) x else linearResample(x, inputSr, spec.targetSr)
        return forceExactLength(resampled, spec.targetSamples)
    }

    private fun linearResample(x: FloatArray, inSr: Int, outSr: Int): FloatArray {
        if (x.isEmpty()) return FloatArray(0)
        val outLen = ((x.size.toLong() * outSr) / inSr).toInt().coerceAtLeast(1)
        val y = FloatArray(outLen)
        val scale = inSr.toFloat() / outSr.toFloat()
        for (i in 0 until outLen) {
            val src = i * scale
            val idx = src.toInt()
            val frac = src - idx
            val a = x[min(idx, x.size - 1)]
            val b = x[min(idx + 1, x.size - 1)]
            y[i] = a + (b - a) * frac
        }
        return y
    }

    private fun forceExactLength(x: FloatArray, n: Int): FloatArray {
        if (x.size == n) return x
        val y = FloatArray(n)
        val copyN = min(x.size, n)
        System.arraycopy(x, 0, y, 0, copyN)
        return y
    }

    private fun runModelScalar(session: OrtSession, data: FloatArray): Float {
        val shape = longArrayOf(1, data.size.toLong())
        val fb: FloatBuffer = FloatBuffer.wrap(data)
        val inputName = session.inputNames.first()

        OnnxTensor.createTensor(env, fb, shape).use { input ->
            session.run(mapOf(inputName to input)).use { results ->
                val outTensor = results[0] as OnnxTensor
                val outBuf = outTensor.floatBuffer
                outBuf.rewind()
                return outBuf.get()
            }
        }
    }

    private class SlidingWindowBuffer {
        private var buf = ShortArray(0)
        private var size = 0

        fun append(src: ShortArray) {
            if (src.isEmpty()) return
            ensureCapacity(size + src.size)
            System.arraycopy(src, 0, buf, size, src.size)
            size += src.size
        }

        fun hasWindow(windowSamples: Int): Boolean = size >= windowSamples

        fun peekWindow(windowSamples: Int): ShortArray {
            val out = ShortArray(windowSamples)
            System.arraycopy(buf, 0, out, 0, windowSamples)
            return out
        }

        fun discard(samples: Int) {
            if (samples <= 0) return
            if (samples >= size) {
                size = 0
                return
            }

            System.arraycopy(buf, samples, buf, 0, size - samples)
            size -= samples
        }

        fun reset() {
            size = 0
        }

        private fun ensureCapacity(required: Int) {
            if (buf.size >= required) return

            var newCapacity = buf.size.coerceAtLeast(1)
            while (newCapacity < required) {
                newCapacity *= 2
            }
            buf = buf.copyOf(newCapacity)
        }
    }
}

suspend fun collectAudioDetectionFeedback(
    logTag: String,
    feedbackPrefix: String,
    feedbackFlow: Flow<AudioDetectionFeedback>,
    getEventFromUid: (String) -> AudioDetectionEvent?,
    logFeedback: suspend (AudioDetectionEvent, AudioDetectionFeedback) -> Unit
) {
    feedbackFlow.collect { feedback ->
        val event = getEventFromUid(feedback.eventUid)
        val nowEl = SystemClock.elapsedRealtime()
        Log.i(
            logTag,
            "${feedbackPrefix}_FEEDBACK uid=${feedback.eventUid} label=${feedback.label} " +
                "respLatencyMs=${feedback.responseLatencyMs} respondedAtMs=${feedback.respondedAtMs} " +
                "serviceSeenEl=$nowEl"
        )
        if (event != null) {
            logFeedback(event, feedback)
        } else {
            Log.w(logTag, "orphan feedback -- no parent event found for this feedback")
        }
    }
}
