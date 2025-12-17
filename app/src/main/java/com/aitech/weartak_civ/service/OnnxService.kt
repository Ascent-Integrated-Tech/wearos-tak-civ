package com.aitech.weartak_civ.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

class OnnxService : Service() {

    private val binder = OnnxBinder()

    private lateinit var env: OrtEnvironment
    private var detectorSession: OrtSession? = null
    private var detectorLoaded = false

    override fun onCreate() {
        super.onCreate()
        env = OrtEnvironment.getEnvironment()
    }

    override fun onBind(intent: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    inner class OnnxBinder : Binder() {
        fun getService(): OnnxService = this@OnnxService
    }

    fun loadDetectorModel(modelPath: String) {
        val opts = OrtSession.SessionOptions()
        detectorSession?.close()
        detectorSession = env.createSession(modelPath, opts)
        detectorLoaded = true

        Log.d(TAG, "Detector loaded: inputs=${detectorSession!!.inputInfo.keys}")
    }

    fun runDetectorOnPcm16(audioPcm: ShortArray): GunshotDetectionResult? {
        if (!detectorLoaded || detectorSession == null) {
            Log.e(TAG, "Detector model not loaded")
            return null
        }

        val floatData = pcm16ToFloat(audioPcm)
        val inputShape = longArrayOf(1, floatData.size.toLong())

        return try {
            val output = runModel(detectorSession!!, floatData, inputShape)
            val score = output[0]
            val isGunshot = score >= 0.5f
            GunshotDetectionResult(isGunshot, score)
        } catch (e: Exception) {
            Log.e(TAG, "Detector inference error", e)
            null
        }
    }

    private fun pcm16ToFloat(pcm: ShortArray): FloatArray {
        return FloatArray(pcm.size) { i -> pcm[i] / 32768f }
    }

    private fun runModel(
        session: OrtSession,
        data: FloatArray,
        shape: LongArray
    ): FloatArray {
        OnnxTensor.createTensor(env, data, shape).use { input ->
            val inputName = session.inputNames.first()
            session.run(mapOf(inputName to input)).use { result ->
                val arr = result[0].value as Array<FloatArray>
                return arr[0]
            }
        }
    }

    data class GunshotDetectionResult(
        val isGunshot: Boolean,
        val confidence: Float
    )

    companion object {
        private const val TAG = "OnnxService"
    }
}
