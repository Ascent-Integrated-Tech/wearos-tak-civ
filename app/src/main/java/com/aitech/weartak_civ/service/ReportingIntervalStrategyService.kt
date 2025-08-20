/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
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
import com.aitech.weartak_civ.DEFAULT_ON_FOOT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_STATIONARY_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_VEHICLE_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.UPDATE_INTERVAL_CHANGED
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max


class ReportingIntervalStrategyService : Service() {
    private val TAG = "WearTAK-" + ReportingIntervalStrategyService::class.java.simpleName
    private lateinit var dataRepository: DataRepository
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var enableDynamicReportingStrategy = false
    private var constantReportingInterval = DEFAULT_REPORTING_INTERVAL
    private var whileAlertingReportingInterval = DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL
    private var onFootReportingInterval = DEFAULT_ON_FOOT_REPORTING_INTERVAL
    private var vehicleReportingInterval = DEFAULT_VEHICLE_REPORTING_INTERVAL
    private var stationaryReportingInterval = DEFAULT_STATIONARY_REPORTING_INTERVAL
    private var dynamicReportingInterval = DEFAULT_REPORTING_INTERVAL
    private var reportingInterval = DEFAULT_REPORTING_INTERVAL
    private var isAlerting = false
    private var latestActivity = ActivityRecognized.UNKNOWN

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        dataRepository = MainActivity.dataRepository  // single instance of data repository shared by all instances
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (!isRunning.getAndSet(true)) {
            startForegroundServiceCompat(REPORTING_INTERVAL_STRATEGY_SERVICE_NOTIFICATION_ID, importance = NotificationManager.IMPORTANCE_LOW)
            Log.d(TAG, "ReportingIntervalStrategyService onStartCommand called")
            initializeVariables()
            startEnableDynamicStrategyCollector()
            startDynamicReportingIntervalCollector()
            startConstantReportingIntervalCollector()
            startIsAlertingCollector()
            startActivityCollector()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        // TODO: should we serviceJob.cancel()
        serviceScope.cancel()
        Log.d(TAG, "ReportingIntervalStrategyService destroyed")
    }

    private fun initializeVariables() {
        runBlocking {
            enableDynamicReportingStrategy = dataRepository.getEnableDynamicReportingStrategy.first()
            constantReportingInterval = dataRepository.getConstantReportingInterval.first()
            whileAlertingReportingInterval = dataRepository.getWhileAlertingReportingInterval.first()
            onFootReportingInterval = dataRepository.getOnFootReportingInterval.first()
            vehicleReportingInterval = dataRepository.getVehicleReportingInterval.first()
            stationaryReportingInterval = dataRepository.getStationaryReportingInterval.first()
            isAlerting = dataRepository.isAlerting.first()
            latestActivity = dataRepository.latestActivityRecognized.first()

            dynamicReportingInterval = calcDynamicReportingInterval(isAlerting, latestActivity,
                whileAlertingReportingInterval, onFootReportingInterval, vehicleReportingInterval, stationaryReportingInterval)
            reportingInterval = if (enableDynamicReportingStrategy) { dynamicReportingInterval } else { constantReportingInterval }
            dataRepository.storeReportingUpdateInterval(reportingInterval)
            Log.d(TAG, "initializeVariables enableDynamicReportingStrategy($enableDynamicReportingStrategy): " +
                    "reportingInterval=$reportingInterval w/ constantReportingInterval=$constantReportingInterval, dynamicReportingInterval=$dynamicReportingInterval " +
                    "(activity=$latestActivity, isAlerting=$isAlerting, alerting=$whileAlertingReportingInterval, onFoot=$onFootReportingInterval, vehicle=$vehicleReportingInterval, stationary=$stationaryReportingInterval)")
            if (reportingInterval != CotDispatchService.reportingUpdateIntervalSecs) {
                Log.d(TAG, "initializeVariables reportingInterval($reportingInterval) != CotDispatchService.reportingUpdateIntervalSecs(${CotDispatchService.reportingUpdateIntervalSecs}). Sending UPDATE_INTERVAL_CHANGED")
                CotDispatchService.reportingUpdateIntervalSecs = reportingInterval
                EventBus.getDefault().post(MessageEvent(UPDATE_INTERVAL_CHANGED, reportingInterval))
            }
        }
    }

    private suspend fun calcReportingIntervalAndSendUpdate() {
        reportingInterval = if (enableDynamicReportingStrategy) { dynamicReportingInterval } else { constantReportingInterval }
        dataRepository.storeReportingUpdateInterval(reportingInterval)
        //Log.d(TAG, "calcReportingIntervalAndSendUpdate enableDynamicReportingStrategy($enableDynamicReportingStrategy): " +
        //            "reportingInterval=$reportingInterval w/ constantReportingInterval=$constantReportingInterval, dynamicReportingInterval=$dynamicReportingInterval " +
        //            "(activity=$latestActivity, alerting=$isAlerting, onFoot=$onFootReportingInterval, vehicle=$vehicleReportingInterval, stationary=$stationaryReportingInterval)")
        if (reportingInterval != CotDispatchService.reportingUpdateIntervalSecs) {
            //Log.d(TAG, "calcReportingIntervalAndSendUpdate reportingInterval($reportingInterval) != CotDispatchService.reportingUpdateIntervalSecs(${CotDispatchService.reportingUpdateIntervalSecs}). Sending UPDATE_INTERVAL_CHANGED")
            CotDispatchService.reportingUpdateIntervalSecs = reportingInterval
            EventBus.getDefault().post(MessageEvent(UPDATE_INTERVAL_CHANGED, reportingInterval))
        }
    }

