package com.aitech.weartak_civ.cep.metric

/** Compact checkpoint store for scalar V2 observations. */
class BaselineV2ScalarCheckpointStore(
    private val generationStore: BaselineV2GenerationStore
) {
    fun commit(
        generation: Long,
        definition: MetricDefinitionVersion,
        observations: List<QualifiedMetricObservation>
    ) {
        val retained = BaselineV2Retention.retainObservations(observations)
        val history = buildString {
            appendLine("start_ms,end_ms,representative_ms,published_ms,value,context,quality_score")
            retained.forEach { observation ->
                appendLine(
                    listOf(
                        observation.time.observationStartMs,
                        observation.time.observationEndMs,
                        observation.time.representativeTimeMs,
                        observation.time.publishedAtMs,
                        observation.value,
                        observation.context.name,
                        observation.quality.score
                    ).joinToString(",")
                )
            }
        }.toByteArray()
        val state = "metric_id=${definition.metricId}\ndefinition_version=${definition.version}\n"
            .toByteArray()
        generationStore.commit(BaselineV2GenerationPayload(generation, history, state))
    }

    fun restore(
        definition: MetricDefinitionVersion
    ): BaselineV2ScalarCheckpoint? {
        val payload = generationStore.loadCommitted() ?: return null
        val observations = payload.history.toString(Charsets.UTF_8)
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                line.split(',').takeIf { it.size == 7 }?.let { fields ->
                    runCatching {
                        val restoredContext = MetricActivityContext.valueOf(fields[5])
                        QualifiedMetricObservation(
                            metricId = definition.metricId,
                            definition = definition,
                            value = fields[4].toDouble(),
                            time = ObservationTime(
                                observationStartMs = fields[0].toLong(),
                                observationEndMs = fields[1].toLong(),
                                representativeTimeMs = fields[2].toLong(),
                                publishedAtMs = fields[3].toLong()
                            ),
                            context = restoredContext,
                            quality = QualityAssessment(
                                BaselineV2QualityState.VALID,
                                fields[6].toDouble()
                            ),
                            sourceMetadata = if (definition.metricId == BaselineV2MetricDefinitions.SPO2.metricId) {
                                TypedSourceMetadata.Spo2(
                                    motionRejected = false,
                                    family = if (restoredContext == MetricActivityContext.ACTIVE) "walk" else "resting",
                                    altitudeBucket = null
                                )
                            } else if (definition.metricId == BaselineV2MetricDefinitions.PPG.metricId) {
                                TypedSourceMetadata.Ppg(
                                    wavelength = "restored",
                                    contactScore = fields[6].toDouble(),
                                    waveformQuality = fields[6].toDouble()
                                )
                            } else if (definition.metricId == BaselineV2MetricDefinitions.ACTIVE_HR.metricId) {
                                TypedSourceMetadata.HeartRate(
                                    activityState = "RESTORED",
                                    still = false
                                )
                            } else if (definition.metricId == BaselineV2MetricDefinitions.SKIN_TEMPERATURE.metricId) {
                                TypedSourceMetadata.SkinTemperature(
                                    sensorLocation = "peripheral",
                                    ambientCelsius = null
                                )
                            } else {
                                TypedSourceMetadata.Hrv(
                                    validNnCount = 0,
                                    rejectedNnCount = 0,
                                    windowSec = definition.windowSec ?: 0L
                                )
                            }
                        )
                    }.getOrNull()
                }
            }
            .toList()
        return BaselineV2ScalarCheckpoint(payload.generation, observations)
    }

    fun clear() = generationStore.clear()
}

data class BaselineV2ScalarCheckpoint(
    val generation: Long,
    val observations: List<QualifiedMetricObservation>
)
