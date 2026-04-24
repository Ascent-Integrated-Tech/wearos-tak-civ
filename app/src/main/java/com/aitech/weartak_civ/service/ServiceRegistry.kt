/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.Service
import com.aitech.weartak_civ.service.ReactiveServiceManager.ServiceType

/**
 * Registry that maps ServiceType enums to actual Service classes
 * and provides service status checking capabilities.
 */
class ServiceRegistry() {
    /**
     * Physio & custom accessibility are not managed by ReactiveServiceMgr.
     * Custom access is triggered by the OS setting and Physio is a virtual
     * service representing Heartrate, SkinTemp (Samsung), ActivityRec (Google).
     */
    private val runnableServiceClassMap = mapOf(
        ServiceType.WEATHER to WeatherService::class.java,
        ServiceType.TAK_CONNECT to TAKConnectService::class.java,
        ServiceType.COT_DISPATCH to CotDispatchService::class.java,
        ServiceType.REPORTING_INTERVAL to ReportingIntervalStrategyService::class.java,
        ServiceType.LOCATION to LocationService::class.java,
        ServiceType.HEART_RATE to HeartrateService::class.java,
        ServiceType.SKIN_TEMP to SkinTempService::class.java,
        ServiceType.PRESSURE to PressureService::class.java,
        ServiceType.DITTO to DittoService::class.java,
        ServiceType.PEAT_BTLE to PeatBtleService::class.java,
        ServiceType.SITX_SERVICE to SitxTakService::class.java,
        ServiceType.CHAT to ChatService::class.java,
        ServiceType.BLE_BEACON to BleBeaconService::class.java,
        ServiceType.MOVEMENT_DETECTION to MovementDetectionService::class.java,
        ServiceType.ACTIVITY_RECOGNITION to ActivityRecognitionService::class.java,
        ServiceType.OFF_BODY_DETECT to OffBodyDetectService::class.java,
        ServiceType.BATTERY to BatteryService::class.java,
        ServiceType.GEOFENCE to GeofenceService::class.java,
        ServiceType.POWER_MGR to PowerManagerService::class.java,
        ServiceType.BLOOD_HOUND to BloodhoundService::class.java,
        ServiceType.COMPANION_APP to CompanionAppService::class.java,
        ServiceType.AUDIO_CAP to AudioCaptureService::class.java,
        ServiceType.ONNX_GUNSHOT to GunshotOnnxService::class.java,
        ServiceType.ONNX_UAS to UasOnnxService::class.java,
        ServiceType.CEP_SIMULATION to CEPSimulationService::class.java
    )

    private val serviceRunningStateMap = mapOf(
        ServiceType.WEATHER to WeatherService.isRunning,
        ServiceType.TAK_CONNECT to TAKConnectService.isRunning,
        ServiceType.COT_DISPATCH to CotDispatchService.isRunning,
        ServiceType.REPORTING_INTERVAL to ReportingIntervalStrategyService.isRunning,
        ServiceType.CUST_ACCESS to CustomAccessibilityService.isRunning,
        ServiceType.LOCATION to LocationService.isRunning,
        ServiceType.HEART_RATE to HeartrateService.isRunning,
        ServiceType.SKIN_TEMP to SkinTempService.isRunning,
        ServiceType.PRESSURE to PressureService.isRunning,
        ServiceType.DITTO to DittoService.isRunning,
        ServiceType.PEAT_BTLE to PeatBtleService.isRunning,
        ServiceType.SITX_SERVICE to SitxTakService.isRunning,
        ServiceType.CHAT to ChatService.isRunning,
        ServiceType.BLE_BEACON to BleBeaconService.isRunning,
        ServiceType.MOVEMENT_DETECTION to MovementDetectionService.isRunning,
        ServiceType.ACTIVITY_RECOGNITION to ActivityRecognitionService.isRunning,
        ServiceType.OFF_BODY_DETECT to OffBodyDetectService.isRunning,
        ServiceType.BATTERY to BatteryService.isRunning,
        ServiceType.GEOFENCE to GeofenceService.isRunning,
        ServiceType.POWER_MGR to PowerManagerService.isRunning,
        ServiceType.BLOOD_HOUND to BloodhoundService.isRunning,
        ServiceType.COMPANION_APP to CompanionAppService.isRunning,
        ServiceType.AUDIO_CAP to AudioCaptureService.isRunning,
        ServiceType.ONNX_GUNSHOT to GunshotOnnxService.isRunning,
        ServiceType.ONNX_UAS to UasOnnxService.isRunning,
        ServiceType.CEP_SIMULATION to CEPSimulationService.isRunning
    )

    /**
     * Get the Service class for a given ServiceType
     */
    fun getServiceClass(serviceType: ServiceType): Class<out Service>? {
        return runnableServiceClassMap[serviceType]
    }

    /**
     * Check if a service is currently running
     */
    fun isServiceRunning(serviceType: ServiceType): Boolean {
        val runningState = serviceRunningStateMap[serviceType]
        return runningState?.get() ?: false
    }

    /**
     * Update the running state flag for a service in the registry.
     * No-op if the service type is unknown.
     */
    fun setServiceRunning(serviceType: ServiceType, isRunning: Boolean) {
        serviceRunningStateMap[serviceType]?.set(isRunning)
    }

    /**
     * Get all registered service types
     */
    fun getAllServiceTypes(): List<ServiceType> {
        return runnableServiceClassMap.keys.toList()
    }

    /**
     * Get services that are currently running
     */
    fun getRunningServices(): List<ServiceType> {
        return serviceRunningStateMap.filter { (_, runningState) ->
            runningState.get()
        }.keys.toList()
    }

    /**
     * Get services that are not running
     */
    fun getStoppedServices(): List<ServiceType> {
        return serviceRunningStateMap.filter { (_, runningState) ->
            !runningState.get()
        }.keys.toList()
    }
}
