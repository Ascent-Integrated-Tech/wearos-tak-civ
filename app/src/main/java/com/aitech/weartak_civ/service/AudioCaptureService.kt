package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureService : Service() {

    // --- Worker state ---
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    // --- Audio state ---
    private var audioRecord: AudioRecord? = null
    private var audioInited = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        isRunning.set(true)

        // Promote to foreground (keep consistent with BatteryService)
        startForegroundServiceCompat(
            NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        // Start worker thread
        workerThread = HandlerThread("AudioCapture-Thread").also { t ->
            try {
                t.start()
                workerHandler = Handler(t.looper)
                workerHandler?.post(audioLoop)
            } catch (e: IllegalThreadStateException) {
                Log.e(TAG, "Failed to start audio worker thread", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            // ACTION_START or null both map to normal behavior
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")
        isRunning.set(false)

        // Stop loop first
        workerHandler?.removeCallbacksAndMessages(null)
        workerHandler?.looper?.quit()
        workerThread?.quitSafely()
        workerHandler = null
        workerThread = null

        // Release audio
        try { audioRecord?.stop() } catch (_: Throwable) {}
        audioRecord?.release()
        audioRecord = null
        audioInited = false

        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onBind(intent: Intent): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }

    // --- Main audio loop (runs on worker thread) ---
    private val audioLoop = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return
            try {
                ensureAudioInited()
                // Read a chunk
                val buf = ByteArray(BUFFER_READ_SIZE)
                val n = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (n > 0) {
                    // TODO: "blend what the audio was gonna do":
                    //  - stream/send to socket
                    //  - write to file/pipe
                    //  - compute VU / RMS / features
                    //  - forward frames to your CoT/ Ditto / pipeline
                    // Example placeholder: compute simple RMS
                    val rms = computeRms16le(buf, n)
                    if (rms != null) {
                        // e.g., Log.d(TAG, "RMS=$rms")
                        // TODO: dispatch RMS/frames to your processing path
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "audio loop error: ${t.message}", t)
            }
            // Immediately re-post; AudioRecord.read() is blocking
            workerHandler?.post(this)
        }
    }

    private fun ensureAudioInited() {
        if (audioInited) return

        val sampleRate = SAMPLE_RATE_HZ
        val channelCfg = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelCfg, format)
        require(minBuf != AudioRecord.ERROR && minBuf != AudioRecord.ERROR_BAD_VALUE) {
            "Invalid min buffer size"
        }

        val bufferSize = maxOf(minBuf, MIN_INTERNAL_BUFFER) // safety margin
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelCfg,
            format,
            bufferSize
        )
        val state = audioRecord?.state
        require(state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed (state=$state)" }

        audioRecord?.startRecording()
        audioInited = true
        Log.d(TAG, "Audio initialized: sr=$sampleRate, buf=$bufferSize")
    }

    // Compute RMS for 16-bit little endian PCM chunk
    private fun computeRms16le(bytes: ByteArray, len: Int): Double? {
        if (len < 2) return null
        var i = 0
        var sumSq = 0.0
        var count = 0
        while (i + 1 < len) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val s = if (sample > 32767) sample - 65536 else sample // sign-correct
            sumSq += (s * s).toDouble()
            count++
            i += 2
        }
        return if (count > 0) Math.sqrt(sumSq / count) else null
    }

    companion object {
        private val TAG = "WearTAK-" + AudioCaptureService::class.java.simpleName

        // Foreground notification ID unique to this service
        private const val NOTIFICATION_ID = 201

        // Audio config
        private const val SAMPLE_RATE_HZ = 16000               // adjust if needed
        private const val MIN_INTERNAL_BUFFER = SAMPLE_RATE_HZ  // ~1s safety
        private const val BUFFER_READ_SIZE = 2048               // per read()

        // Control actions (optional)
        const val ACTION_START = "com.aitech.weartak_civ.action.AUDIO_START"
        const val ACTION_STOP  = "com.aitech.weartak_civ.action.AUDIO_STOP"

        // Running flag (per-service)
        val isRunning: AtomicBoolean = AtomicBoolean(false)
    }
}