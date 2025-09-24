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
        ServiceType.TAK_CONNECT to TAKConnectService::class.java,
        ServiceType.COT_DISPATCH to CotDispatchService::class.java,
        ServiceType.REPORTING_INTERVAL to ReportingIntervalStrategyService::class.java,
        ServiceType.LOCATION to LocationService::class.java,
        ServiceType.HEART_RATE to HeartrateService::class.java,
        ServiceType.SKIN_TEMP to SkinTempService::class.java,
        ServiceType.DITTO to DittoService::class.java,
        ServiceType.CHAT to ChatService::class.java,
        ServiceType.BLE_BEACON to BleBeaconService::class.java,
        ServiceType.MOVEMENT_DETECTION to MovementDetectionService::class.java,
        ServiceType.ACTIVITY_RECOGNITION to ActivityRecognitionService::class.java,
        ServiceType.OFF_BODY_DETECT to OffBodyDetectService::class.java,
        ServiceType.BATTERY to BatteryService::class.java,
        ServiceType.POWER_MGR to PowerManagerService::class.java
    )

    private val serviceRunningStateMap = mapOf(
        ServiceType.TAK_CONNECT to TAKConnectService.isRunning,
        ServiceType.COT_DISPATCH to CotDispatchService.isRunning,
        ServiceType.REPORTING_INTERVAL to ReportingIntervalStrategyService.isRunning,
        ServiceType.CUST_ACCESS to CustomAccessibilityService.isRunning,
        ServiceType.LOCATION to LocationService.isRunning,
        ServiceType.HEART_RATE to HeartrateService.isRunning,
        ServiceType.SKIN_TEMP to SkinTempService.isRunning,
        ServiceType.DITTO to DittoService.isRunning,
        ServiceType.CHAT to ChatService.isRunning,
        ServiceType.BLE_BEACON to BleBeaconService.isRunning,
        ServiceType.MOVEMENT_DETECTION to MovementDetectionService.isRunning,
        ServiceType.ACTIVITY_RECOGNITION to ActivityRecognitionService.isRunning,
        ServiceType.OFF_BODY_DETECT to OffBodyDetectService.isRunning,
        ServiceType.BATTERY to BatteryService.isRunning,
        ServiceType.POWER_MGR to PowerManagerService.isRunning
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