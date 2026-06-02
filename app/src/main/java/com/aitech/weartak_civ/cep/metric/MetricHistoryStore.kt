package com.aitech.weartak_civ.cep.metric

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

interface MetricHistoryStore {
    fun appendHotSample(metricId: String, sample: NormalizedMetricSample)
    fun loadHotSamples(metricId: String, sinceMs: Long): List<NormalizedMetricSample>
    fun loadWarmBuckets(metricId: String, sinceMs: Long): List<MetricBucket>
    fun loadColdBuckets(metricId: String, sinceMs: Long): List<MetricBucket>
    fun compactHotToWarm(metricId: String, cutoffMs: Long)
    fun compactWarmToCold(metricId: String, cutoffMs: Long)
    fun evictOlderThan(metricId: String, cutoffMs: Long)
}

class FileBackedMetricHistoryStore(
    private val baseDir: File
) : MetricHistoryStore {
    private val hotSamples: MutableMap<String, MutableList<NormalizedMetricSample>> = ConcurrentHashMap()
    private val lock = Any()

    init {
        baseDir.mkdirs()
    }

    override fun appendHotSample(metricId: String, sample: NormalizedMetricSample) {
        synchronized(lock) {
            hotSamples.getOrPut(metricId) { mutableListOf() }.add(sample)
        }
    }

    override fun loadHotSamples(metricId: String, sinceMs: Long): List<NormalizedMetricSample> {
        synchronized(lock) {
            return hotSamples[metricId].orEmpty().filter { it.tsMs >= sinceMs }.sortedBy { it.tsMs }
        }
    }

    override fun loadWarmBuckets(metricId: String, sinceMs: Long): List<MetricBucket> {
        synchronized(lock) {
            return readBuckets(metricId, Tier.WARM).filter { it.bucketStartTs >= sinceMs }.sortedBy { it.bucketStartTs }
        }
    }

    override fun loadColdBuckets(metricId: String, sinceMs: Long): List<MetricBucket> {
        synchronized(lock) {
            return readBuckets(metricId, Tier.COLD).filter { it.bucketStartTs >= sinceMs }.sortedBy { it.bucketStartTs }
        }
    }

    override fun compactHotToWarm(metricId: String, cutoffMs: Long) {
        synchronized(lock) {
            val samples = hotSamples[metricId].orEmpty()
            if (samples.isEmpty()) return
            val toCompact = samples.filter { it.tsMs < cutoffMs }
            if (toCompact.isEmpty()) return

            val warmBuckets = readBuckets(metricId, Tier.WARM).toMutableList()
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
        }
    }

    override fun compactWarmToCold(metricId: String, cutoffMs: Long) {
        synchronized(lock) {
            val warmBuckets = readBuckets(metricId, Tier.WARM)
            if (warmBuckets.isEmpty()) return
            val toCompact = warmBuckets.filter { it.bucketStartTs < cutoffMs }
            if (toCompact.isEmpty()) return

            val coldBuckets = readBuckets(metricId, Tier.COLD).toMutableList()
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
                    lastTs = grouped.maxOf { it.lastTs }
                )
                if (existingIdx >= 0) {
                    coldBuckets[existingIdx] = mergeBuckets(coldBuckets[existingIdx], aggregated)
                } else {
                    coldBuckets.add(aggregated)
                }
            }
            writeBuckets(metricId, Tier.COLD, coldBuckets)
            writeBuckets(metricId, Tier.WARM, warmBuckets.filter { it.bucketStartTs >= cutoffMs })
        }
    }

    override fun evictOlderThan(metricId: String, cutoffMs: Long) {
        synchronized(lock) {
            hotSamples[metricId] = hotSamples[metricId].orEmpty().filter { it.tsMs >= cutoffMs }.toMutableList()
            writeBuckets(metricId, Tier.WARM, readBuckets(metricId, Tier.WARM).filter { it.bucketStartTs >= cutoffMs })
            writeBuckets(metricId, Tier.COLD, readBuckets(metricId, Tier.COLD).filter { it.bucketStartTs >= cutoffMs })
        }
    }

    private fun tierFile(metricId: String, tier: Tier): File = File(baseDir, "${metricId}_${tier.name.lowercase()}.csv")

    private fun readBuckets(metricId: String, tier: Tier): List<MetricBucket> {
        val file = tierFile(metricId, tier)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .map { line ->
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
                    lastTs = p[8].toLong()
                )
            }
    }

    private fun writeBuckets(metricId: String, tier: Tier, buckets: List<MetricBucket>) {
        val file = tierFile(metricId, tier)
        file.parentFile?.mkdirs()
        file.writeText(
            buckets.sortedBy { it.bucketStartTs }.joinToString("\n") { b ->
                listOf(
                    b.bucketStartTs,
                    b.bucketSizeMs,
                    b.count,
                    b.sum,
                    b.sumSquares,
                    b.min,
                    b.max,
                    b.firstTs,
                    b.lastTs
                ).joinToString(",")
            }
        )
    }

    private enum class Tier { WARM, COLD }

    companion object {
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
                lastTs = Long.MIN_VALUE
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
                lastTs = max(bucket.lastTs, sample.tsMs)
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
                lastTs = max(a.lastTs, b.lastTs)
            )
        }
    }
}
