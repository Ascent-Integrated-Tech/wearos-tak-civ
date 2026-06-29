/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class CrmDataRepository(private val dataRepository: DataRepository) {

    val latestCrmInference: StateFlow<CrmInferenceState> = dataRepository.latestCrmInference
    val rawPpgSamples: SharedFlow<CrmRawPpgSample> = dataRepository.crmRawPpgSamples

    fun tryPublish(state: CrmInferenceState): Boolean = dataRepository.tryStoreCrmInference(state)

    suspend fun publish(state: CrmInferenceState) = dataRepository.storeCrmInference(state)

    fun tryPublishRawPpgSample(
        uptimeMillis: Long,
        rawValue: Float,
        sourceAvailable: Boolean,
        deviceWorn: Boolean
    ): Boolean = dataRepository.tryStoreCrmRawPpgSample(
        CrmRawPpgSample(
            uptimeMillis = uptimeMillis,
            rawValue = rawValue,
            sourceAvailable = sourceAvailable,
            deviceWorn = deviceWorn
        )
    )

    fun tryPublishPpgSourceUnavailable(
        uptimeMillis: Long,
        reason: String
    ): Boolean = dataRepository.tryStoreCrmRawPpgSample(
        CrmRawPpgSample(
            uptimeMillis = uptimeMillis,
            rawValue = null,
            sourceAvailable = false,
            deviceWorn = false,
            reason = reason
        )
    )
}

data class CrmRawPpgSample(
    val uptimeMillis: Long,
    val rawValue: Float?,
    val sourceAvailable: Boolean,
    val deviceWorn: Boolean,
    val emittedAtMillis: Long = System.currentTimeMillis(),
    val reason: String? = null
)

enum class CrmInferenceStatus {
    IDLE,
    ACCEPTED,
    REJECTED,
    ERROR
}

data class CrmInferenceState(
    val status: CrmInferenceStatus = CrmInferenceStatus.IDLE,
    val value: Float? = null,
    val updatedAtMillis: Long = 0L,
    val quality: Float? = null,
    val rejectionReason: String? = null,
    val sourceSampleCount: Int = 0,
    val inputWindowDurationMs: Long = 0L,
    val inferenceLatencyMs: Long? = null
) {
    companion object {
        fun idle(updatedAtMillis: Long = System.currentTimeMillis()) = CrmInferenceState(
            status = CrmInferenceStatus.IDLE,
            updatedAtMillis = updatedAtMillis
        )
    }
}
