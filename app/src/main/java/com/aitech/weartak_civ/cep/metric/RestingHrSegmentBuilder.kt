package com.aitech.weartak_civ.cep.metric

/** A raw HR observation presented to the V2 resting-HR qualifier. */
data class RestingHrInput(
    val bpm: Double,
    val timestampMs: Long,
    val awake: Boolean,
    val onWrist: Boolean,
    val still: Boolean,
    val quality: QualityAssessment
)

/** One independent resting interval contributed to the personal model. */
data class RestingHrSegment(
    val startMs: Long,
    val endMs: Long,
    val representativeTimeMs: Long,
    val medianBpm: Double,
    val sampleCount: Int
)

class RestingHrSegmentBuilder(
    private val minimumDwellMs: Long = 60_000L,
    private val maximumSegmentMs: Long = 600_000L,
    private val minimumBpm: Double = 20.0,
    private val maximumBpm: Double = 240.0
) {
    private var candidateStartMs: Long? = null
    private var activeStartMs: Long? = null
    private var lastTimestampMs: Long? = null
    private var lastEligibleTimestampMs: Long? = null
    private val samples = mutableListOf<Double>()

    init {
        require(minimumDwellMs >= 0L)
        require(maximumSegmentMs >= minimumDwellMs)
        require(minimumBpm < maximumBpm)
    }

    fun ingest(input: RestingHrInput): List<RestingHrSegment> {
        if (lastTimestampMs != null && input.timestampMs <= lastTimestampMs!!) return emptyList()
        lastTimestampMs = input.timestampMs
        if (!isEligible(input)) return finishSegment()
        lastEligibleTimestampMs = input.timestampMs

        val candidateStart = candidateStartMs
        if (candidateStart == null) {
            candidateStartMs = input.timestampMs
            samples += input.bpm
            return emptyList()
        }

        val activeStart = activeStartMs
        if (activeStart == null) {
            if (input.timestampMs - candidateStart < minimumDwellMs) {
                samples += input.bpm
                return emptyList()
            }
            activeStartMs = candidateStart
        } else if (input.timestampMs - activeStart >= maximumSegmentMs) {
            val completed = finishSegment()
            candidateStartMs = input.timestampMs
            return completed
        }

        samples += input.bpm
        return emptyList()
    }

    fun finish(): List<RestingHrSegment> = finishSegment()

    fun clear() {
        reset()
        lastTimestampMs = null
    }

    private fun isEligible(input: RestingHrInput): Boolean =
        input.awake &&
            input.onWrist &&
            input.still &&
            input.quality.state == BaselineV2QualityState.VALID &&
            input.quality.score > 0.0 &&
            input.bpm in minimumBpm..maximumBpm

    private fun finishSegment(): List<RestingHrSegment> {
        val activeStart = activeStartMs
        if (activeStart == null || samples.isEmpty()) {
            reset()
            return emptyList()
        }
        val endMs = lastEligibleTimestampMs ?: activeStart
        val segment = RestingHrSegment(
            startMs = activeStart,
            endMs = endMs,
            representativeTimeMs = activeStart + (endMs - activeStart) / 2L,
            medianBpm = RobustStatistics.median(samples)!!,
            sampleCount = samples.size
        )
        reset()
        return listOf(segment)
    }

    private fun reset() {
        candidateStartMs = null
        activeStartMs = null
        samples.clear()
        lastEligibleTimestampMs = null
    }
}
