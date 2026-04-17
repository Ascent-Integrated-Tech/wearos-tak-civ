package com.aitech.weartak_civ.service.audio

import android.content.Context
import android.os.Build
import android.os.Process
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.DataRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import kotlin.math.round

class AudioDetectionMetricsLogger(
    context: Context,
    private val spec: AudioDetectionSpec
) {
    private val appContext = context.applicationContext

    private val repo by lazy {
        try {
            (appContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(appContext)
        }
    }

    suspend fun logInferenceWindow(
        chunk: AudioCaptureRepository.AudioPcmChunk,
        inferStartElapsedMs: Long,
        inferEndElapsedMs: Long,
        inference: AudioDetectionInferenceResult,
        audioSource: String,
        triggerUid: String?,
        triggerPcmPath: String?,
        triggered: Boolean,
        cooldownSuppressed: Boolean,
        cpuStartMs: Long,
        cpuEndMs: Long
    ) {
        val dir = File(appContext.filesDir, spec.debugDirName).apply { mkdirs() }
        val logFile = File(dir, "${spec.detectorKey}_inference_metrics.jsonl")
        val system = repo.snapshotSystem()

        val inferDurationMs = (inferEndElapsedMs - inferStartElapsedMs).coerceAtLeast(0L)
        val chunkToInferLatencyMs = (inferStartElapsedMs - chunk.timestampElMs).coerceAtLeast(0L)
        val cpuDeltaMs = (cpuEndMs - cpuStartMs).coerceAtLeast(0L)
        val cpuUtilizationPct =
            if (inferDurationMs > 0L) {
                round((cpuDeltaMs.toDouble() * 10000.0) / inferDurationMs) / 100.0
            } else {
                null
            }

        val obj = JSONObject().apply {
            put("recordedAtMs", System.currentTimeMillis())
            put("detectorKey", spec.detectorKey)
            put("modelAssetName", spec.assetName)
            put("threshold", spec.threshold.toDouble())
            put("targetSampleRateHz", spec.targetSr)
            put("targetSamples", spec.targetSamples)
            put("windowSamples", spec.windowSamples)
            put("hopMs", spec.hopMs)
            put("triggerCooldownMs", spec.triggerCooldownMs)

            put("audioSessionId", chunk.sessionId)
            put("audioSeq", chunk.seq)
            put("audioChunkTimestampMs", chunk.timestampMs)
            put("audioChunkElapsedMs", chunk.timestampElMs)
            put("sampleRateHz", chunk.sampleRateHz)
            put("channels", chunk.channels)
            put("chunkSampleCount", chunk.samples.size)
            put("audioSource", audioSource)

            put("inferStartElapsedMs", inferStartElapsedMs)
            put("inferEndElapsedMs", inferEndElapsedMs)
            put("inferDurationMs", inferDurationMs)
            put("chunkToInferLatencyMs", chunkToInferLatencyMs)

            put("inferenceSucceeded", inference.inferenceSucceeded)
            putNullable("errorMessage", inference.errorMessage)
            putNullable("confidence", inference.confidence?.let { round(it * 10000f) / 10000f })
            put("detected", inference.detected)
            put("triggered", triggered)
            put("cooldownSuppressed", cooldownSuppressed)
            putNullable("triggerUid", triggerUid)
            putNullable("triggerPcmPath", triggerPcmPath)

            put("processCpuStartMs", cpuStartMs)
            put("processCpuEndMs", cpuEndMs)
            put("processCpuDeltaMs", cpuDeltaMs)
            putNullable("processCpuUtilizationPct", cpuUtilizationPct)
            putNullable("cpuSamplesPct", null)
            putNullable("cpuPeakPct", null)

            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("wearTakVersion", system.wearTakVersion)
            put("callsign", system.callsign)
            put("deviceId", system.deviceId)
            putNullable("batteryPercent", system.batteryPercent)
            put("charging", system.systemStates.charging)
            put("dockType", system.systemStates.dockType.name)
            put("isWorn", system.systemStates.isWorn)
            put("desiredReportIntervalSecs", system.systemStates.desiredReportInterval)
            put("actualReportIntervalSecs", system.systemStates.actualReportInterval)
        }

        appendJsonLine(logFile, obj)
    }

    private fun appendJsonLine(file: File, obj: JSONObject) {
        FileWriter(file, true).use { writer ->
            writer.write(obj.toString())
            writer.write("\n")
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        when (value) {
            null -> put(key, JSONObject.NULL)
            is FloatArray -> put(key, JSONArray(value.map { it.toDouble() }))
            is DoubleArray -> put(key, JSONArray(value.toList()))
            else -> put(key, value)
        }
    }

    companion object {
        fun currentProcessCpuTimeMs(): Long = Process.getElapsedCpuTime()
    }
}
