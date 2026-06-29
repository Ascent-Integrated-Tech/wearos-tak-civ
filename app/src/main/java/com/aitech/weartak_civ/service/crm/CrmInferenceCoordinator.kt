/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service.crm

import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.data.CrmDataRepository
import com.aitech.weartak_civ.data.CrmInferenceState
import com.aitech.weartak_civ.data.CrmInferenceStatus

class CrmInferenceCoordinator(
    private val crmRepo: CrmDataRepository,
    private val runnerFactory: () -> CrmModelRunner,
    private val preprocessor: CrmPpgPreprocessor = CrmPpgPreprocessor(),
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
    private val uptimeMillis: () -> Long = { SystemClock.uptimeMillis() }
) : AutoCloseable {
    private val lock = Any()
    private var runner: CrmModelRunner? = null
    private var lastInferenceUptimeMs = 0L
    private var lastRejectedLogUptimeMs = 0L
    private var lastRejectedReason: String? = null

    fun warmUpModel() {
        try {
            getRunner()
            Log.i(TAG, "CRM_MODEL_READY")
        } catch (t: Throwable) {
            Log.e(TAG, "CRM_MODEL_LOAD_FAIL", t)
            crmRepo.tryPublish(
                CrmInferenceState(
                    status = CrmInferenceStatus.ERROR,
                    updatedAtMillis = wallClockMillis(),
                    rejectionReason = t.message ?: t.javaClass.simpleName
                )
            )
        }
    }

    fun onRawPpgSample(sampleUptimeMillis: Long, rawValue: Float, sourceAvailable: Boolean) {
        val result = synchronized(lock) {
            preprocessor.addSample(sampleUptimeMillis, rawValue)
            val now = uptimeMillis()
            if (now - lastInferenceUptimeMs < INFERENCE_PERIOD_MS) {
                return
            }
            lastInferenceUptimeMs = now
            if (!sourceAvailable) {
                return@synchronized CrmWindowResult.Rejected("ppg_unavailable", 0, 0L)
            }
            preprocessor.buildWindow()
        }
        publish(result)
    }

    fun reset(reason: String) {
        synchronized(lock) {
            preprocessor.clear()
            lastInferenceUptimeMs = 0L
        }
        crmRepo.tryPublish(
            CrmInferenceState(
                status = CrmInferenceStatus.REJECTED,
                updatedAtMillis = wallClockMillis(),
                rejectionReason = reason
            )
        )
    }

    private fun publish(result: CrmWindowResult) {
        when (result) {
            is CrmWindowResult.Accepted -> runInference(result)
            is CrmWindowResult.Rejected -> {
                maybeLogRejectedWindow(result)
                crmRepo.tryPublish(
                    CrmInferenceState(
                        status = CrmInferenceStatus.REJECTED,
                        updatedAtMillis = wallClockMillis(),
                        rejectionReason = result.reason,
                        sourceSampleCount = result.sourceSampleCount,
                        inputWindowDurationMs = result.windowDurationMs
                    )
                )
            }
        }
    }

    private fun runInference(window: CrmWindowResult.Accepted) {
        val start = SystemClock.elapsedRealtime()
        try {
            val value = getRunner().run(window.scaledSamples)
            val end = SystemClock.elapsedRealtime()
            crmRepo.tryPublish(
                CrmInferenceState(
                    status = CrmInferenceStatus.ACCEPTED,
                    value = value,
                    updatedAtMillis = wallClockMillis(),
                    quality = window.quality,
                    sourceSampleCount = window.sourceSampleCount,
                    inputWindowDurationMs = window.windowDurationMs,
                    inferenceLatencyMs = end - start
                )
            )
            Log.d(TAG, "CRM_INFERENCE value=${"%.4f".format(value)} samples=${window.sourceSampleCount} latencyMs=${end - start}")
        } catch (t: Throwable) {
            Log.e(TAG, "CRM inference failed", t)
            crmRepo.tryPublish(
                CrmInferenceState(
                    status = CrmInferenceStatus.ERROR,
                    updatedAtMillis = wallClockMillis(),
                    rejectionReason = t.message ?: t.javaClass.simpleName,
                    sourceSampleCount = window.sourceSampleCount,
                    inputWindowDurationMs = window.windowDurationMs
                )
            )
        }
    }

    private fun maybeLogRejectedWindow(result: CrmWindowResult.Rejected) {
        val now = uptimeMillis()
        if (result.reason == lastRejectedReason && now - lastRejectedLogUptimeMs < REJECTION_LOG_PERIOD_MS) return
        lastRejectedReason = result.reason
        lastRejectedLogUptimeMs = now
        Log.d(
            TAG,
            "CRM_WINDOW_REJECT reason=${result.reason} samples=${result.sourceSampleCount} durationMs=${result.windowDurationMs}"
        )
    }

    private fun getRunner(): CrmModelRunner = synchronized(lock) {
        runner ?: runnerFactory().also { runner = it }
    }

    override fun close() {
        synchronized(lock) {
            runner?.close()
            runner = null
            preprocessor.clear()
        }
    }

    companion object {
        private const val TAG = "WearTAK-CrmInference"
        private const val INFERENCE_PERIOD_MS = 1_000L
        private const val REJECTION_LOG_PERIOD_MS = 5_000L
    }
}
