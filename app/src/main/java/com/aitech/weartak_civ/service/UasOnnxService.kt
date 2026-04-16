package com.aitech.weartak_civ.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.UasOnnxRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

class UasOnnxService : Service() {

    private lateinit var env: OrtEnvironment
    private var detectorSession: OrtSession? = null
    @Volatile private var detectorLoaded: Boolean = false

    private val lock = ReentrantLock()

    override fun onBind(intent: Intent?): IBinder? = null

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    private val ring = ShortRingBuffer(WINDOW_SAMPLES)

    private val audioRepo by lazy {
        try {
            (applicationContext as WearTakApplication).audioRepo
        } catch (_: Throwable) {
            AudioCaptureRepository(applicationContext)
        }
    }

    private val uasRepo by lazy {
        try {
            (applicationContext as WearTakApplication).uasOnnxRepo
        } catch (_: Throwable) {
            UasOnnxRepository(applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "UasOnnxService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        env = OrtEnvironment.getEnvironment()
        ensureDetectorLoaded()

        scope.launch { audioRepo.requestStartRecording() }
        pcmCollectJob = scope.launch { collectPcmStream() }
        scope.launch { collectUasFeedback() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "UasOnnxService onStartCommand()")
        return START_NOT_STICKY
    }

    private suspend fun collectUasFeedback() {
        uasRepo.uasFeedback.collect { fb ->
            val event = uasRepo.getEventFromUid(fb.eventUid)
            val nowEl = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "UAS_FEEDBACK uid=${fb.eventUid} label=${fb.label} " +
                    "respLatencyMs=${fb.responseLatencyMs} respondedAtMs=${fb.respondedAtMs} " +
                    "serviceSeenEl=$nowEl"
            )
            if (event != null) {
                Utils.logUasFeedback(
                    context = applicationContext,
                    event = event,
                    label = fb.label,
                    respondedAtMs = fb.respondedAtMs,
                    responseLatencyMs = fb.responseLatencyMs
                )
            } else {
                Log.w(TAG, "orphan feedback -- no parent UAS event found for this feedback")
            }
        }
    }

    private var lastInferEl = 0L
    private var lastSeq: Long? = null
    private var pcmCollectJob: Job? = null

    private suspend fun collectPcmStream() {
        audioRepo.getPcmChunks.collect { chunk ->
            val prev = lastSeq
            lastSeq = chunk.seq

            if (prev != null && chunk.seq != prev + 1) {
                Log.w(TAG, "PCM GAP: prev=$prev now=${chunk.seq} (dropping window until refilled)")
                ring.reset()
                return@collect
            }

            ring.append(chunk.samples)

            val nowEl = SystemClock.elapsedRealtime()
            if (nowEl - lastInferEl < HOP_MS) return@collect

            val windowPcm = ring.snapshot() ?: return@collect

            val inferStart = SystemClock.elapsedRealtime()
            val uas = runDetectorOnPcm16(windowPcm, chunk.sampleRateHz) ?: return@collect
            val inferEnd = SystemClock.elapsedRealtime()
            lastInferEl = inferEnd

            val inferMs = inferEnd - inferStart
            val audioAgeMs = nowEl - chunk.timestampElMs

            if (uas.isUas && (nowEl - lastTriggerMs) > TRIGGER_COOLDOWN_MS) {
                lastTriggerMs = nowEl

                val dir = File(filesDir, "uas_debug").apply { mkdirs() }
                val uid = UUID.randomUUID().toString()
                val windowFile = File(dir, "$uid.pcm")

                Log.i(
                    TAG,
                    "UAS_TRIGGER uid=$uid conf=${"%.4f".format(uas.confidence)} " +
                        "chunkSeq=${chunk.seq} readSamples=${chunk.samples.size} sr=${chunk.sampleRateHz} " +
                        "audioToDetectWallMs=$audioAgeMs inferMs=$inferMs"
                )

                val event = try {
                    uasRepo.createUasEvent(
                        uid = uid,
                        confidence = uas.confidence,
                        pcmChunk = chunk,
                        triggerPcmPath = windowFile.absolutePath,
                        audioSource = audioRepo.audioSource.toString(),
                        inferStart = inferStart,
                        inferEnd = inferEnd
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed creating UAS event: ${t.message}", t)
                    return@collect
                }

                uasRepo.upsertRecentUas(event)

                val windowCopy = windowPcm.copyOf()

                try {
                    launchPcmWrite(uid, windowFile, windowCopy)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed writing trigger PCM: ${t.message}", t)
                }
            }
        }
    }

    private fun writePcm16Le(windowFile: File, windowCopy: ShortArray) {
        FileOutputStream(windowFile, false).use { out ->
            val bytes = ByteArray(windowCopy.size * 2)
            var j = 0
            for (s in windowCopy) {
                val v = s.toInt()
                bytes[j++] = (v and 0xFF).toByte()
                bytes[j++] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
        }
    }

    private val pcmWriteGate = kotlinx.coroutines.sync.Semaphore(1)

    private fun launchPcmWrite(uid: String, windowFile: File, windowCopy: ShortArray) {
        scope.launch(Dispatchers.IO) {
            if (!pcmWriteGate.tryAcquire()) {
                Log.w(TAG, "UAS_PCM_SAVE_SKIPPED uid=$uid reason=writer_busy")
                return@launch
            }
            try {
                val t0 = SystemClock.elapsedRealtime()
                writePcm16Le(windowFile, windowCopy)
                val t1 = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "UAS_PCM_SAVED uid=$uid bytes=${windowCopy.size * 2} writeMs=${t1 - t0} path=${windowFile.absolutePath}"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "UAS_PCM_SAVE_FAIL uid=$uid err=${t.message}", t)
            } finally {
                pcmWriteGate.release()
            }
        }
    }

    private fun ensureDetectorLoaded(assetName: String = DETECTOR_ASSET): Boolean {
        if (detectorLoaded && detectorSession != null) return true

        return lock.withLock {
            if (detectorLoaded && detectorSession != null) return@withLock true

            try {
                detectorSession?.close()
                detectorSession = null
                detectorLoaded = false

                val modelBytes = assets.open(assetName).use { it.readBytes() }
                val opts = OrtSession.SessionOptions()
                detectorSession = env.createSession(modelBytes, opts)

                detectorLoaded = true
                Log.d(TAG, "Detector loaded: inputs=${detectorSession!!.inputNames}, outputs=${detectorSession!!.outputNames}")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load detector model from assets: $assetName", t)
                detectorLoaded = false
                detectorSession = null
                false
            }
        }
    }

    fun runDetectorOnPcm16(audioPcm: ShortArray, sampleRateHz: Int): UasDetectionResult? {
        val session = detectorSession

        if (!detectorLoaded || session == null) {
            if (isRunning.get()) Log.e(TAG, "Detector model not loaded")
            return null
        }

        val floatData = pcm16ToFloat(audioPcm)
        val modelInput = forceOneSecond44100(floatData, sampleRateHz)

        return try {
            val score = lock.withLock { runModelScalar(session, modelInput) }
            UasDetectionResult(
                isUas = score >= THRESHOLD,
                confidence = score
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Detector inference error", t)
            null
        }
    }

    private fun pcm16ToFloat(pcm: ShortArray): FloatArray =
        FloatArray(pcm.size) { i -> pcm[i] / 32768f }

    private fun forceOneSecond44100(x: FloatArray, inputSr: Int): FloatArray {
        val resampled = if (inputSr == TARGET_SR) x else linearResample(x, inputSr, TARGET_SR)
        return forceExactLength(resampled, TARGET_SAMPLES)
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

    private class ShortRingBuffer(private val capacity: Int) {
        private val buf = ShortArray(capacity)
        private var writePos = 0
        private var filled = false

        fun append(src: ShortArray) {
            var i = 0
            while (i < src.size) {
                buf[writePos] = src[i]
                writePos++
                if (writePos >= capacity) {
                    writePos = 0
                    filled = true
                }
                i++
            }
        }

        fun reset() {
            writePos = 0
            filled = false
        }

        fun snapshot(): ShortArray? {
            if (!filled) return null
            val out = ShortArray(capacity)
            val tail = capacity - writePos
            System.arraycopy(buf, writePos, out, 0, tail)
            System.arraycopy(buf, 0, out, tail, writePos)
            return out
        }
    }

    override fun onDestroy() {
        isRunning.set(false)
        pcmCollectJob?.cancel()
        scope.cancel()
        lock.withLock {
            try { detectorSession?.close() } catch (_: Throwable) {}
            detectorSession = null
            detectorLoaded = false
        }
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
        Log.d(TAG, "UasOnnxService onDestroy()")
    }

    data class UasDetectionResult(
        val isUas: Boolean,
        val confidence: Float
    )

    companion object {
        private const val TAG = "WearTAK-UasOnnxService"
        private const val NOTIFICATION_ID = 920
        val isRunning: AtomicBoolean = AtomicBoolean(false)

        private const val DETECTOR_ASSET = "falcon-bin.onnx"
        private const val THRESHOLD = 0.5f
        private const val TARGET_SR = 44_100
        private const val TARGET_SAMPLES = 44_100
        private const val WINDOW_SAMPLES = 44_100
        private const val HOP_MS = 1000L

        private var lastTriggerMs = 0L
        private const val TRIGGER_COOLDOWN_MS = 3000L
    }
}
