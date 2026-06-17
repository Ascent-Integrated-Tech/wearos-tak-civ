/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.CrmRepository
import com.aitech.weartak_civ.data.RawPpgRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

class CrmService : Service() {

    companion object {
        private const val TAG = "WearTAK-CrmService"
        private const val NOTIF_ID = 178

        private const val WINDOW_SECONDS = 20
        private const val MIN_READY_SECONDS = 5
        private const val MIN_BASELINE_AMPLITUDE = 1f

        val isRunning = AtomicBoolean(false)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null

    private val rawPpgRepo by lazy {
        try {
            (applicationContext as WearTakApplication).rawPpgRepo
        } catch (_: Throwable) {
            RawPpgRepository()
        }
    }

    private val crmRepo by lazy {
        try {
            (applicationContext as WearTakApplication).crmRepo
        } catch (_: Throwable) {
            CrmRepository()
        }
    }

    private val rollingSamples = ArrayDeque<Int>(4096)
    private var baselineAmplitude: Float? = null

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "CrmService onCreate()")

        isRunning.set(true)

        startForegroundServiceCompat(
            NOTIF_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        collectJob = serviceScope.launch {
            crmRepo.updateState(
                CrmRepository.CrmState(
                    source = CrmRepository.CrmSource.HEURISTIC,
                    isProvisional = true,
                    signalReady = false,
                    status = "starting"
                )
            )

            rawPpgRepo.rawPpgFrames.collect { frame ->
                if (frame.wavelength != "green" || frame.samples.isEmpty()) {
                    return@collect
                }

                appendSamples(frame.samples, frame.sampleRateHz)
                publishCrmState(frame.timestampMs, frame.sampleRateHz)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int = START_STICKY

    override fun onDestroy() {
        Log.d(TAG, "CrmService onDestroy()")

        runBlocking {
            crmRepo.updateState(CrmRepository.CrmState())
        }

        collectJob?.cancel()
        serviceScope.cancel()

        isRunning.set(false)

        stopForeground(STOP_FOREGROUND_DETACH)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun appendSamples(samples: IntArray, sampleRateHz: Int) {
        samples.forEach { rollingSamples.addLast(it) }

        val maxSamples = max(sampleRateHz, 1) * WINDOW_SECONDS

        while (rollingSamples.size > maxSamples) {
            rollingSamples.removeFirst()
        }
    }

    private suspend fun publishCrmState(
        timestampMs: Long,
        sampleRateHz: Int
    ) {
        val minReadySamples = max(sampleRateHz, 1) * MIN_READY_SECONDS

        if (rollingSamples.size < minReadySamples) {
            crmRepo.updateState(
                CrmRepository.CrmState(
                    updatedAtMs = timestampMs,
                    source = CrmRepository.CrmSource.HEURISTIC,
                    isProvisional = true,
                    signalReady = false,
                    status = "awaiting_signal"
                )
            )
            return
        }

        val amplitude = computeAmplitude(rollingSamples.toList().toIntArray())
        val baseline = updateBaseline(amplitude)

        val score = ((amplitude / baseline) * 100f)
            .roundToInt()
            .coerceIn(0, 100)

        val band = when {
            score <= 50 -> CrmRepository.CrmBand.RED
            score <= 75 -> CrmRepository.CrmBand.YELLOW
            else -> CrmRepository.CrmBand.GREEN
        }

        crmRepo.updateState(
            CrmRepository.CrmState(
                score = score,
                band = band,
                updatedAtMs = timestampMs,
                source = CrmRepository.CrmSource.HEURISTIC,
                isProvisional = true,
                signalReady = true,
                status = "ready"
            )
        )
    }

    private fun updateBaseline(amplitude: Float): Float {
        val safeAmplitude = amplitude.coerceAtLeast(MIN_BASELINE_AMPLITUDE)

        val current = baselineAmplitude

        val next = if (current == null) {
            safeAmplitude
        } else {
            max(safeAmplitude, current * 0.999f)
        }

        baselineAmplitude = next
        return next
    }

    private fun computeAmplitude(samples: IntArray): Float {
        if (samples.isEmpty()) {
            return 0f
        }

        val sorted = samples.copyOf()
        sorted.sort()

        val lowIndex = ((sorted.size - 1) * 0.05f)
            .roundToInt()
            .coerceIn(0, sorted.lastIndex)

        val highIndex = ((sorted.size - 1) * 0.95f)
            .roundToInt()
            .coerceIn(0, sorted.lastIndex)

        val amplitude = sorted[highIndex] - sorted[lowIndex]

        Log.d(
            TAG,
            "CRM provisional amplitude=$amplitude baseline=${baselineAmplitude ?: -1f} samples=${samples.size}"
        )

        return amplitude.toFloat().coerceAtLeast(0f)
    }
}