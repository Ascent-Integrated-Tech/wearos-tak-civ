package com.aitech.weartak_civ.service

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.service.ReactiveServiceManager.ServiceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 *  - General guideline to what dictates if a services is available for starting
 *
 * 	1.	User Setting (intent): e.g., enablePhysio == true, enableDitto == false
 *	2.	Policy Gates (environment):
 *          - licensing:     is this service a premium feature under license (ditto)
 *          - permissions:   do we have the permissions this service requires?
 *          - misc policies: any other reasons for disallowing a service to start
 *                  (e.g. the parent service is disabled, special modes like low power)
 *	3.	Runtime State: is the service already running?
 *                  (e.g. isRunning)
 *
 */

class ServiceConfiguration(
    private val context: Context,
    private val dataRepository: DataRepository
) {
    private val TAG = "WearTAK-ServiceConfig"

    fun getServiceEnabledFlow(type: ServiceType): Flow<Boolean> = when (type) {
        ServiceType.LOCATION ->
            dataRepository.getEnableLocationService.distinctUntilChanged()

        /* PHYSIO Service is virtual group – not used to start a real service.
         * It is like an API for the UI and Knox to gate the HR, Skin, and
         * Act Rec Services. If disabled, nothing runs. If Enabled, we check
         * whether individual services are enabled / disabled. Individual service
         * toggles are controlled in Knox and Knox only (i.e. there are no settings
         * UI toggle buttons for HR, Skin, Act Rec)
         */
        ServiceType.PHYSIO ->
            dataRepository.getEnablePhysiologicalServices.distinctUntilChanged()

        ServiceType.HEART_RATE -> combine(
            dataRepository.getEnablePhysiologicalServices.distinctUntilChanged(),    // group intent
            dataRepository.getEnableHeartRateService.distinctUntilChanged()        // per-service intent
        ) { physioOn, hrOn ->
            if (!physioOn) { false } else { hrOn }
        }.distinctUntilChanged()

        ServiceType.SKIN_TEMP -> combine(
            dataRepository.getEnablePhysiologicalServices.distinctUntilChanged(),
            dataRepository.getEnableSkinTempService.distinctUntilChanged()
        ) { physioOn, skinOn ->
            if (!physioOn) { false } else { skinOn }
        }.distinctUntilChanged()

        ServiceType.ACTIVITY_RECOGNITION -> combine(
            dataRepository.getEnablePhysiologicalServices.distinctUntilChanged(),
            dataRepository.getEnableActivityRecognitionService.distinctUntilChanged()
        ) { physioOn, actOn ->
            if (!physioOn) { false } else { actOn }
        }.distinctUntilChanged()

        ServiceType.DITTO -> dataRepository.getEnableDittoService.distinctUntilChanged()
        ServiceType.HAIL -> dataRepository.getEnableHailService.distinctUntilChanged()
        ServiceType.CHAT  -> dataRepository.getEnableChatService.distinctUntilChanged()
        ServiceType.BLE_BEACON -> dataRepository.getEnableBleBeaconService.distinctUntilChanged()

        // Always-desired core services. Runtime gating still happens in isServiceAvailable().
        ServiceType.TAK_CONNECT,
        ServiceType.COT_DISPATCH,
        ServiceType.REPORTING_INTERVAL,
        ServiceType.MOVEMENT_DETECTION,
        ServiceType.CUST_ACCESS,
        ServiceType.BATTERY,
        ServiceType.POWER_MGR -> flowOf(true).distinctUntilChanged()
        ServiceType.OFF_BODY_DETECT -> flowOf(true).distinctUntilChanged()
    }

    private fun requiredPerms(type: ServiceType): List<String> = when (type) {
        ServiceType.LOCATION -> listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )

        ServiceType.PHYSIO -> listOf(
            android.Manifest.permission.BODY_SENSORS,
            android.Manifest.permission.BODY_SENSORS_BACKGROUND,
            android.Manifest.permission.ACTIVITY_RECOGNITION
        )

        ServiceType.HEART_RATE,
        ServiceType.SKIN_TEMP,
        ServiceType.ACTIVITY_RECOGNITION -> requiredPerms(ServiceType.PHYSIO)

        ServiceType.BLE_BEACON -> buildList {
            add(android.Manifest.permission.BLUETOOTH_SCAN)
            add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }

        ServiceType.DITTO -> buildList {
            add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            add(android.Manifest.permission.BLUETOOTH_SCAN)
            add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }

        ServiceType.HAIL,

        ServiceType.OFF_BODY_DETECT -> listOf(
            android.Manifest.permission.BODY_SENSORS,
            android.Manifest.permission.BODY_SENSORS_BACKGROUND
        )

        ServiceType.CHAT -> emptyList()

        ServiceType.TAK_CONNECT,
        ServiceType.CUST_ACCESS,
        ServiceType.REPORTING_INTERVAL,
        ServiceType.COT_DISPATCH,
        ServiceType.MOVEMENT_DETECTION,
        ServiceType.BATTERY,
        ServiceType.POWER_MGR -> emptyList()
    }

    private fun missingPerms(type: ServiceType): List<String> {
        val req = requiredPerms(type)
        if (req.isEmpty()) return emptyList()
        return req.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun permissionsGranted(type: ServiceType): Boolean {
        val missing = missingPerms(type)
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Missing permissions for $type: $missing")
            return false
        }
        return true
    }

    @Suppress("DEPRECATION")
    fun isServiceAvailable(type: ServiceType): Boolean {
        if (!isLicenseValid()) return false
        return permissionsGranted(type)
    }
}