package com.aitech.weartak_civ.service.crm

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CrmSessionCsvLogger(
    context: Context,
) : AutoCloseable {

    @Volatile
    private var modelInputWriter: BufferedWriter? = null
    private val appContext = context.applicationContext
    private val writerExecutor = Executors.newSingleThreadExecutor()

    private val sessionActive = AtomicBoolean(false)
    private val rawSampleIndex = AtomicLong(0L)
    private val inferenceIndex = AtomicLong(0L)

    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    private var rawWriter: BufferedWriter? = null

    @Volatile
    private var inferenceWriter: BufferedWriter? = null

    fun startSession(
        channel: String,
        trackerType: String,
        nominalSampleRateHz: Int,
    ): String {
        val sessionId =
            "${FILE_TIMESTAMP_FORMATTER.format(Instant.now())}_${UUID.randomUUID().toString().take(8)}"

        currentSessionId = sessionId
        sessionActive.set(true)
        rawSampleIndex.set(0L)
        inferenceIndex.set(0L)

        writerExecutor.execute {
            closeWritersInternal()

            try {
                val sessionDirectory = File(
                    appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    "crm_sessions/$sessionId",
                ).apply {
                    mkdirs()
                }

                val rawFile = File(sessionDirectory, "raw_ppg.csv")
                val inferenceFile = File(sessionDirectory, "crm_inference.csv")
                val modelInputFile = File(sessionDirectory, "crm_model_input.csv")

                rawWriter = BufferedWriter(FileWriter(rawFile, false)).apply {
                    write(
                        "session_id,sample_index,datetime_utc,wall_time_ms," +
                                "uptime_ms,channel,tracker_type,nominal_sample_rate_hz," +
                                "raw_value,device_worn\n"
                    )
                    flush()
                }

                inferenceWriter = BufferedWriter(FileWriter(inferenceFile, false)).apply {
                    write(
                        "session_id,inference_index,datetime_utc,wall_time_ms," +
                                "inference_uptime_ms,window_start_uptime_ms," +
                                "window_end_uptime_ms,source_sample_count," +
                                "window_duration_ms,max_sample_gap_ms," +
                                "resampled_min,resampled_max,resampled_amplitude," +
                                "model_input_sample_count,quality,quality_method," +
                                "min_coverage_enforced,max_gap_enforced," +
                                "raw_model_output,display_score,inference_latency_ms," +
                                "status,rejection_reason\n"
                    )
                    flush()
                }

                modelInputWriter = BufferedWriter(
                    FileWriter(modelInputFile, false)
                ).apply {
                    write(
                        "session_id,inference_index,model_sample_index," +
                                "target_uptime_ms,resampled_value,scaled_value\n"
                    )
                    flush()
                }

                writeSessionMetadata(
                    directory = sessionDirectory,
                    sessionId = sessionId,
                    channel = channel,
                    trackerType = trackerType,
                    nominalSampleRateHz = nominalSampleRateHz,
                )

                Log.i(
                    TAG,
                    "CRM_CSV_SESSION_START id=$sessionId path=${sessionDirectory.absolutePath}"
                )
            } catch (t: Throwable) {
                sessionActive.set(false)
                Log.e(TAG, "Failed to start CRM CSV session", t)
                closeWritersInternal()
            }
        }

        return sessionId
    }

    fun logRawPpg(
        uptimeMillis: Long,
        rawValue: Float,
        channel: String,
        trackerType: String,
        nominalSampleRateHz: Int,
        deviceWorn: Boolean,
    ) {
        if (!sessionActive.get()) return

        val sessionId = currentSessionId ?: return
        val sampleIndex = rawSampleIndex.incrementAndGet()
        val wallTimeMillis = System.currentTimeMillis()
        val datetimeUtc = Instant.ofEpochMilli(wallTimeMillis).toString()

        writerExecutor.execute {
            try {
                val writer = rawWriter ?: return@execute

                writer.write(
                    "$sessionId," +
                            "$sampleIndex," +
                            "$datetimeUtc," +
                            "$wallTimeMillis," +
                            "$uptimeMillis," +
                            "${csv(channel)}," +
                            "${csv(trackerType)}," +
                            "$nominalSampleRateHz," +
                            "${rawValue.toCsvFloat()}," +
                            "$deviceWorn\n"
                )

                if (sampleIndex % RAW_FLUSH_SAMPLE_COUNT == 0L) {
                    writer.flush()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed writing raw PPG CSV row", t)
            }
        }
    }

    fun logInference(
        inferenceUptimeMillis: Long,
        windowStartUptimeMillis: Long,
        windowEndUptimeMillis: Long,
        sourceSampleCount: Int,
        windowDurationMs: Long,
        maxSampleGapMs: Long?,
        resampledMin: Float?,
        resampledMax: Float?,
        resampledAmplitude: Float?,
        modelInputSampleCount: Int,
        quality: Float?,
        qualityMethod: String,
        minCoverageEnforced: Boolean,
        maxGapEnforced: Boolean,
        rawModelOutput: Float?,
        displayScore: Int?,
        inferenceLatencyMs: Long?,
        status: String,
        rejectionReason: String? = null,
    ): Long {
        if (!sessionActive.get()) return -1L

        val sessionId = currentSessionId ?: return -1L
        val index = inferenceIndex.incrementAndGet()
        val wallTimeMillis = System.currentTimeMillis()
        val datetimeUtc = Instant.ofEpochMilli(wallTimeMillis).toString()

        writerExecutor.execute {
            try {
                val writer = inferenceWriter ?: return@execute

                writer.write(
                    "$sessionId," +
                            "$index," +
                            "$datetimeUtc," +
                            "$wallTimeMillis," +
                            "$inferenceUptimeMillis," +
                            "$windowStartUptimeMillis," +
                            "$windowEndUptimeMillis," +
                            "$sourceSampleCount," +
                            "$windowDurationMs," +
                            "${maxSampleGapMs ?: ""}," +
                            "${resampledMin.toCsvFloat()}," +
                            "${resampledMax.toCsvFloat()}," +
                            "${resampledAmplitude.toCsvFloat()}," +
                            "$modelInputSampleCount," +
                            "${quality.toCsvFloat()}," +
                            "${csv(qualityMethod)}," +
                            "$minCoverageEnforced," +
                            "$maxGapEnforced," +
                            "${rawModelOutput.toCsvFloat()}," +
                            "${displayScore ?: ""}," +
                            "${inferenceLatencyMs ?: ""}," +
                            "${csv(status)}," +
                            "${csv(rejectionReason.orEmpty())}\n"
                )

                writer.flush()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed writing CRM inference CSV row", t)
            }
        }

        return index
    }

    fun logModelInput(
        inferenceIndex: Long,
        windowStartUptimeMs: Long,
        windowEndUptimeMs: Long,
        resampledSamples: FloatArray,
        scaledSamples: FloatArray,
    ) {
        if (!sessionActive.get()) return
        if (resampledSamples.size != scaledSamples.size) {
            Log.e(
                TAG,
                "Cannot log model input: resampled=${resampledSamples.size} " +
                        "scaled=${scaledSamples.size}"
            )
            return
        }

        val sessionId = currentSessionId ?: return

        writerExecutor.execute {
            try {
                val writer = modelInputWriter ?: return@execute

                val sampleCount = scaledSamples.size

                for (index in 0 until sampleCount) {
                    val fraction = if (sampleCount <= 1) {
                        0.0
                    } else {
                        index.toDouble() / (sampleCount - 1).toDouble()
                    }

                    val targetUptimeMs =
                        windowStartUptimeMs +
                                ((windowEndUptimeMs - windowStartUptimeMs) * fraction).toLong()

                    writer.write(
                        "$sessionId," +
                                "$inferenceIndex," +
                                "$index," +
                                "$targetUptimeMs," +
                                "${resampledSamples[index].toCsvFloat()}," +
                                "${scaledSamples[index].toCsvFloat()}\n"
                    )
                }

                writer.flush()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed writing CRM model input CSV", t)
            }
        }
    }

    fun stopSession(reason: String) {
        if (!sessionActive.getAndSet(false)) return

        val sessionId = currentSessionId

        writerExecutor.execute {
            try {
                rawWriter?.flush()
                inferenceWriter?.flush()
                modelInputWriter?.flush()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed flushing CRM CSV session", t)
            } finally {
                closeWritersInternal()
                Log.i(TAG, "CRM_CSV_SESSION_STOP id=$sessionId reason=$reason")
                currentSessionId = null
            }
        }
    }

    override fun close() {
        sessionActive.set(false)

        writerExecutor.execute {
            closeWritersInternal()
        }

        writerExecutor.shutdown()
    }

    private fun writeSessionMetadata(
        directory: File,
        sessionId: String,
        channel: String,
        trackerType: String,
        nominalSampleRateHz: Int,
    ) {
        val metadataFile = File(directory, "session_metadata.csv")

        BufferedWriter(FileWriter(metadataFile, false)).use { writer ->
            writer.write("key,value\n")
            writer.write("session_id,${csv(sessionId)}\n")
            writer.write("started_at_utc,${csv(Instant.now().toString())}\n")
            writer.write("ppg_channel,${csv(channel)}\n")
            writer.write("tracker_type,${csv(trackerType)}\n")
            writer.write("nominal_sample_rate_hz,$nominalSampleRateHz\n")
            writer.write("manufacturer,${csv(Build.MANUFACTURER)}\n")
            writer.write("device_model,${csv(Build.MODEL)}\n")
            writer.write("device_name,${csv(Build.DEVICE)}\n")
            writer.write("android_version,${csv(Build.VERSION.RELEASE)}\n")
            writer.write("android_sdk,${Build.VERSION.SDK_INT}\n")
        }
    }

    private fun closeWritersInternal() {
        try {
            rawWriter?.flush()
            rawWriter?.close()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed closing raw PPG writer", t)
        } finally {
            rawWriter = null
        }

        try {
            inferenceWriter?.flush()
            inferenceWriter?.close()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed closing inference writer", t)
        } finally {
            inferenceWriter = null
        }

        try {
            modelInputWriter?.flush()
            modelInputWriter?.close()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed closing model input writer", t)
        } finally {
            modelInputWriter = null
        }
    }

    private fun csv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun Float?.toCsvFloat(): String =
        if (this != null && isFinite()) {
            String.format(Locale.US, "%.8f", this)
        } else {
            ""
        }

    companion object {
        private const val TAG = "WearTAK-CrmCsvLogger"
        private const val RAW_FLUSH_SAMPLE_COUNT = 100L

        private val FILE_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .withZone(java.time.ZoneOffset.UTC)
    }
}