    private suspend fun calcDynamicReportingIntervalAndSendUpdate() {
        val dynamicRI = calcDynamicReportingInterval(isAlerting, latestActivity,
            whileAlertingReportingInterval, onFootReportingInterval, vehicleReportingInterval, stationaryReportingInterval)
        if (dynamicRI != dynamicReportingInterval) {
            Log.d(TAG, "dynamicReportingInterval changed from $dynamicReportingInterval to $dynamicRI")
            dynamicReportingInterval = dynamicRI
            calcReportingIntervalAndSendUpdate()
        }
    }

    private fun startEnableDynamicStrategyCollector() {
        serviceScope.launch {
            dataRepository.enableDynamicReportingStrategy.collect { dynRS ->
                if ((null != dynRS) && (enableDynamicReportingStrategy != dynRS)) {
                    Log.d(TAG, "startEnableDynamicStrategyCollector: enableDynamicReportingStrategy changed from $enableDynamicReportingStrategy to $dynRS")
                    enableDynamicReportingStrategy = dynRS
                    calcReportingIntervalAndSendUpdate()
                }
            }
        }
    }

    private fun startConstantReportingIntervalCollector() {
        serviceScope.launch {
            dataRepository.constantReportingInterval.collect { constantRI ->
                if ((null != constantRI) && (constantReportingInterval != constantRI)) {
                    Log.d(TAG, "constantReportingInterval changed from $constantReportingInterval to $constantRI")
                    constantReportingInterval = constantRI
                    calcReportingIntervalAndSendUpdate()
                }
            }
        }
    }

    private fun startDynamicReportingIntervalCollector() {
        serviceScope.launch {
            dataRepository.whileAlertingReportingInterval.collect { alertingRI ->
                if ((null != alertingRI) && (whileAlertingReportingInterval != alertingRI)) {
                    Log.d(TAG, "whileAlertingReportingInterval changed from $whileAlertingReportingInterval to $alertingRI")
                    whileAlertingReportingInterval = alertingRI
                    calcDynamicReportingIntervalAndSendUpdate()
                }
            }
        }
        serviceScope.launch {
            dataRepository.onFootReportingInterval.collect { onFootRI ->
                if ((null != onFootRI) && (onFootReportingInterval != onFootRI)) {
                    Log.d(TAG, "onFootReportingInterval changed from $onFootReportingInterval to $onFootRI")
                    onFootReportingInterval = onFootRI
                    calcDynamicReportingIntervalAndSendUpdate()
                }
            }
        }
        serviceScope.launch {
            dataRepository.vehicleReportingInterval.collect { vehicleRI ->
                if ((null != vehicleRI) && (vehicleReportingInterval != vehicleRI)) {
                    Log.d(TAG, "vehicleReportingInterval changed from $vehicleReportingInterval to $vehicleRI")
                    vehicleReportingInterval = vehicleRI
                    calcDynamicReportingIntervalAndSendUpdate()
                }
            }
        }
        serviceScope.launch {
            dataRepository.stationaryReportingInterval.collect { stationaryRI ->
                if ((null != stationaryRI) && (stationaryReportingInterval != stationaryRI)) {
                    Log.d(TAG, "stationaryReportingInterval changed from $stationaryReportingInterval to $stationaryRI")
                    stationaryReportingInterval = stationaryRI
                    calcDynamicReportingIntervalAndSendUpdate()
                }
            }
        }
    }

    private fun startActivityCollector() {
        serviceScope.launch {
            dataRepository.latestActivityRecognized.collect { activity ->
                if (latestActivity != activity) {
                    Log.d(TAG, "latestActivity changed from ${latestActivity.name} to ${activity.name}")
                    latestActivity = activity
                    calcDynamicReportingIntervalAndSendUpdate()
                }
            }
        }
    }

    private fun startIsAlertingCollector() {
        serviceScope.launch {
            dataRepository.isAlerting.collect { alert ->
                if (isAlerting != alert) {
                    Log.d(TAG, "isAlerting changed from $isAlerting to $alert")
                    isAlerting = alert
                    calcDynamicReportingIntervalAndSendUpdate()
                }
            }
        }
    }

    companion object {
        val isRunning = AtomicBoolean(false)
        private const val REPORTING_INTERVAL_STRATEGY_SERVICE_NOTIFICATION_ID = 1123

        fun calcDynamicReportingInterval(alerting: Boolean, activity: ActivityRecognized, alertingRI: Int, onFootRI: Int, vehicleRI: Int, stationaryRI: Int) : Int {
            if (alerting) {
                return alertingRI
            }
            return when (activity) {
                ActivityRecognized.ON_FOOT, ActivityRecognized.RUNNING , ActivityRecognized.WALKING -> onFootRI
                ActivityRecognized.IN_VEHICLE -> vehicleRI
                ActivityRecognized.TILTING, ActivityRecognized.BICYCLING, ActivityRecognized.UNKNOWN -> max(onFootRI, vehicleRI)
                ActivityRecognized.STILL -> stationaryRI
            }
        }
    }
}