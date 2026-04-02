package com.aitech.weartak_civ.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.OnnxRepository
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
import kotlin.math.min
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.pytorch.executorch.Module;


class OnnxService : Service() {
    private lateinit var env: OrtEnvironment
    private var detectorSession: OrtSession? = null
    @Volatile private var detectorLoaded: Boolean = false

    private val lock = ReentrantLock()

    override fun onBind(intent: Intent?): IBinder? = null

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    private val ring = ShortRingBuffer(WINDOW_SAMPLES)
    private var lastInferMs = 0L

    private val audioRepo by lazy {
        try {
            (applicationContext as WearTakApplication).audioRepo
        } catch (_: Throwable) {
            AudioCaptureRepository(applicationContext)
        }
    }

    private val onnxRepo by lazy {
        try {
            (applicationContext as WearTakApplication).onnxRepo
        } catch (_: Throwable) {
            OnnxRepository(applicationContext)
        }
    }

    private var vulkanModule: Module? = null
    private var useGpu = false

    object VulkanSupport {
        fun isVulkanLikelyAvailable(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            val pm = context.packageManager

            // Basic Vulkan feature presence
            val hasVersion = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
            val hasLevel = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)

            return hasVersion || hasLevel
        }
    }

    object AssetUtils {
        fun copyAssetToFilesDir(
            context: Context,
            assetName: String,
            outFileName: String = assetName
        ): String {
            val outFile = File(context.filesDir, outFileName)

            if (!outFile.exists()) {
                context.assets.open(assetName).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            return outFile.absolutePath
        }
    }


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OnnxService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        env = OrtEnvironment.getEnvironment()
        ensureDetectorLoaded()
        useGpu = VulkanSupport.isVulkanLikelyAvailable(applicationContext)
        Log.d(TAG, android.os.Build.SUPPORTED_ABIS.joinToString())
        if (useGpu) {
            val vulkanPteFileWithPath = AssetUtils.copyAssetToFilesDir(applicationContext, "dart-bin-2chan-vulkan_20260327.pte")
            Log.i(TAG, "vulkanPteFileWithPath=($vulkanPteFileWithPath)")
            try {
                vulkanModule = Module.load(vulkanPteFileWithPath)
                Log.i(TAG, "vulkanModule=$vulkanModule")
            } catch (t: Throwable) {
                useGpu = false
                Log.w(TAG, "Falling back to ONNX CPU path", t)
            }
        }

        scope.launch { audioRepo.requestStartRecording() }
        pcmCollectJob = scope.launch { collectPcmStream() }
        scope.launch { collectGunshotFeedback() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OnnxService onStartCommand()")
        return START_NOT_STICKY
    }

    // When a gunshot event is triggered, the UI asks the user to label it. If they don't label in the timeout window, we label as timeout. Then we write meta data about the event to json log file.
    private suspend fun collectGunshotFeedback() {
        onnxRepo.gunshotFeedback.collect { fb ->
            val event = onnxRepo.getEventFromUid(fb.eventUid)
            val nowEl = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "GS_FEEDBACK uid=${fb.eventUid} label=${fb.label} " +
                        "respLatencyMs=${fb.responseLatencyMs} respondedAtMs=${fb.respondedAtMs} " +
                        "serviceSeenEl=$nowEl"
            )
            if (event != null) {
                Utils.logGunshotFeedback(
                    context = applicationContext,   // if your existing function needs it
                    event = event,
                    label = fb.label,
                    respondedAtMs = fb.respondedAtMs,
                    responseLatencyMs = fb.responseLatencyMs
                )
            } else {
                // Optional: log a short “orphan feedback” line for debugging
                // Utils.writeToLogFileLocation(orphanFile, "orphan feedback uid=${fb.eventUid} label=${fb.label}")
                Log.w(TAG, "orphan feedback -- no parent event found found for this feedabck doing nothing")
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
                ring.reset()          // add a reset() method that clears filled=false, writePos=0
                return@collect
            }

            // 1) Always append audio (never gate before this)
            ring.append(chunk.samples)

            val nowEl = SystemClock.elapsedRealtime()

            // 2) Single cadence gate (elapsed clock)
            if (nowEl - lastInferEl < HOP_MS) return@collect

            // 3) Need full 1 second before inference
            val windowPcm = ring.snapshot() ?: return@collect

            val inferStart = SystemClock.elapsedRealtime()
            val gs = runDetectorOnPcm16(windowPcm, chunk.sampleRateHz) ?: return@collect
            val inferEnd = SystemClock.elapsedRealtime()
            // 4) Update cadence AFTER inference
            lastInferEl = inferEnd

            val inferMs = inferEnd - inferStart
            val audioAgeMs = nowEl - chunk.timestampElMs

            if (gs.isGunshot && (nowEl - lastTriggerMs) > TRIGGER_COOLDOWN_MS) {
                lastTriggerMs = nowEl

                val dir = File(filesDir, "gunshot_debug").apply { mkdirs() }
                val uid = UUID.randomUUID().toString()
                val windowFile = File(dir, "$uid.pcm")

                Log.i(
                    TAG,
                    "GS_TRIGGER uid=$uid conf=${"%.4f".format(gs.confidence)} " +
                            "chunkSeq=${chunk.seq} readSamples=${chunk.samples.size} sr=${chunk.sampleRateHz} " +
                            "audioToDetectWallMs=$audioAgeMs inferMs=$inferMs"
                )

                val event = try {
                    onnxRepo.createOnnxGunshotEvent(
                        uid = uid,
                        confidence = gs.confidence,
                        pcmChunk = chunk,
                        triggerPcmPath = windowFile.absolutePath,
                        audioSource = audioRepo.audioSource.toString(),
                        inferStart = inferStart,
                        inferEnd = inferEnd
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed creating gunshot event: ${t.message}", t)
                    return@collect
                }

                onnxRepo.upsertRecentGunshot(event)

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
                Log.w(TAG, "GS_PCM_SAVE_SKIPPED uid=$uid reason=writer_busy")
                return@launch
            }
            try {
                val t0 = SystemClock.elapsedRealtime()
                writePcm16Le(windowFile, windowCopy)
                val t1 = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "GS_PCM_SAVED uid=$uid bytes=${windowCopy.size * 2} writeMs=${t1 - t0} path=${windowFile.absolutePath}"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "GS_PCM_SAVE_FAIL uid=$uid err=${t.message}", t)
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

    /**
     * Runs the gunshot detector on PCM16 mono audio.
     *
     * @param audioPcm PCM16 samples (mono)
     * @param sampleRateHz sample rate of audioPcm (e.g., AudioRecord.sampleRate)
     *
     * The model expects exactly 1 second @ 44.1 kHz, float32, shape [1, 44100].
     * If sampleRateHz != 44100, we resample (linear) and then trim/pad to 44100.
     */
    fun runDetectorOnPcm16(audioPcm: ShortArray, sampleRateHz: Int): GunshotDetectionResult? {
        val session = detectorSession

        if (!detectorLoaded || session == null) {
            if (isRunning.get()) Log.e(TAG, "Detector model not loaded")
            return null
        }

        val floatData = pcm16ToFloat(audioPcm)
        val modelInput = forceOneSecond44100(floatData, sampleRateHz)


        return try {
            val score = lock.withLock { runModelScalar(session, modelInput) }
            //Log.i(TAG, "Detector inference score=$score") //first log
            GunshotDetectionResult(
                isGunshot = score >= THRESHOLD,
                confidence = score
            )

        } catch (t: Throwable) {
            Log.e(TAG, "Detector inference error", t)
            null
        }
    }

    // Audio conversion / shaping
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

    // ONNX runtime inference

    /**
     * IMPORTANT: Uses FloatBuffer.wrap(data) to avoid the
     * "Argument type mismatch: FloatArray but OrtAllocator expected" error.
     *
     * Many onnxruntime-android versions accept (env, FloatBuffer, shape) overloads.
     */
    private fun runModelScalar(session: OrtSession, data: FloatArray): Float {
        val shape = longArrayOf(1, data.size.toLong())
        val fb: FloatBuffer = FloatBuffer.wrap(data)
        val inputName = session.inputNames.first() // FIX: Use the model’s real input name instead of INPUT_NAME

        OnnxTensor.createTensor(env, fb, shape).use { input ->
            session.run(mapOf(inputName to input)).use { results ->
                // results[0] is typically an OnnxTensor
                val outTensor = results[0] as OnnxTensor

                // Most compatible way on Android: read from the tensor's FloatBuffer
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

        fun isFull(): Boolean = filled

        fun reset() {
            writePos = 0
            filled = false
        }

        /** Returns newest `capacity` samples in chronological order (oldest->newest). */
        fun snapshot(): ShortArray? {
            if (!filled) return null
            val out = ShortArray(capacity)
            val tail = capacity - writePos
            // copy [writePos .. end)
            System.arraycopy(buf, writePos, out, 0, tail)
            // copy [0 .. writePos)
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
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
        Log.d(TAG, "OnnxService onDestroy()")
    }

    data class GunshotDetectionResult(
        val isGunshot: Boolean,
        val confidence: Float
    )

    companion object {
        private const val TAG = "WearTAK-OnnxService"
        private const val NOTIFICATION_ID = 919  // change per service
        val isRunning: AtomicBoolean = AtomicBoolean(false)

        // in assets/
        //private const val DETECTOR_ASSET = "dart_detector_h2.onnx" //old module
        private const val DETECTOR_ASSET = "dart_bin_20260225.onnx"
        private const val INPUT_NAME = "input"
        private const val OUTPUT_NAME = "result"

        private const val THRESHOLD = 0.5f

        // since onnx  model expects exactly 1 second @ 44.1k
        private const val TARGET_SR = 44_100
        private const val TARGET_SAMPLES = 44_100
        private const val WINDOW_SAMPLES = 44_100           // 1.0s at 44.1k
        private const val HOP_MS = 1000L                     // run inference every 250ms is too much, some longer inferences can take 400ms - 700ms, 1000ms (1 sec) should work.

        private var lastTriggerMs = 0L
        private val TRIGGER_COOLDOWN_MS = 3000L

    }
}