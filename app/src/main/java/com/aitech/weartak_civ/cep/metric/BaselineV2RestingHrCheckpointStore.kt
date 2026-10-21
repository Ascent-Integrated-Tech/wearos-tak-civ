package com.aitech.weartak_civ.cep.metric

/** Durable V2 resting-HR checkpoint built on the generation commit protocol. */
class BaselineV2RestingHrCheckpointStore(
    private val generationStore: BaselineV2GenerationStore
) {
    fun clear() = generationStore.clear()

    fun commit(
        generation: Long,
        segments: List<RestingHrSegment>,
        snapshot: BaselineV2MetricSnapshot
    ) {
        generationStore.commit(
            BaselineV2GenerationPayload(
                generation = generation,
                history = encodeSegments(BaselineV2Retention.retainSegments(segments)),
                state = encodeSnapshot(snapshot)
            )
        )
    }

    fun restoreSegments(): RestingHrCheckpoint? {
        val payload = generationStore.loadCommitted() ?: return null
        val segments = payload.history.toString(Charsets.UTF_8)
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                line.split(',').takeIf { it.size == 5 }?.let { fields ->
                    runCatching {
                        RestingHrSegment(
                            startMs = fields[0].toLong(),
                            endMs = fields[1].toLong(),
                            representativeTimeMs = fields[2].toLong(),
                            medianBpm = fields[3].toDouble(),
                            sampleCount = fields[4].toInt()
                        )
                    }.getOrNull()
                }
            }
            .toList()
        return RestingHrCheckpoint(payload.generation, segments)
    }

    private fun encodeSegments(segments: List<RestingHrSegment>): ByteArray = buildString {
        appendLine("start_ms,end_ms,representative_ms,median_bpm,sample_count")
        segments.forEach { segment ->
            appendLine(
                listOf(
                    segment.startMs,
                    segment.endMs,
                    segment.representativeTimeMs,
                    segment.medianBpm,
                    segment.sampleCount
                ).joinToString(",")
            )
        }
    }.toByteArray()

    private fun encodeSnapshot(snapshot: BaselineV2MetricSnapshot): ByteArray = buildString {
        appendLine("metric_id=${snapshot.model.metricId}")
        appendLine("definition_version=${snapshot.model.definition.version}")
        appendLine("model_version=${snapshot.modelVersion}")
        appendLine("threshold_formula_version=${snapshot.thresholdFormulaVersion}")
        appendLine("snapshot_generation=${snapshot.generation}")
        appendLine("lifecycle=${snapshot.model.lifecycle}")
    }.toByteArray()
}

data class RestingHrCheckpoint(
    val generation: Long,
    val segments: List<RestingHrSegment>
)
