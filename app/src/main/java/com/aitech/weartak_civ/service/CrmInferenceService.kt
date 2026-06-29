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
import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.CrmDataRepository
import com.aitech.weartak_civ.data.CrmRawPpgSample
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.crm.CrmInferenceCoordinator
import com.aitech.weartak_civ.service.crm.TfliteCrmModelRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CrmInferenceService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ppgCollectJob: Job? = null
    private var lastRawLogUptimeMs = 0L

    private val dataRepository: DataRepository by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }

    private val crmRepo: CrmDataRepository by lazy {
        try {
            (applicationContext as WearTakApplication).crmRepo
        } catch (_: Throwable) {
            CrmDataRepository(dataRepository)
        }
    }

    private val coordinator: CrmInferenceCoordinator by lazy {
        CrmInferenceCoordinator(
            crmRepo = crmRepo,
            runnerFactory = { TfliteCrmModelRunner(applicationContext) }
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CRM_SERVICE_CREATE starting foreground model service")
        isRunning.set(true)
        startForegroundServiceCompat(NOTIFICATION_ID, importance = NotificationManager.IMPORTANCE_LOW)
        coordinator.warmUpModel()
        startCollectingPpg()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "CRM_SERVICE_START startId=$startId")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "CRM_SERVICE_DESTROY")
        isRunning.set(false)
        ppgCollectJob?.cancel()
        coordinator.close()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCollectingPpg() {
        if (ppgCollectJob?.isActive == true) return
        ppgCollectJob = serviceScope.launch {
            Log.i(TAG, "CRM_PPG_COLLECT_START waiting for SkinTempService raw PPG")
            crmRepo.rawPpgSamples.collect { sample ->
                onPpgSample(sample)
            }
        }
    }

    private fun onPpgSample(sample: CrmRawPpgSample) {
        if (!sample.sourceAvailable || !sample.deviceWorn || sample.rawValue == null) {
            val reason = sample.reason ?: "ppg_source_unavailable"
            Log.i(TAG, "CRM_PPG_SOURCE_UNAVAILABLE reason=$reason worn=${sample.deviceWorn} available=${sample.sourceAvailable}")
            coordinator.reset(reason)
            return
        }

        maybeLogRawSample(sample)
        coordinator.onRawPpgSample(
            sampleUptimeMillis = sample.uptimeMillis,
            rawValue = sample.rawValue,
            sourceAvailable = true
        )
    }

    private fun maybeLogRawSample(sample: CrmRawPpgSample) {
        val now = SystemClock.uptimeMillis()
        if (now - lastRawLogUptimeMs < RAW_LOG_PERIOD_MS) return
        lastRawLogUptimeMs = now
        Log.d(
            TAG,
            "CRM_PPG_SAMPLE raw=${sample.rawValue} uptimeMs=${sample.uptimeMillis} emittedAtMs=${sample.emittedAtMillis}"
        )
    }

    companion object {
        private const val TAG = "WearTAK-CrmInferenceService"
        private const val NOTIFICATION_ID = 921
        private const val RAW_LOG_PERIOD_MS = 2_000L
        val isRunning: AtomicBoolean = AtomicBoolean(false)
    }
}
