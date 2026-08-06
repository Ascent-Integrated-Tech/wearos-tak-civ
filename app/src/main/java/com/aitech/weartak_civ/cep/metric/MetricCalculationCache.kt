package com.aitech.weartak_civ.cep.metric

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class CachedMetricCore(
    val calculatedAtMs: Long,
    val samplesAtCalculation: Long,
    val core: MetricBaselineCore
)

interface MetricCalculationCacheStore {
    fun load(metricId: String, configHash: String): CachedMetricCore?
    fun save(metricId: String, configHash: String, cached: CachedMetricCore)
    fun clearAll() = Unit
}

class FileBackedMetricCalculationCacheStore(
    private val baseDir: File
) : MetricCalculationCacheStore {
    override fun load(metricId: String, configHash: String): CachedMetricCore? {
        val file = fileFor(metricId)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            if (json.optInt("schema_version", -1) != SCHEMA_VERSION) return null
            if (json.optInt("algorithm_version", -1) != ALGORITHM_VERSION) return null
            if (json.optString("config_hash") != configHash) return null
            CachedMetricCore(
                calculatedAtMs = json.getLong("calculated_at_ms"),
                samplesAtCalculation = json.getLong("samples_at_calculation"),
                core = coreFromJson(json.getJSONObject("core"))
            )
        }.getOrNull()
    }

    override fun save(metricId: String, configHash: String, cached: CachedMetricCore) {
        baseDir.mkdirs()
        val json = JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("algorithm_version", ALGORITHM_VERSION)
            put("metric_id", metricId)
            put("config_hash", configHash)
            put("calculated_at_ms", cached.calculatedAtMs)
            put("samples_at_calculation", cached.samplesAtCalculation)
            put("core", coreToJson(cached.core))
        }
        val file = fileFor(metricId)
        val temp = File(baseDir, "${file.name}.tmp")
        temp.writeText(json.toString())
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun clearAll() {
        baseDir.listFiles()?.forEach { file ->
            if (file.isFile) Files.deleteIfExists(file.toPath())
        }
    }

    private fun fileFor(metricId: String): File {
        require(metricId.matches(METRIC_ID_PATTERN)) { "Unsafe metric id: $metricId" }
        return File(baseDir, "$metricId.json")
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val ALGORITHM_VERSION = 1
        private val METRIC_ID_PATTERN = Regex("[A-Za-z0-9_.-]+")

        fun configHash(spec: MetricSpec): String {
            val value = listOf(
                spec.id,
                spec.processor,
                spec.baseline,
                spec.trend
            ).toString()
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun coreToJson(core: MetricBaselineCore): JSONObject = JSONObject().apply {
            putNullable("baseline", core.baseline)
            putNullable("short_term", core.shortTerm)
            putNullable("long_term", core.longTerm)
            putNullable("deviation", core.deviation)
            putNullable("percent_deviation", core.percentDeviation)
            putNullable("low_threshold", core.lowThreshold)
            putNullable("low_threshold_warn", core.lowThresholdWarn)
            putNullable("high_threshold", core.highThreshold)
            putNullable("high_threshold_warn", core.highThresholdWarn)
            put("confidence_base", core.confidenceBase)
            put("sample_count", core.sampleCount)
            put("coverage_seconds", core.coverageSeconds)
            put("covered_seconds", core.coveredSeconds)
            put("active_days", core.activeDays)
            putNullable("latest_value", core.latestValue)
            putNullable("latest_ts_ms", core.latestTsMs)
        }

        private fun coreFromJson(json: JSONObject): MetricBaselineCore = MetricBaselineCore(
            baseline = json.optDoubleOrNull("baseline"),
            shortTerm = json.optDoubleOrNull("short_term"),
            longTerm = json.optDoubleOrNull("long_term"),
            deviation = json.optDoubleOrNull("deviation"),
            percentDeviation = json.optDoubleOrNull("percent_deviation"),
            lowThreshold = json.optDoubleOrNull("low_threshold"),
            lowThresholdWarn = json.optDoubleOrNull("low_threshold_warn"),
            highThreshold = json.optDoubleOrNull("high_threshold"),
            highThresholdWarn = json.optDoubleOrNull("high_threshold_warn"),
            confidenceBase = json.getDouble("confidence_base"),
            sampleCount = json.getLong("sample_count"),
            coverageSeconds = json.getLong("coverage_seconds"),
            coveredSeconds = json.getLong("covered_seconds"),
            activeDays = json.getInt("active_days"),
            latestValue = json.optDoubleOrNull("latest_value"),
            latestTsMs = json.optLongOrNull("latest_ts_ms")
        )

        private fun JSONObject.putNullable(key: String, value: Any?) {
            put(key, value ?: JSONObject.NULL)
        }

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (isNull(key)) null else optDouble(key)

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (isNull(key)) null else optLong(key)
    }
}
