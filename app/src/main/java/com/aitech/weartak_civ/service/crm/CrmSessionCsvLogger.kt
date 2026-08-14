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

data class CrmSessionMetadata(
    val modelParameters: CrmModelParameters,
    val sessionId: String,
    val startedAtUtc: String,
    val wearTakUid: String,
    val callsignAtSessionStart: String,
    val inferenceModel: String,
    val ppgChannel: String,
    val trackerType: String,
    val nominalSampleRateHz: Int,
    val appVersionName: String,
    val appVersionCode: Int,
    val gitBranch: String,
    val gitCommit: String,
    val buildType: String,
    val manufacturer: String,
    val deviceModel: String,
    val deviceName: String,
    val androidVersion: String,
    val androidSdk: Int,
)

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
        wearTakUid: String,
        callsignAtSessionStart: String,
        appVersionName: String,
        appVersionCode: Int,
        gitBranch: String,
        gitCommit: String,
        buildType: String,
    ): String {
        val startedAt = Instant.now()

        val sessionId =
            "${FILE_TIMESTAMP_FORMATTER.format(startedAt)}_" +
                    UUID.randomUUID().toString().take(8)

        val parameters = CrmPpgPreprocessor.MODEL_PARAMETERS

        val metadata = CrmSessionMetadata(
            sessionId = sessionId,
            startedAtUtc = startedAt.toString(),
            wearTakUid = wearTakUid,
            callsignAtSessionStart = callsignAtSessionStart,
            inferenceModel = parameters.modelAssetName,
            modelParameters = parameters,
            ppgChannel = channel,
            trackerType = trackerType,
            nominalSampleRateHz = nominalSampleRateHz,
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            gitBranch = gitBranch,
            gitCommit = gitCommit,
            buildType = buildType,
            manufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            deviceName = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE,
            androidSdk = Build.VERSION.SDK_INT,
        )

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
                    metadata = metadata,
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
        metadata: CrmSessionMetadata,
    ) {
        val metadataFile = File(directory, "session_metadata.csv")
        val parameters = metadata.modelParameters

        val fields = listOf(
            "session_id" to metadata.sessionId,
            "started_at_utc" to metadata.startedAtUtc,
            "wear_tak_uid" to metadata.wearTakUid,
            "callsign_at_session_start" to metadata.callsignAtSessionStart,

            "inference_model" to parameters.modelAssetName,

            // Model input construction.
            "target_sample_rate_hz" to parameters.targetSampleRateHz,
            "target_samples_per_window" to parameters.targetSamplesPerWindow,
            "target_window_duration_ms" to parameters.targetWindowDurationMs,
            "inference_attempt_period_ms" to parameters.inferenceAttemptPeriodMs,
            "resampling_method" to parameters.resamplingMethod,
            "normalization_method" to parameters.normalizationMethod,
            "normalization_output_min" to parameters.normalizationOutputMin,
            "normalization_output_max" to parameters.normalizationOutputMax,

            // Window acceptance requirements.
            "minimum_source_sample_count" to parameters.minimumSourceSampleCount,
            "minimum_source_coverage_ms" to parameters.minimumSourceCoverageMs,
            "maximum_source_sample_gap_ms" to parameters.maximumSourceSampleGapMs,
            "minimum_resampled_peak_to_peak_amplitude" to parameters.minimumResampledPeakToPeakAmplitude,

            // Current quality calculation.
            "quality_method" to parameters.qualityMethod,
            "quality_soft_cap" to parameters.qualitySoftCap,

            "ppg_channel" to metadata.ppgChannel,
            "tracker_type" to metadata.trackerType,
            "nominal_sample_rate_hz" to metadata.nominalSampleRateHz,

            "app_version_name" to metadata.appVersionName,
            "app_version_code" to metadata.appVersionCode,
            "git_branch" to metadata.gitBranch,
            "git_commit" to metadata.gitCommit,
            "build_type" to metadata.buildType,
            "manufacturer" to metadata.manufacturer,
            "device_model" to metadata.deviceModel,
            "device_name" to metadata.deviceName,
            "android_version" to metadata.androidVersion,
            "android_sdk" to metadata.androidSdk,
        )

        BufferedWriter(FileWriter(metadataFile, false)).use { writer ->
            writer.write("key,value\n")

            fields.forEach { (key, value) ->
                writer.write("$key,${csv(value.toString())}\n")
            }
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