package com.aitech.weartak_civ.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.min
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class OnnxService : Service() {

    private val binder = OnnxBinder()

    private lateinit var env: OrtEnvironment
    private var detectorSession: OrtSession? = null
    @Volatile private var detectorLoaded: Boolean = false

    private val lock = ReentrantLock()

    override fun onCreate() {
        super.onCreate()
        env = OrtEnvironment.getEnvironment()
        ensureDetectorLoaded()
    }

    override fun onBind(intent: Intent?): IBinder {
        ensureDetectorLoaded()
        return binder
    }

    inner class OnnxBinder : Binder() {
        fun getService(): OnnxService = this@OnnxService
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
            Log.e(TAG, "Detector model not loaded")
            return null
        }

        val floatData = pcm16ToFloat(audioPcm)
        val modelInput = forceOneSecond44100(floatData, sampleRateHz)

        return try {
            val score = lock.withLock { runModelScalar(session, modelInput) }
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

        OnnxTensor.createTensor(env, fb, shape).use { input ->
            session.run(mapOf(INPUT_NAME to input)).use { results ->
                // results[0] is typically an OnnxTensor
                val outTensor = results[0] as OnnxTensor

                // Most compatible way on Android: read from the tensor's FloatBuffer
                val outBuf = outTensor.floatBuffer
                outBuf.rewind()
                return outBuf.get()
            }
        }
    }

    override fun onDestroy() {
        lock.withLock {
            try { detectorSession?.close() } catch (_: Throwable) {}
            detectorSession = null
            detectorLoaded = false
        }
        super.onDestroy()
    }

    data class GunshotDetectionResult(
        val isGunshot: Boolean,
        val confidence: Float
    )

    companion object {
        private const val TAG = "OnnxService"

        // in assets/
        private const val DETECTOR_ASSET = "dart_detector_h2.onnx"

        private const val INPUT_NAME = "input"
        private const val OUTPUT_NAME = "result"

        private const val THRESHOLD = 0.5f

        // since their  model expects exactly 1 second @ 44.1k
        private const val TARGET_SR = 44_100
        private const val TARGET_SAMPLES = 44_100
    }
}
