package com.aitech.weartak_civ.cep.metric

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

interface MetricHistoryStore {
    fun historyVersion(metricId: String): Long = 0L
    fun clearAll() = Unit
    fun appendHotSample(metricId: String, sample: NormalizedMetricSample)
    fun loadHotSamples(metricId: String, sinceMs: Long): List<NormalizedMetricSample>
    fun loadWarmBuckets(metricId: String, sinceMs: Long): List<MetricBucket>
    fun loadColdBuckets(metricId: String, sinceMs: Long): List<MetricBucket>
    fun compactHotToWarm(metricId: String, cutoffMs: Long)
    fun compactWarmToCold(metricId: String, cutoffMs: Long)
    fun evictOlderThan(metricId: String, cutoffMs: Long)
}

class FileBackedMetricHistoryStore(
    private val baseDir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES
) : MetricHistoryStore {
    private val hotSamples: MutableMap<String, MutableList<NormalizedMetricSample>> = ConcurrentHashMap()
    private val bucketCache: MutableMap<Pair<String, Tier>, List<MetricBucket>> = ConcurrentHashMap()
    private val historyVersions: MutableMap<String, Long> = ConcurrentHashMap()
    private val lock = Any()

    init {
        require(maxFileBytes > 0L) { "maxFileBytes must be positive" }
        baseDir.mkdirs()
        migrateLegacyRestingHistory()
    }

    override fun historyVersion(metricId: String): Long = synchronized(lock) {
        historyVersions[metricId] ?: 0L
    }

    override fun clearAll() {
        synchronized(lock) {
            hotSamples.clear()
            bucketCache.clear()
            historyVersions.clear()
            baseDir.listFiles()?.forEach { file ->
                if (file.isFile) Files.deleteIfExists(file.toPath())
            }
        }
    }

    override fun appendHotSample(metricId: String, sample: NormalizedMetricSample) {
        synchronized(lock) {
            if (!hotSamples.containsKey(metricId)) {
                hotSamples[metricId] = readHotSamples(metricId).toMutableList()
            }
            val previousLastTs = hotSamples[metricId].orEmpty().maxOfOrNull { it.tsMs }
            hotSamples.getOrPut(metricId) { mutableListOf() }.add(sample)
            val file = hotFile(metricId)
            val row = hotRow(sample)
            val appendable = previousLastTs == null || sample.tsMs >= previousLastTs
            val rowBytes = row.toByteArray().size + NEWLINE_BYTES
            if (appendable && file.exists() && file.length() + rowBytes <= maxFileBytes) {
                file.appendText("$row\n")
            } else {
                val targetBytes = if (file.length() + rowBytes > maxFileBytes) {
                    (maxFileBytes * 9L / 10L).coerceAtLeast(HOT_HEADER.toByteArray().size.toLong())
                } else {
                    maxFileBytes
                }
                hotSamples[metricId] = boundedRows(
                    items = hotSamples.getValue(metricId).sortedBy { it.tsMs },
                    header = HOT_HEADER,
                    row = ::hotRow,
                    maxBytes = targetBytes
                ).toMutableList()
                writeHotSamples(metricId, hotSamples.getValue(metricId))
            }
            bumpVersion(metricId)
        }
    }

    override fun loadHotSamples(metricId: String, sinceMs: Long): List<NormalizedMetricSample> {
        synchronized(lock) {
            if (!hotSamples.containsKey(metricId)) {
                hotSamples[metricId] = readHotSamples(metricId).toMutableList()
            }
            return hotSamples[metricId].orEmpty().filter { it.tsMs >= sinceMs }.sortedBy { it.tsMs }
        }
    }

    override fun loadWarmBuckets(metricId: String, sinceMs: Long): List<MetricBucket> {
        synchronized(lock) {
            return cachedBuckets(metricId, Tier.WARM).filter { it.bucketStartTs >= sinceMs }.sortedBy { it.bucketStartTs }
        }
    }

    override fun loadColdBuckets(metricId: String, sinceMs: Long): List<MetricBucket> {
        synchronized(lock) {
            return cachedBuckets(metricId, Tier.COLD).filter { it.bucketStartTs >= sinceMs }.sortedBy { it.bucketStartTs }
        }
    }

    override fun compactHotToWarm(metricId: String, cutoffMs: Long) {
        synchronized(lock) {
            val samples = hotSamples[metricId].orEmpty()
            if (samples.isEmpty()) return
            val toCompact = samples.filter { it.tsMs < cutoffMs }
            if (toCompact.isEmpty()) return

            val warmBuckets = cachedBuckets(metricId, Tier.WARM).toMutableList()
            val byHour = toCompact.groupBy { alignToHour(it.tsMs) }
            byHour.forEach { (bucketStart, grouped) ->
                val existingIdx = warmBuckets.indexOfFirst { it.bucketStartTs == bucketStart && it.bucketSizeMs == HOUR_MS }
                val aggregated = grouped.fold(emptyBucket(bucketStart, HOUR_MS)) { acc, sample -> addSample(acc, sample) }
                if (existingIdx >= 0) {
                    warmBuckets[existingIdx] = mergeBuckets(warmBuckets[existingIdx], aggregated)
                } else {
                    warmBuckets.add(aggregated)
                }
            }
            writeBuckets(metricId, Tier.WARM, warmBuckets)
            hotSamples[metricId] = samples.filter { it.tsMs >= cutoffMs }.toMutableList()
            writeHotSamples(metricId, hotSamples.getValue(metricId))
            bumpVersion(metricId)
        }
    }

    override fun compactWarmToCold(metricId: String, cutoffMs: Long) {
        synchronized(lock) {
            val warmBuckets = cachedBuckets(metricId, Tier.WARM)
            if (warmBuckets.isEmpty()) return
            val toCompact = warmBuckets.filter { it.bucketStartTs < cutoffMs }
            if (toCompact.isEmpty()) return

            val coldBuckets = cachedBuckets(metricId, Tier.COLD).toMutableList()
            val byDay = toCompact.groupBy { alignToUtcDay(it.bucketStartTs) }
            byDay.forEach { (bucketStart, grouped) ->
                val existingIdx = coldBuckets.indexOfFirst { it.bucketStartTs == bucketStart && it.bucketSizeMs == DAY_MS }
                val aggregated = MetricBucket(
                    bucketStartTs = bucketStart,
                    bucketSizeMs = DAY_MS,
                    count = grouped.sumOf { it.count },
                    sum = grouped.sumOf { it.sum },
                    sumSquares = grouped.sumOf { it.sumSquares },
                    min = grouped.minOf { it.min },
                    max = grouped.maxOf { it.max },
                    firstTs = grouped.minOf { it.firstTs },
                    lastTs = grouped.maxOf { it.lastTs },
                    sumLog = grouped.mapNotNull { it.sumLog }.takeIf { it.size == grouped.size }?.sum(),
                    sumLogSquares = grouped.mapNotNull { it.sumLogSquares }.takeIf { it.size == grouped.size }?.sum()
                )
                if (existingIdx >= 0) {
                    coldBuckets[existingIdx] = mergeBuckets(coldBuckets[existingIdx], aggregated)
                } else {
                    coldBuckets.add(aggregated)
                }
            }
            writeBuckets(metricId, Tier.COLD, coldBuckets)
            writeBuckets(metricId, Tier.WARM, warmBuckets.filter { it.bucketStartTs >= cutoffMs })
            bumpVersion(metricId)
        }
    }

    override fun evictOlderThan(metricId: String, cutoffMs: Long) {
        synchronized(lock) {
            if (!hotSamples.containsKey(metricId)) {
                hotSamples[metricId] = readHotSamples(metricId).toMutableList()
            }
            val retainedHot = hotSamples.getValue(metricId).filter { it.tsMs >= cutoffMs }
            val warm = cachedBuckets(metricId, Tier.WARM)
            val cold = cachedBuckets(metricId, Tier.COLD)
            val retainedWarm = warm.filter { it.bucketStartTs >= cutoffMs }
            val retainedCold = cold.filter { it.bucketStartTs >= cutoffMs }
            val changed = retainedHot.size != hotSamples.getValue(metricId).size ||
                retainedWarm.size != warm.size || retainedCold.size != cold.size
            if (!changed) return
            if (retainedHot.size != hotSamples.getValue(metricId).size) {
                hotSamples[metricId] = retainedHot.toMutableList()
                writeHotSamples(metricId, retainedHot)
            }
            if (retainedWarm.size != warm.size) writeBuckets(metricId, Tier.WARM, retainedWarm)
            if (retainedCold.size != cold.size) writeBuckets(metricId, Tier.COLD, retainedCold)
            bumpVersion(metricId)
        }
    }

    private fun bumpVersion(metricId: String) {
        historyVersions[metricId] = (historyVersions[metricId] ?: 0L) + 1L
    }

    private fun migrateLegacyRestingHistory() {
        HISTORY_TIERS.forEach { tier ->
            val legacy = File(baseDir, "${LEGACY_RESTING_METRIC_ID}_${tier}.csv")
            val current = File(baseDir, "${RESTING_METRIC_ID}_${tier}.csv")
            if (legacy.exists() && !current.exists()) {
                Files.move(legacy.toPath(), current.toPath())
            }
        }
    }

    private fun tierFile(metricId: String, tier: Tier): File {
        require(metricId.matches(METRIC_ID_PATTERN)) { "Unsafe metric id: $metricId" }
        return File(baseDir, "${metricId}_${tier.name.lowercase()}.csv")
    }

    private fun hotFile(metricId: String): File {
        require(metricId.matches(METRIC_ID_PATTERN)) { "Unsafe metric id: $metricId" }
        return File(baseDir, "${metricId}_hot.csv")
    }

    private fun readHotSamples(metricId: String): List<NormalizedMetricSample> {
        val file = hotFile(metricId)
        if (!file.exists()) return emptyList()
        val lines = file.readLines().filter { it.isNotBlank() }
        val dataLines = versionedDataLines(lines, supportedVersions = setOf("v1")) ?: return emptyList()
        return dataLines.mapNotNull { line ->
            runCatching {
                val p = line.split(",")
                NormalizedMetricSample(value = p[1].toDouble(), tsMs = p[0].toLong())
            }.getOrNull()
        }
    }

    private fun writeHotSamples(metricId: String, samples: List<NormalizedMetricSample>) {
        val bounded = boundedRows(samples.sortedBy { it.tsMs }, HOT_HEADER, ::hotRow, maxFileBytes)
        atomicWrite(hotFile(metricId), serializeRows(HOT_HEADER, bounded, ::hotRow))
    }

    private fun readBuckets(metricId: String, tier: Tier): List<MetricBucket> {
        val file = tierFile(metricId, tier)
        if (!file.exists()) return emptyList()
        val lines = file.readLines().filter { it.isNotBlank() }
        val dataLines = versionedDataLines(lines, supportedVersions = setOf("v1", "v2")) ?: return emptyList()
        return dataLines.mapNotNull { line ->
            runCatching {
                val p = line.split(",")
                MetricBucket(
                    bucketStartTs = p[0].toLong(),
                    bucketSizeMs = p[1].toLong(),
                    count = p[2].toLong(),
                    sum = p[3].toDouble(),
                    sumSquares = p[4].toDouble(),
                    min = p[5].toDouble(),
                    max = p[6].toDouble(),
                    firstTs = p[7].toLong(),
                    lastTs = p[8].toLong(),
                    sumLog = p.getOrNull(9)?.takeIf { it.isNotBlank() }?.toDouble(),
                    sumLogSquares = p.getOrNull(10)?.takeIf { it.isNotBlank() }?.toDouble()
                )
            }.getOrNull()
        }
    }

    private fun cachedBuckets(metricId: String, tier: Tier): List<MetricBucket> {
        val key = metricId to tier
        return bucketCache.getOrPut(key) { readBuckets(metricId, tier) }
    }

    private fun writeBuckets(metricId: String, tier: Tier, buckets: List<MetricBucket>) {
        val file = tierFile(metricId, tier)
        val bounded = boundedRows(
            items = buckets.sortedBy { it.bucketStartTs },
            header = BUCKET_HEADER,
            row = ::bucketRow
        )
        atomicWrite(file, serializeRows(BUCKET_HEADER, bounded, ::bucketRow))
        bucketCache[metricId to tier] = bounded
    }

    private fun <T> boundedRows(
        items: List<T>,
        header: String,
        row: (T) -> String,
        maxBytes: Long = maxFileBytes
    ): List<T> {
        val rows = items.map(row)
        var firstRetained = rows.size
        var totalBytes = header.toByteArray().size
        for (index in rows.indices.reversed()) {
            val rowBytes = rows[index].toByteArray().size + NEWLINE_BYTES
            if (totalBytes + rowBytes > maxBytes) break
            totalBytes += rowBytes
            firstRetained = index
        }
        return items.subList(firstRetained, items.size)
    }

    private fun <T> serializeRows(header: String, items: List<T>, row: (T) -> String): String = buildString {
        appendLine(header)
        items.forEach { appendLine(row(it)) }
    }

    private fun hotRow(sample: NormalizedMetricSample): String = "${sample.tsMs},${sample.value}"

    private fun bucketRow(bucket: MetricBucket): String = listOf(
        bucket.bucketStartTs,
        bucket.bucketSizeMs,
        bucket.count,
        bucket.sum,
        bucket.sumSquares,
        bucket.min,
        bucket.max,
        bucket.firstTs,
        bucket.lastTs,
        bucket.sumLog ?: "",
        bucket.sumLogSquares ?: ""
    ).joinToString(",")

    private fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(content)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun versionedDataLines(lines: List<String>, supportedVersions: Set<String>): List<String>? {
        val header = lines.firstOrNull() ?: return emptyList()
        val version = header.substringBefore(",")
        if (version in supportedVersions) return lines.drop(1)
        if (header.startsWith("v")) return null
        return lines
    }

    private enum class Tier { WARM, COLD }

    companion object {
        private val METRIC_ID_PATTERN = Regex("[A-Za-z0-9_.-]+")
        private val HISTORY_TIERS = listOf("hot", "warm", "cold")
        private const val LEGACY_RESTING_METRIC_ID = "hr.resting_bpm"
        private const val RESTING_METRIC_ID = "hr.resting_bpm.resting"
        private const val NEWLINE_BYTES = 1
        const val DEFAULT_MAX_FILE_BYTES: Long = 5L * 1024L * 1024L
        private const val HOT_HEADER = "v1,ts_ms,value"
        private const val BUCKET_HEADER =
            "v2,bucket_start,bucket_size,count,sum,sum_squares,min,max,first_ts,last_ts,sum_log,sum_log_squares"
        const val HOUR_MS: Long = 60L * 60L * 1000L
        const val DAY_MS: Long = 24L * HOUR_MS

        fun alignToHour(tsMs: Long): Long = (tsMs / HOUR_MS) * HOUR_MS

        // UTC-day alignment by default; deterministic and timezone-independent.
        fun alignToUtcDay(tsMs: Long): Long = (tsMs / DAY_MS) * DAY_MS

        fun emptyBucket(bucketStartTs: Long, bucketSizeMs: Long): MetricBucket =
            MetricBucket(
                bucketStartTs = bucketStartTs,
                bucketSizeMs = bucketSizeMs,
                count = 0L,
                sum = 0.0,
                sumSquares = 0.0,
                min = Double.POSITIVE_INFINITY,
                max = Double.NEGATIVE_INFINITY,
                firstTs = Long.MAX_VALUE,
                lastTs = Long.MIN_VALUE,
                sumLog = 0.0,
                sumLogSquares = 0.0
            )

        fun addSample(bucket: MetricBucket, sample: NormalizedMetricSample): MetricBucket {
            return MetricBucket(
                bucketStartTs = bucket.bucketStartTs,
                bucketSizeMs = bucket.bucketSizeMs,
                count = bucket.count + 1,
                sum = bucket.sum + sample.value,
                sumSquares = bucket.sumSquares + (sample.value * sample.value),
                min = min(bucket.min, sample.value),
                max = max(bucket.max, sample.value),
                firstTs = min(bucket.firstTs, sample.tsMs),
                lastTs = max(bucket.lastTs, sample.tsMs),
                sumLog = bucket.sumLog?.plus(kotlin.math.ln(sample.value)),
                sumLogSquares = bucket.sumLogSquares?.plus(kotlin.math.ln(sample.value).let { it * it })
            )
        }

        fun mergeBuckets(a: MetricBucket, b: MetricBucket): MetricBucket {
            require(a.bucketSizeMs == b.bucketSizeMs) { "Cannot merge buckets with different sizes" }
            return MetricBucket(
                bucketStartTs = min(a.bucketStartTs, b.bucketStartTs),
                bucketSizeMs = a.bucketSizeMs,
                count = a.count + b.count,
                sum = a.sum + b.sum,
                sumSquares = a.sumSquares + b.sumSquares,
                min = min(a.min, b.min),
                max = max(a.max, b.max),
                firstTs = min(a.firstTs, b.firstTs),
                lastTs = max(a.lastTs, b.lastTs),
                sumLog = if (a.sumLog != null && b.sumLog != null) a.sumLog + b.sumLog else null,
                sumLogSquares = if (a.sumLogSquares != null && b.sumLogSquares != null) {
                    a.sumLogSquares + b.sumLogSquares
                } else null
            )
        }
    }
}
