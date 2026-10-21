package com.aitech.weartak_civ.cep.metric

/** Bounded retention policy for persisted V2 learning history. */
object BaselineV2Retention {
    const val MAX_CHECKPOINT_BYTES: Long = 8L * 1024L * 1024L
    const val MAX_HISTORY_AGE_MS: Long = 30L * 24L * 60L * 60L * 1_000L

    fun retainObservations(
        observations: List<QualifiedMetricObservation>
    ): List<QualifiedMetricObservation> = retainRows(
        rows = observations,
        newestTimestamp = { it.time.representativeTimeMs },
        row = { observation ->
            listOf(
                observation.time.observationStartMs,
                observation.time.observationEndMs,
                observation.time.representativeTimeMs,
                observation.time.publishedAtMs,
                observation.value,
                observation.context.name,
                observation.quality.score
            ).joinToString(",")
        },
        header = "start_ms,end_ms,representative_ms,published_ms,value,context,quality_score"
    )

    fun retainSegments(
        segments: List<RestingHrSegment>
    ): List<RestingHrSegment> = retainRows(
        rows = segments,
        newestTimestamp = { it.representativeTimeMs },
        row = { segment ->
            listOf(
                segment.startMs,
                segment.endMs,
                segment.representativeTimeMs,
                segment.medianBpm,
                segment.sampleCount
            ).joinToString(",")
        },
        header = "start_ms,end_ms,representative_ms,median_bpm,sample_count"
    )

    private fun <T> retainRows(
        rows: List<T>,
        newestTimestamp: (T) -> Long,
        row: (T) -> String,
        header: String
    ): List<T> {
        if (rows.isEmpty()) return emptyList()
        val ordered = rows.sortedBy(newestTimestamp)
        val newest = newestTimestamp(ordered.last())
        val ageCutoff = newest - MAX_HISTORY_AGE_MS
        val ageRetained = ordered.filter { newestTimestamp(it) >= ageCutoff }
        val headerBytes = (header + "\n").toByteArray().size.toLong()
        val encodedRows = ageRetained.map { (row(it) + "\n").toByteArray().size.toLong() }
        if (headerBytes + encodedRows.sum() <= MAX_CHECKPOINT_BYTES) return ageRetained

        var stride = 1
        var selected: List<T>
        do {
            selected = ageRetained.filterIndexed { index, _ ->
                index == 0 || index == ageRetained.lastIndex || index % stride == 0
            }
            stride *= 2
        } while (serializedBytes(selected, row, headerBytes) > MAX_CHECKPOINT_BYTES && selected.size > 2)
        return if (serializedBytes(selected, row, headerBytes) <= MAX_CHECKPOINT_BYTES) {
            selected
        } else {
            emptyList()
        }
    }

    private fun <T> serializedBytes(
        rows: List<T>,
        row: (T) -> String,
        headerBytes: Long
    ): Long = headerBytes + rows.sumOf { (row(it) + "\n").toByteArray().size.toLong() }
}
