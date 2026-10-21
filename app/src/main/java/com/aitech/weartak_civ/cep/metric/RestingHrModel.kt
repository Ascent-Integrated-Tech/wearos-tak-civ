package com.aitech.weartak_civ.cep.metric

/** Explicit policy inputs; production values must be approved separately. */
data class RestingHrModelConfig(
    val minimumScaleBpm: Double = 2.0,
    val expectedLowerMultiplier: Double,
    val expectedUpperMultiplier: Double,
    val lowWarningMultiplier: Double,
    val lowAlertMultiplier: Double,
    val highWarningMultiplier: Double,
    val highAlertMultiplier: Double
) {
    init {
        require(minimumScaleBpm > 0.0)
        require(expectedLowerMultiplier > 0.0 && expectedUpperMultiplier > 0.0)
        require(lowWarningMultiplier > expectedLowerMultiplier)
        require(lowAlertMultiplier > lowWarningMultiplier)
        require(highWarningMultiplier > expectedUpperMultiplier)
        require(highAlertMultiplier > highWarningMultiplier)
    }
}

data class RestingHrDistributionEstimate(
    val centreBpm: Double,
    val lowerScaleBpm: Double,
    val upperScaleBpm: Double,
    val lowerExpectedBoundBpm: Double,
    val upperExpectedBoundBpm: Double,
    val scaleMethod: String,
    val sampleCount: Int
)

class RestingHrModel(private val config: RestingHrModelConfig) {
    fun estimate(segmentMediansBpm: List<Double>): RestingHrDistributionEstimate? {
        val scale = RobustStatistics.robustScale(segmentMediansBpm, config.minimumScaleBpm)
        val centre = scale.centre ?: return null
        val spread = scale.scale ?: return null
        return RestingHrDistributionEstimate(
            centreBpm = centre,
            lowerScaleBpm = spread,
            upperScaleBpm = spread,
            lowerExpectedBoundBpm = centre - config.expectedLowerMultiplier * spread,
            upperExpectedBoundBpm = centre + config.expectedUpperMultiplier * spread,
            scaleMethod = scale.method,
            sampleCount = segmentMediansBpm.size
        )
    }

    fun candidateBoundaries(
        estimate: RestingHrDistributionEstimate,
        absoluteLowEmergencyBpm: Double,
        absoluteHighEmergencyBpm: Double
    ): InformationalCandidateBoundaries {
        val lowWarning = estimate.centreBpm - config.lowWarningMultiplier * estimate.lowerScaleBpm
        val lowAlert = estimate.centreBpm - config.lowAlertMultiplier * estimate.lowerScaleBpm
        val highWarning = estimate.centreBpm + config.highWarningMultiplier * estimate.upperScaleBpm
        val highAlert = estimate.centreBpm + config.highAlertMultiplier * estimate.upperScaleBpm
        require(
            absoluteLowEmergencyBpm < lowAlert &&
                lowAlert < lowWarning &&
                lowWarning < estimate.centreBpm &&
                estimate.centreBpm < highWarning &&
                highWarning < highAlert &&
                highAlert < absoluteHighEmergencyBpm
        ) {
            "Resting-HR candidate boundary ordering is invalid"
        }
        return InformationalCandidateBoundaries(
            lowWarning = lowWarning,
            lowAlert = lowAlert,
            highWarning = highWarning,
            highAlert = highAlert,
            source = "MODEL_ONLY"
        )
    }
}
