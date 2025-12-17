/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.location.Location
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.DEFAULT_AGE
import com.aitech.weartak_civ.DEFAULT_BATT_ALERT_PERCENTAGE
import com.aitech.weartak_civ.DEFAULT_BATT_WARNING_PERCENTAGE
import com.aitech.weartak_civ.DEFAULT_ON_FOOT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_RESTING_HR
import com.aitech.weartak_civ.DEFAULT_STATIONARY_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_VEHICLE_REPORTING_INTERVAL
import com.aitech.weartak_civ.DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL
import com.aitech.weartak_civ.service.ChatService
import com.aitech.weartak_civ.service.ReactiveServiceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterNotNull
import java.time.Instant
import kotlinx.coroutines.flow.stateIn
import com.aitech.weartak_civ.Utils.SettingsUtils.CategoryRolesEntry
import com.aitech.weartak_civ.events.CustomEmergencyEvent
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.CAT_MANUAL
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.DESC_SOS_ALERT
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import com.aitech.weartak_civ.service.HailService
import com.aitech.weartak_civ.service.HailService.HailLicenseStates
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.EnumMap
import java.util.UUID
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator


private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "data")
private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class DataRepository(private val context: Context) {
    private val TAG = "WearTAK-" + DataRepository::class.java.simpleName

    val passiveDataEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PASSIVE_DATA_ENABLED] ?: false
    }

    suspend fun setPassiveDataEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PASSIVE_DATA_ENABLED] = enabled
        }
    }

    /**
     * Below is a prototype of a resting heart rate average calculator, using aggregate
     * heart rate data to generate an average.
     */
//    init {
//        repoScope.launch {
//            _latestHeartRate
//                .filterNotNull()
//                .combine(_latestActivityRecognized.filterNotNull()) { hr, activity ->
//                    hr to activity
//                }
//                .filter { (_, activity) -> activity == "STILL" }
//                .collect { (hr, _) ->
//                    restingAggregator.pushSample(hr.toDouble())
//
//                    restingAggregator.computeAverage()?.let { avg ->
//                        _restingHeartRate.emit(avg.roundToInt())
//                    }
//                }
//        }
//    }

    private val _missingPermissions = MutableStateFlow<List<String>>(emptyList())
    val missingPermissions: StateFlow<List<String>> = _missingPermissions

    suspend fun updateMissingPermissions(perms: List<String>) {
        _missingPermissions.emit(perms)
    }

    // ======== ======== HAIL SECTION START ======== ========

    /**
     * Hail Service is controlled by the User (or in Knox mgd configs), this gate
     * initiates hail licensing and injection of data into a Hail Cot Detail, given
     * the license is valid.
     */
    suspend fun storeEnableHailService(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_HAIL_SERVICE] = enable
        }
    }

    val getEnableHailService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_HAIL_SERVICE] ?: false
    }

    suspend fun storeEnableBatdokCot(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_BATDOK_COT] = enable
        }
    }

    val getEnableBatdokCot: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_BATDOK_COT] ?: true
    }



    /**
     * Hail License State can be one of the four states below and controls
     * UI screens, license authentication, and activation of building
     * the Hail CotDetail. See HailService.HailLicenseStates{} for more info:
     *
     *     enum class HailLicenseStates {
     *         VALID,
     *         CHECKING,
     *         SOFT_EXPIRED,
     *         EXPIRED,
     *         INVALID
     *     }
     **/
    private fun String.toHailStateOr(default: HailLicenseStates): HailLicenseStates =
        runCatching { enumValueOf<HailLicenseStates>(this) }.getOrElse { default }

    val hailLicenseState: StateFlow<HailLicenseStates> =
        context.dataStore.data
            .map { prefs ->
                prefs[HAIL_LICENSE_STATE]?.toHailStateOr(HailLicenseStates.INVALID)
                    ?: HailLicenseStates.INVALID
            }
            .stateIn(
                scope = repoScope,
                started = SharingStarted.Eagerly,
                initialValue = HailLicenseStates.INVALID
            )

    suspend fun storeHailLicenseState(state: HailLicenseStates?) {
        context.dataStore.edit { prefs ->
            if (state == null) {
                prefs.remove(HAIL_LICENSE_STATE)   // don’t persist “null”
            } else {
                prefs[HAIL_LICENSE_STATE] = state.name
            }
        }
    }

    val getHailLicenseState: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[HAIL_LICENSE_STATE] ?: HailLicenseStates.INVALID.name
    }

    /**
     * Combat ID is unique to each hail USER (aka INDIVIDUAL PERSON), not
     * necessarily a device. This is a hail feature so users can have
     * multiple devices/sensors feeding data into hail. The CombatID is
     * required in the Hail CotDetail and is synonymous with a "card" in
     * the ATAK plugin UI.
     */
    suspend fun storeCombatId(id: Int) {
        context.dataStore.edit { prefs ->
            prefs[HAIL_COMBAT_ID] = id
        }
    }
    val getCombatId: Flow<Int?> = context.dataStore.data.map { prefs ->
        prefs[HAIL_COMBAT_ID]
    }

    /**
     * When check button is clicked in UI, this value will be
     * flipped to true, and the license will be checked.
     */
    var hailCheckLicenseButtonPressed = MutableStateFlow<Boolean>(false)

    /**
     * Change part of the saved license info without overwriting everything.
     * Reads the current value, gives it to the transform function,
     * then saves whatever that function returns.
     * Return null from the transform to clear the value.
     */
    suspend fun updateHailLicenseInfo(
        transform: (HailService.HailLicenseInfo?) -> HailService.HailLicenseInfo?
    ) {
        context.dataStore.edit { prefs ->
            val beforeJson = prefs[HAIL_LICENSE_INFO_JSON]
            val current = beforeJson?.let {
                runCatching { gson.fromJson(it, HailService.HailLicenseInfo::class.java) }.getOrNull()
            }
            val next = transform(current)
            val afterJson = next?.let { gson.toJson(it)}

            Log.d(TAG, "HailLicenseInfo Before=$beforeJson")
            Log.d(TAG, "HailLicenseInfo Current=$current")
            Log.d(TAG, "HailLicenseInfo After=$afterJson")

            if (next == null) {
                prefs.remove(HAIL_LICENSE_INFO_JSON)
            } else {
                prefs[HAIL_LICENSE_INFO_JSON] = gson.toJson(next)
            }
        }
    }

    /**
     * A live stream of the saved hail license info.
     * Every time the persistently stored value changes, this will emit
     * the new value. Emits null if there is nothing saved.
     */
    val hailLicenseInfoFlow: Flow<HailService.HailLicenseInfo?> =
        context.dataStore.data.map { prefs ->
            val json = prefs[HAIL_LICENSE_INFO_JSON] ?: return@map null
            runCatching {
                gson.fromJson(json, HailService.HailLicenseInfo::class.java)
            }.getOrNull()
        }

    /**
     * A version of hailLicenseInfoFlow that always remembers the latest value.
     * Useful for UI code so it can instantly read the current license info
     * without waiting for a change.
     */
    val hailInfoStateFlow: StateFlow<HailService.HailLicenseInfo?> =
        hailLicenseInfoFlow.stateIn(
            scope = repoScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /**
     * buildHailDetail is the final gate for Hail Service activation.
     * ReactiveServiceManager controls this gate, and the CotDispatchService
     * checks against it. Once true, the CotDispatchService constructs
     * the Hail Detail (via the Hail SDK APIs) and injects it into each
     * PLI CoT. The device will then show up on the Hail Plugin in ATAK.
     */
    val buildHailDetail: StateFlow<Boolean> =
        combine(getEnableHailService, hailLicenseState) { enable, state ->
            enable && (state == HailLicenseStates.VALID ||
                    state == HailLicenseStates.SOFT_EXPIRED)
        }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, /* initial */ false)

    // ======== ======== HAIL SECTION END ======== ==========

    // ======== ======== Multicast SECTION Start ======== ==========
    suspend fun storeEnableMulticast(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_MULTICAST] = enable
        }
    }

    val getEnableMulticast: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_MULTICAST] ?: true
    }
    // ======== ======== Multicast SECTION END ======== ==========

    private val _latestActivityConfidence = MutableStateFlow<Int>(100)
    val latestActivityConfidence: StateFlow<Int?> = _latestActivityConfidence

    private val _latestActivityRecognized = MutableStateFlow<ActivityRecognized>(ActivityRecognized.UNKNOWN)
    val latestActivityRecognized: StateFlow<ActivityRecognized> = _latestActivityRecognized

    private val _isAlerting = MutableStateFlow<Boolean>(false)
    val isAlerting : StateFlow<Boolean> = _isAlerting

    private val _latestHeartRate = MutableStateFlow<Int?>(null)
    val latestHeartRate: StateFlow<Int?> = _latestHeartRate

    private val _restingHeartRate = MutableStateFlow<Int>(DEFAULT_RESTING_HR)
    val restingHeartRate: StateFlow<Int> = _restingHeartRate

    private val _latestSkinTemp = MutableStateFlow<Float?>(null)
    val latestSkinTemp: StateFlow<Float?> = _latestSkinTemp

    private val _latestLocation = MutableStateFlow<Location?>(null)
    val latestLocation: StateFlow<Location?> = _latestLocation

    private val _latestBatteryPercentage = MutableStateFlow<Int?>(null)
    val latestBatteryPercentage: StateFlow<Int?> = _latestBatteryPercentage

    suspend fun updateChargingState(charging: Boolean) {
        _chargingState.emit(charging)
    }
    private val _chargingState = MutableStateFlow<Boolean>(false)
    val chargingState: StateFlow<Boolean> = _chargingState

    private val _latestIsDeviceWorn = MutableStateFlow<Boolean>(false)
    val latestIsDeviceWorn: StateFlow<Boolean> = _latestIsDeviceWorn

    // Enable Service Flow Toggles
    // --- Desired state (on/off intent) ---
    private val _serviceStateFlow =
        MutableStateFlow(EnumMap<ReactiveServiceManager.ServiceType, Boolean>(ReactiveServiceManager.ServiceType::class.java))

    val serviceStateFlow: StateFlow<EnumMap<ReactiveServiceManager.ServiceType, Boolean>> =
        _serviceStateFlow.asStateFlow()

    suspend fun updateServicesStateFlow(map: EnumMap<ReactiveServiceManager.ServiceType, Boolean>) {
        _serviceStateFlow.emit(map)
    }

    // --- Service States Debugging Tools ----
    private val _serviceDebugStateFlow =
        MutableStateFlow(EnumMap<ReactiveServiceManager.ServiceType, ReactiveServiceManager.ServiceDebugState?>(
            ReactiveServiceManager.ServiceType::class.java
        ))

    val serviceDebugStateFlow: StateFlow<EnumMap<ReactiveServiceManager.ServiceType, ReactiveServiceManager.ServiceDebugState?>> =
        _serviceDebugStateFlow.asStateFlow()

    // Merge helpers: update only one stage without losing the others
    suspend fun updateDebugStageRemoteKnox(knoxMap: EnumMap<ReactiveServiceManager.ServiceType, Boolean?>) {
        val current = _serviceDebugStateFlow.value.toMutableMap()
        for ((t, v) in knoxMap) {
            val prev = current[t]
            current[t] = ReactiveServiceManager.ServiceDebugState(
                type = t,
                remoteKnox = v,
                localCompanion = prev?.localCompanion,
                configUpdate = prev?.configUpdate,
                desired = prev?.desired ?: false,
                running = prev?.running ?: false
            )
        }
        _serviceDebugStateFlow.emit(EnumMap(current))
    }

    suspend fun updateDebugStageLocalPersisted(localMap: EnumMap<ReactiveServiceManager.ServiceType, Boolean?>) {
        val current = _serviceDebugStateFlow.value.toMutableMap()
        for ((t, v) in localMap) {
            val prev = current[t]
            current[t] = ReactiveServiceManager.ServiceDebugState(
                type = t,
                remoteKnox = prev?.remoteKnox,
                localCompanion = v,
                configUpdate = prev?.configUpdate,
                desired = prev?.desired ?: false,
                running = prev?.running ?: false
            )
        }
        _serviceDebugStateFlow.emit(EnumMap(current))
    }

    suspend fun updateDebugStageConfigUpdate(configMap: EnumMap<ReactiveServiceManager.ServiceType, Boolean?>) {
        val current = _serviceDebugStateFlow.value.toMutableMap()
        for ((t, v) in configMap) {
            val prev = current[t]
            current[t] = ReactiveServiceManager.ServiceDebugState(
                type = t,
                remoteKnox = prev?.remoteKnox,
                localCompanion = prev?.localCompanion,
                configUpdate = v,
                desired = prev?.desired ?: false,
                running = prev?.running ?: false
            )
        }
        _serviceDebugStateFlow.emit(EnumMap(current))
    }

    suspend fun updateDebugStageDesired(desiredMap: EnumMap<ReactiveServiceManager.ServiceType, Boolean>) {
        val current = _serviceDebugStateFlow.value.toMutableMap()
        for ((t, v) in desiredMap) {
            val prev = current[t]
            current[t] = ReactiveServiceManager.ServiceDebugState(
                type = t,
                remoteKnox = prev?.remoteKnox,
                localCompanion = prev?.localCompanion,
                configUpdate = prev?.configUpdate,
                desired = v,
                running = prev?.running ?: false
            )
        }
        _serviceDebugStateFlow.emit(EnumMap(current))
    }

    suspend fun updateDebugStageRunning(runningMap: EnumMap<ReactiveServiceManager.ServiceType, Boolean>) {
        val current = _serviceDebugStateFlow.value.toMutableMap()
        for ((t, v) in runningMap) {
            val prev = current[t]
            current[t] = ReactiveServiceManager.ServiceDebugState(
                type = t,
                remoteKnox = prev?.remoteKnox,
                localCompanion = prev?.localCompanion,
                configUpdate = prev?.configUpdate,
                desired = prev?.desired ?: false,
                running = v
            )
        }
        _serviceDebugStateFlow.emit(EnumMap(current))
    }

    // -------- End of Service State Debugging ----------

    val getEnableBloodhoundCompassService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_BLOODHOUND_COMPASS_SERVICE] ?: false
    }

    suspend fun storeEnableBloodhoundCompassService(enableBloodhoundService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_BLOODHOUND_COMPASS_SERVICE] = enableBloodhoundService
        }
    }

    private val _getEnableTakConnectService = MutableStateFlow<Boolean>(true)
    var getEnableTakConnectService: MutableStateFlow<Boolean> = _getEnableTakConnectService

    private val _getEnableCotDispatchService = MutableStateFlow<Boolean>(true)
    var getEnableCotDispatchService: MutableStateFlow<Boolean> = _getEnableCotDispatchService

    private val _getEnableMovementDetectionService = MutableStateFlow<Boolean>(true)
    var getEnableMovementDetectionService: MutableStateFlow<Boolean> = _getEnableMovementDetectionService

    private val _getEnableBatteryService = MutableStateFlow<Boolean>(true)
    var getEnableBatteryService: MutableStateFlow<Boolean> = _getEnableBatteryService

    private val _getEnablePowerManagerService = MutableStateFlow<Boolean>(true)
    var getEnablePowerManagerService: MutableStateFlow<Boolean> = _getEnablePowerManagerService

    private val _getEnableOffBodyDetectService = MutableStateFlow<Boolean>(true)
    var getEnableOffBodyDetectService: MutableStateFlow<Boolean> = _getEnableOffBodyDetectService

    @Entity(tableName = "daily_health_metrics")
    data class DailyHealthMetrics(
        @PrimaryKey val timestampHour: Instant,  // truncated to the top of the hour
        val steps: Long,
        val calories: Double,
        val elevationMeters: Double
    )

    val getRestingHeartRate: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[RESTING_HEART_RATE] ?: DEFAULT_RESTING_HR
    }

    suspend fun storeRestingHeartRate(restHr: Int) {
        context.dataStore.edit { prefs ->
            prefs[RESTING_HEART_RATE] = restHr
        }
        _restingHeartRate.emit(restHr)
    }

    suspend fun snapshotLocation(): LocationSnapshot? {
        val baseLoc = latestLocation.value ?: return null

        var latActual   = baseLoc.latitude
        var lonActual   = baseLoc.longitude
        var haeActual   = baseLoc.altitude
        var ceActual    = baseLoc.accuracy
        var leActual    = baseLoc.verticalAccuracyMeters

        if (ceActual.isNaN()) ceActual = 9999999.0f
        if (leActual.isNaN()) leActual = 9999999.0f

        // Apply BLE-beacon override if enabled
        if (getEnableBleBeaconService.first()) {
            val bleOutput = Utils.LocationUtils.mapLocationAgainstClosestBleBeacon(baseLoc)
            latActual = bleOutput.lat
            lonActual = bleOutput.lon
            haeActual = bleOutput.hae
            ceActual  = bleOutput.ce
        }

        return LocationSnapshot(
            timeMillis = baseLoc.time,
            lat = latActual,
            lon = lonActual,
            hae = haeActual,
            ce = ceActual,
            le = leActual,
            course = baseLoc.bearing.takeIf { !it.isNaN() },
            speed = baseLoc.speed.takeIf { !it.isNaN() }
        )
    }

    data class LocationSnapshot(
        val timeMillis: Long,
        val lat: Double,
        val lon: Double,
        val hae: Double?,
        val ce: Float?,    // circular error (horizontal)
        val le: Float?,    // linear error (vertical)
        val course: Float?, // optional: bearing
        val speed: Float?   // optional: m/s
    )

    data class PhysioData(
        val heartRateBpm: Int?,          // current HR
        val skinTempF: Float?,           // Temp of skin in Deg F
        val ageYears: Int?,              // physiological age used for calculations
        val weightKg: Int?,              // settings
        val restingHrBpm: Int?,          // baseline rest HR
        val exertion: Double?,           // 0..1 or null if unknown
        val exertionClass: String?,      // "LIGHT", "MODERATE", "VIGOROUS", "UNKNOWN"
        val dailyMetrics: DailyMetrics?  // aggregated steps/calories/floors/elevation
    )

    data class PhysioSnapshot(
        val timeMillis: Long,
        val data: PhysioData
    )

    suspend fun snapshotPhysio(): PhysioSnapshot {
        val nowMillis = System.currentTimeMillis()

        val hr          = latestHeartRate.value
        val skinT       = latestSkinTemp.value
        val ageYears    = getPhysiologicalMetricAge.first()
        val weightKg    = getPhysiologicalMetricWeight.first()
        val restingHr   = getRestingHeartRate.first()
        val exertion    = latestExertion.value
        val daily       = latestDailyMetrics.value

        val exertionClass: String? = exertion.let {
            Utils.PhysioUtils.classifyExertion(it)
        }

        return PhysioSnapshot(
            timeMillis = nowMillis,
            data = PhysioData(
                heartRateBpm  = hr,
                skinTempF     = skinT,
                ageYears      = ageYears,
                weightKg      = weightKg,
                restingHrBpm  = restingHr,
                exertion      = exertion,
                exertionClass = exertionClass,
                dailyMetrics  = daily
            )
        )
    }

    data class DailyMetrics(
        val timestampHour: Instant,
        val steps: Long       = 0L,
        val calories: Double  = 0.0,
        val floors: Double    = 0.0,
        val elevationM: Double = 0.0
    )

    private val _latestDailyMetrics = MutableStateFlow<DailyMetrics?>(null)
    val latestDailyMetrics: StateFlow<DailyMetrics?> = _latestDailyMetrics

    fun updateDailyMetrics(
        timestampHour: Instant,
        steps: Long?,
        calories: Double?,
        floors: Double?,
        elevationM: Double?
    ) {
        val prev = _latestDailyMetrics.value
        _latestDailyMetrics.value = DailyMetrics(
            timestampHour = timestampHour,
            steps        = steps       ?: prev?.steps       ?: 0L,
            calories     = calories    ?: prev?.calories    ?: 0.0,
            floors       = floors      ?: prev?.floors      ?: 0.0,
            elevationM   = elevationM  ?: prev?.elevationM  ?: 0.0
        )
    }

    data class SystemSnapshot(
        val timeMillis: Long,       // When the snapshot was taken
        val batteryPercent: Int?,   // nullable because sensors can fail or race
        val callsign: String,       // always available from DataStore
        val deviceId: String,       // MainActivity.device_uid
        val wearTakVersion: String, // gradle build vers #
        val reportIntSecs: Int      // pli reporting rate
    )

    suspend fun snapshotSystem(): SystemSnapshot {
        val now = System.currentTimeMillis()

        val battery  = latestBatteryPercentage.value
        val callsign = getCallsign.first()
        val deviceId = MainActivity.device_uid
        val version  = MainActivity.versionNumber
        val interval = reportingUpdateIntervalSecs.value

        return SystemSnapshot(
            timeMillis = now,
            batteryPercent = battery,
            callsign = callsign,
            deviceId = deviceId,
            wearTakVersion = version,
            reportIntSecs = interval
        )
    }

    data class MarkerSnapshot(
        val uid: String,
        val markerType: MarkerType,
        val iconSetPath: String?,
        val loc: LocationSnapshot?,
        val time: Utils.CotEventTimeSnapshot,
        val physio: PhysioSnapshot?,
        val system: SystemSnapshot
    )

    suspend fun snapshotMarker(req: DropRequest): MarkerSnapshot? {
        val loc = snapshotLocation() ?: return null
        val timeSnapshot = Utils.getCotEventTimeSnapshot(
            startOffset = Utils.TimeOffset(),
            staleOffset = Utils.TimeOffset(years = 1)
        )

        val physioSnapshot = snapshotPhysio()
        val systemSnapshot = snapshotSystem()

        return MarkerSnapshot(
            uid = UUID.randomUUID().toString(),
            markerType = req.markerType,
            iconSetPath = req.markerType.icon,
            loc = loc,
            time = timeSnapshot,
            physio = physioSnapshot,
            system = systemSnapshot
        )
    }

    enum class AlertState {
        ALERT,
        CANCEL
    }

    data class EmergencyEventSnapshot(
        val uid: String,                     // stable per alert thread
        val alertState: AlertState,          // ALERT or CANCEL
        val category: String,
        val description: String,
        val sendSms: Boolean,

        val time: Utils.CotEventTimeSnapshot,
        val location: LocationSnapshot,
        val physio: PhysioSnapshot?,
        val system: SystemSnapshot
    )

    private var sosAlertUid: String? = null

    suspend fun snapshotEmergencyEvent(
        category: String,
        description: String,
        sendSms: Boolean,
        alertState: AlertState
    ): EmergencyEventSnapshot? {
        val loc = latestLocation.value
        if (loc == null) {
            // Let caller decide what to do (set manualSosToBeSent, etc.)
            return null
        }

        // UID logic: new for ALERT, reuse for CANCEL
        val uid = if (alertState == AlertState.ALERT) {
            val fresh = UUID.randomUUID().toString()
            sosAlertUid = fresh
            fresh
        } else {
            sosAlertUid ?: run {
                // No previous alert -> nothing to cancel; you can log if you want
                return null
            }
        }

        val timeSnap = Utils.getCotEventTimeSnapshot(
            startOffset = Utils.TimeOffset(),
            staleOffset = Utils.TimeOffset(minutes = 15) // or whatever SOS stale you want
        )

        val locSnap = snapshotLocation() ?: return null
        val systemSnap = snapshotSystem()
        val physioSnap = snapshotPhysio()

        return EmergencyEventSnapshot(
            uid         = uid,
            alertState  = alertState,
            category    = category,
            description = description,
            sendSms     = sendSms,
            time        = timeSnap,
            location    = locSnap,
            physio      = physioSnap,
            system      = systemSnap
        )
    }


    data class PliSnapshot(
        val loc: LocationSnapshot?,
        val time: Utils.CotEventTimeSnapshot,
        val physioData: PhysioSnapshot,
        val systemData: SystemSnapshot
    )

    suspend fun snapshotPli(): PliSnapshot {
        val timeSnap = Utils.getCotEventTimeSnapshot(
            startOffset = Utils.TimeOffset(),
            staleOffset = Utils.TimeOffset(seconds = (3 * almightyReportingIntervalSecs.value))
        )

        return PliSnapshot(
            loc = snapshotLocation(),
            time = timeSnap,
            physioData = snapshotPhysio(),
            systemData = snapshotSystem()
        )
    }

    private suspend fun postCustomEmergencyEvent(event: CustomEmergencyEvent) {
        _customEmergencyEvent.emit(event)
    }

    private fun removeCustomEmergencyEvent(eventUid: CustomEmergencyEvent) {

    }

    private suspend fun activateSos() {
        val event = CustomEmergencyEvent(CAT_MANUAL, DESC_SOS_ALERT, 1, true)
        postCustomEmergencyEvent(event)
    }

    private suspend fun deActivateSos() {
        val event = CustomEmergencyEvent(CAT_MANUAL, DESC_SOS_ALERT, 1, true)
        postCustomEmergencyEvent(event)
    }

    private val _customEmergencyEvent = MutableSharedFlow<CustomEmergencyEvent>(extraBufferCapacity = 64)
    val customEmergencyEvent: SharedFlow<CustomEmergencyEvent> = _customEmergencyEvent

    data class DroppedMarker(
        val uid: String,
        val markerType: String,
        val timeDropped: Long
    )

    private val _droppedMarkerList = MutableStateFlow<List<DroppedMarker>>(emptyList())
    val droppedMarkerList: StateFlow<List<DroppedMarker>> = _droppedMarkerList

    // Backend-only mutators (UI never calls these)
    suspend fun appendDroppedMarker(item: DroppedMarker) {
        _droppedMarkerList.emit(_droppedMarkerList.value + item)
    }

    suspend fun removeByUid(uid: String) {
        _droppedMarkerList.emit(_droppedMarkerList.value.filterNot { it.uid == uid })
    }

    suspend fun storeDroppedMarkerList(droppedMarkerList: List<DroppedMarker>) {
        _droppedMarkerList.emit(droppedMarkerList)
    }

    enum class MarkerType(
        val type: String,
        val icon: String
    ) {
        FRIENDLY("a-f-G", "COT_MAPPING_2525C/a-f/a-f-G"),
        UNKNOWN("a-u-G", "COT_MAPPING_2525C/a-u/a-u-G"),
        HOSTILE("a-h-G", "COT_MAPPING_2525C/a-h/a-h-G"),
        NEUTRAL("a-n-G", "COT_MAPPING_2525C/a-n/a-n-G");

        companion object {
            fun parse(input: String): MarkerType? {
                val s = input.trim()
                return entries.firstOrNull {
                    it.name.equals(s, ignoreCase = true) ||   // allow FRIENDLY / HOSTILE / …
                            it.type.equals(s, ignoreCase = true)      // allow a-f-G / a-h-G / …
                }
            }
        }
    }

    // --- request flows (UI -> backend) ---
    data class DropRequest(
        val markerType: MarkerType,
        val timeMillis: Long
    )

    sealed interface ClearRequest {
        data class Specific(val uid: String) : ClearRequest
        data object Last : ClearRequest
        data object All : ClearRequest
    }

    private val _dropRequests = MutableSharedFlow<DropRequest>(extraBufferCapacity = 64)
    val dropRequests: SharedFlow<DropRequest> = _dropRequests

    private val _clearRequests = MutableSharedFlow<ClearRequest>(extraBufferCapacity = 64)
    val clearRequests: SharedFlow<ClearRequest> = _clearRequests

    // UI-facing API
    suspend fun requestMarkerDrop(markerType: MarkerType, timeMillis: Long) =
        _dropRequests.emit(DropRequest(markerType, timeMillis))

    suspend fun requestMarkerDrop(markerTypeRaw: String, timeMillis: Long): Boolean {
        val parsed = MarkerType.parse(markerTypeRaw) ?: return false // caller can handle false
        _dropRequests.emit(DropRequest(parsed, timeMillis))
        return true
    }

    suspend fun requestClearSpecific(uid: String) =
        _clearRequests.emit(ClearRequest.Specific(uid))
    suspend fun requestClearLast() = _clearRequests.emit(ClearRequest.Last)
    suspend fun requestClearAll() = _clearRequests.emit(ClearRequest.All)

    suspend fun storeLatestHeartRate(heartRate: Int) {
        _latestHeartRate.emit(heartRate)
    }

    suspend fun storeLatestActivityRecognized(activity: ActivityRecognized) {
        _latestActivityRecognized.emit(activity)
    }

    suspend fun storeLatestActivityConfidence(confidence: Int) {
        _latestActivityConfidence.emit(confidence)
    }

    suspend fun storeIsAlerting(alerting: Boolean) {
        _isAlerting.emit(alerting)
    }

    suspend fun storeLatestSkinTemp(skinTemp: Float) {
        _latestSkinTemp.emit(skinTemp)
    }

    suspend fun storeLatestLocation(location: Location) {
        _latestLocation.emit(location)
    }

    suspend fun storeLatestBatteryPercentage(batteryPercentage: Int) {
        _latestBatteryPercentage.emit(batteryPercentage)
    }

    suspend fun storeLatestIsDeviceWorn(isDeviceWorn: Boolean) {
        _latestIsDeviceWorn.emit(isDeviceWorn)
    }

    val getCallsign: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CALLSIGN] ?: ""
    }

    suspend fun storeCallsign(callsign: String) {
        context.dataStore.edit { prefs ->
            prefs[CALLSIGN] = callsign
        }
    }

    suspend fun storeTeamNames(teamNames: String) {
        context.dataStore.edit { prefs ->
            prefs[TEAM_NAMES] = teamNames
        }
    }

    val getTeamNames: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TEAM_NAMES] ?: "White,Yellow,Orange,Magenta,Red,Maroon,Purple,Dark Blue,Blue,Cyan,Teal,Green,Dark Green,Brown"
    }

    suspend fun storeTeamColors(teamColors: String) {
        context.dataStore.edit { prefs ->
            prefs[TEAM_COLORS] = teamColors
        }
    }

    val getTeamColors: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TEAM_COLORS] ?: "White,Yellow,Orange,Magenta,Red,Maroon,Purple,Dark Blue,Blue,Cyan,Teal,Green,Dark Green,Brown"
    }

    private val listType = object : TypeToken<List<CategoryRolesEntry>>() {}.type

    suspend fun storeCategoryRolesList(entries: List<CategoryRolesEntry>) {
        context.dataStore.edit { prefs ->
            prefs[CATEGORY_ROLES_ENTRIES_JSON] = gson.toJson(entries, listType)
        }
    }

    val getCategoryRolesList: Flow<List<CategoryRolesEntry>> =
        context.dataStore.data.map { prefs ->
            val jsonString = prefs[CATEGORY_ROLES_ENTRIES_JSON]
            if (jsonString.isNullOrEmpty()) {
                emptyList()
            } else {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<CategoryRolesEntry>>() {}
                val type = typeToken.type
                gson.fromJson<List<CategoryRolesEntry>>(jsonString, type)
            }
        }

    suspend fun storeRoleCategory(category: String) =
        context.dataStore.edit { prefs ->
            prefs[SELECTED_CATEGORY] = category
        }

    val getRoleCategory: Flow<String> =
        context.dataStore.data.map {prefs ->
            prefs[SELECTED_CATEGORY] ?: "MIL"
        }

    fun getRolesForCategory(cat: String): Flow<List<String>> =
        getCategoryRolesList.map { list ->
            list.firstOrNull { it.category == cat }?.roles ?: emptyList()
        }

//    suspend fun storeRolesList(rolesList: String) {
//        context.dataStore.edit { prefs ->
//            prefs[ROLES_LIST] = rolesList
//        }
//    }
//
//    val getRolesList: Flow<String> = context.dataStore.data.map { prefs ->
//        prefs[ROLES_LIST] ?: "Team Member,Team Lead,HQ,Sniper,Medic,Forward Observer,RTO,K9"
//    }

    val getTeam: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TEAM] ?: "Orange"
    }

    suspend fun storeTeam(team: String) {
        context.dataStore.edit { prefs ->
            prefs[TEAM] = team
        }
    }

    val getRole: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SELECTED_ROLE] ?: "Team Member"
    }

    suspend fun storeRole(role: String) {
        context.dataStore.edit { prefs ->
            prefs[SELECTED_ROLE] = role
        }
    }

    val getLicense: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[LICENSE] ?: "License"
    }

    suspend fun storeLicense(license: String) {
        Log.i(TAG, " ********************* storeLicense Called changing from ${getLicense.first()} → $license")
        context.dataStore.edit { prefs ->
            prefs[LICENSE] = license
        }
    }

    suspend fun storeDittoAppId(appId: String) {
        context.dataStore.edit { prefs ->
            prefs[DITTO_APP_ID] = appId
        }
    }

    val getDittoAppId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DITTO_APP_ID] ?: "d0a0ed19-be6f-43ed-bb70-ff95653c98b7"
    }

    suspend fun storeDittoAuthToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[DITTO_AUTH_TOKEN] = token
        }
    }

    val getDittoAuthToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DITTO_AUTH_TOKEN] ?: "8b7efdee-5a96-409e-842c-1ed48045de24"
    }

    suspend fun storeCustomDeveloperConfigJson(jsonString: String) {
        context.dataStore.edit { prefs ->
            prefs[CUSTOM_DEVELOPER_CONFIG_JSON] = jsonString
        }
    }

    val getCustomDeveloperConfigJson: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_DEVELOPER_CONFIG_JSON] ?: "{}"
    }

    val getEnableLocationService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_LOCATION_SERVICE] ?: true
    }

    suspend fun storeEnableLocationService(enableLocationService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_LOCATION_SERVICE] = enableLocationService
        }
    }

    val getEnablePhysiologicalServices: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_PHYSIOLOGICAL_SERVICES] ?: true
    }

    suspend fun storeEnablePhysiologicalServices(enablePhysiologicalServices: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_PHYSIOLOGICAL_SERVICES] = enablePhysiologicalServices
        }
    }

    val getEnableActRecService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_ACTIVITY_RECOGNITION_SERVICE] ?: false
    }

    suspend fun storeEnableActivityRecognitionService(enableActivityRecognitionService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_ACTIVITY_RECOGNITION_SERVICE] = enableActivityRecognitionService
        }
    }

    val getEnableSkinTempService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_SKIN_TEMP_SERVICE] ?: true
    }

    suspend fun storeEnableSkinTempService(enableSkinTempService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_SKIN_TEMP_SERVICE] = enableSkinTempService
        }
    }

    val getEnableHeartRateService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_HEART_RATE_SERVICE] ?: true
    }

    suspend fun storeEnableHeartRateService(enableHeartRateService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_HEART_RATE_SERVICE] = enableHeartRateService
        }
    }

    suspend fun storeEnablePhysioServices(enablePhysioService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_PHYSIOLOGICAL_SERVICES]    = enablePhysioService
//            prefs[ENABLE_SKIN_TEMP_SERVICE]  = enablePhysioService
//            prefs[ENABLE_HEART_RATE_SERVICE] = enablePhysioService
//            prefs[ENABLE_ACTIVITY_RECOGNITION_SERVICE] = enablePhysioService
        }
    }

    val getEnableDittoService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_DITTO_SERVICE] ?: false
    }

    suspend fun storeEnableDittoService(enableDittoService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_DITTO_SERVICE] = enableDittoService
        }
    }

    val getEnableChatService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_CHAT_SERVICE] ?: false
    }

    suspend fun storeEnableChatService(enableChatService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_CHAT_SERVICE] = enableChatService
        }
    }

    private val _almightyReportingIntervalSecs =
        MutableStateFlow(DEFAULT_REPORTING_INTERVAL)

    val almightyReportingIntervalSecs: StateFlow<Int> =
        _almightyReportingIntervalSecs.asStateFlow()

    fun setAlmightyReportingInterval(intervalInSecs: Int) {
        if (_almightyReportingIntervalSecs.value == intervalInSecs) return
        _almightyReportingIntervalSecs.value = intervalInSecs
    }

    private val _reportingUpdateIntervalSecs = MutableStateFlow<Int>(DEFAULT_REPORTING_INTERVAL)
    val reportingUpdateIntervalSecs: StateFlow<Int> = _reportingUpdateIntervalSecs

    suspend fun storeReportingUpdateInterval(reportingUpdateInterval: Int) {
        _reportingUpdateIntervalSecs.emit(reportingUpdateInterval)
    }

    /*
     *  tracks the wifi connection state to start or stop multicasting.
     */
    var isWifiConnected = MutableStateFlow<Boolean>(false)

    val getEnableWifiBatteryOptimization: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_WIFI_BATTERY_OPTIMIZATION] ?: true
    }

    suspend fun storeEnableWifiBatteryOptimization(enableWifiBatteryOptimization: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_WIFI_BATTERY_OPTIMIZATION] = enableWifiBatteryOptimization
        }
    }

    val getEnableAllWifiBatteryOptimization: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION] ?: true
    }

    suspend fun storeEnableAllWifiBatteryOptimization(enableAllWifiBatteryOptimization: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION] = enableAllWifiBatteryOptimization
        }
    }

    data class WifiSsidItem(val name: String)
    val gson = Gson()
    val getWifiSsidList: Flow<List<WifiSsidItem>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[WIFI_SSID_LIST_KEY]
            if (jsonString.isNullOrEmpty()) {
                emptyList()
            } else {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<WifiSsidItem>>() {}
                val type = typeToken.type
                gson.fromJson<List<WifiSsidItem>>(jsonString, type)
            }
        }

    suspend fun storeWifiSsidList(newList: List<WifiSsidItem>) {
        context.dataStore.edit { preferences ->
            // Convert list to JSON
            val jsonString = gson.toJson(newList)
            // Store in DataStore
            preferences[WIFI_SSID_LIST_KEY] = jsonString
        }
    }

    /*
    suspend fun addWifiSsidItem(item: WifiSsidItem) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[WIFI_SSID_LIST_KEY]
            Log.d(TAG, "addWifiSsidItem: $item, currJsonList: ${currentJson.toString()}")
            val currentList: MutableList<WifiSsidItem> = if (!currentJson.isNullOrEmpty()) {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<WifiSsidItem>>() {}
                val type = typeToken.type
                gson.fromJson(currentJson, type)
            } else {
                mutableListOf()
            }

            currentList.add(item)

            val newJson = gson.toJson(currentList)
            preferences[WIFI_SSID_LIST_KEY] = newJson
            Log.d(TAG, "addeWifiSsidItem newJson: $newJson")
        }
    }

    suspend fun removeWifiSsidItem(item: WifiSsidItem) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[WIFI_SSID_LIST_KEY]
            Log.d(TAG, "removeWifiSsidItem: $item, currJsonList: ${currentJson.toString()}")
            val currentList: MutableList<WifiSsidItem> = if (!currentJson.isNullOrEmpty()) {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<WifiSsidItem>>() {}
                val type = typeToken.type
                gson.fromJson(currentJson, type)
            } else {
                mutableListOf()
            }

            currentList.remove(item)

            preferences[WIFI_SSID_LIST_KEY] = gson.toJson(currentList)
            Log.d(TAG, "removeWifiSsidItem newJson: ${gson.toJson(currentList)}")
        }
    }

    suspend fun updateWifiSsidItem(oldItemIndex: Int, newItem: WifiSsidItem) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[WIFI_SSID_LIST_KEY]
            Log.d(TAG, "updateWifiSsidItem oldIndex($oldItemIndex), newItem($newItem), currJsonList: ${currentJson.toString()}")
            val currentList: MutableList<WifiSsidItem> = if (!currentJson.isNullOrEmpty()) {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<WifiSsidItem>>() {}
                val type = typeToken.type
                gson.fromJson(currentJson, type)
            } else {
                mutableListOf()
            }

            if (oldItemIndex != -1) {
                currentList[oldItemIndex] = newItem
            }

            val newJson = gson.toJson(currentList)
            preferences[WIFI_SSID_LIST_KEY] = newJson
            Log.d(TAG, "updateWifiSsidItem newJson: $newJson")
            preferences[WIFI_SSID_LIST_KEY] = gson.toJson(currentList)
        }
    }
     */

    data class ChatEventSnapshot(
        val uid: String,                     // stable per chat message
        val roomUid: String,                // e.g. "All Chat Rooms" or actual room id
        val roomTitle: String,              // human label for the room
        val message: String,                // chat text

        val time: Utils.CotEventTimeSnapshot,
        val location: LocationSnapshot?,    // might be null if you want to allow it
        val physio: PhysioSnapshot?,
        val system: SystemSnapshot
    )

    suspend fun snapshotChatEvent(
        roomUid: String,
        roomTitle: String,
        message: String
    ): ChatEventSnapshot? {
        val locSnap = snapshotLocation()
        if (locSnap == null) {
            Log.w("WearTAK-Chat", "snapshotChatEvent: snapshotLocation() is null, returning null")
            return null
        }

        val timeSnap = Utils.getCotEventTimeSnapshot(
            startOffset = Utils.TimeOffset(),
            staleOffset = Utils.TimeOffset(minutes = 15) // or shorter if you want
        )

        val systemSnap = snapshotSystem()
        val physioSnap = snapshotPhysio()
        val msgUid = UUID.randomUUID().toString()

        return ChatEventSnapshot(
            uid        = msgUid,
            roomUid    = roomUid,
            roomTitle  = roomTitle,
            message    = message,
            time       = timeSnap,
            location   = locSnap,
            physio     = physioSnap,
            system     = systemSnap
        )
    }

    var newMessagesPresentPublicBoolean = MutableLiveData<Boolean>(false)

    private val _chatRoomsFlow = MutableStateFlow<List<Utils.ChatRoom>>(emptyList())
    val chatRoomsFlow: StateFlow<List<Utils.ChatRoom>> = _chatRoomsFlow

    /* ─── unread indicator ──────────────────────────────────────────────────── */
    private val _unreadCount      = MutableStateFlow(0)
    private val _hasUnread        = MutableStateFlow(false)

    val unreadCount : StateFlow<Int>  = _unreadCount        // if you need the exact number
    val hasUnread   : StateFlow<Boolean> = _hasUnread        // true/false for the badge

    suspend fun refreshUnreadCount() {
        val n = ChatService.getInstance()?.getUnreadMsgsCount() ?: 0
        _unreadCount.emit(n)
        _hasUnread.emit(n > 0)
    }

    fun setChatRooms(rooms: List<Utils.ChatRoom>) {
        _chatRoomsFlow.value = rooms
        val anyUnread = rooms.any { it.newMessagesPresent }
        newMessagesPresentPublicBoolean.postValue(anyUnread)
    }

    suspend fun markRoomRead(roomId: String) {
        ChatService.markRoomReadStatic(roomId)
        val fresh = ChatService.getInstance()?.getInbox() ?: emptyList()
        _chatRoomsFlow.emit(fresh)

        // update indicator
        _unreadCount.emit(fresh.count { it.newMessagesPresent })
        _hasUnread.emit(fresh.any  { it.newMessagesPresent })
    }

    enum class Endpoint { TAK_SERVER, DITTO, COMPANION_APP }

    private val _endpointConnected = MutableStateFlow<Set<Endpoint>>(emptySet())
    val endpointConnected: StateFlow<Set<Endpoint>> = _endpointConnected

    val anyEndpointConnected: StateFlow<Boolean> =
        endpointConnected.map { it.isNotEmpty() }
            .stateIn( scope = repoScope , SharingStarted.Eagerly, false)

    fun setEndpointConnected(endpoint: Endpoint) {
        _endpointConnected.update { it + endpoint }
    }

    fun setEndpointDisconnected(endpoint: Endpoint) {
        _endpointConnected.update { it - endpoint }
    }

    fun isEndpointConnected(endpoint: Endpoint): StateFlow<Boolean> =
        endpointConnected.map { endpoint in it }
            .stateIn(scope = repoScope, SharingStarted.Eagerly, false)


    /**
     *  Returns the current unread-message total, 0 if the service isn’t running.
     */
    suspend fun getUnreadMsgsCount(): Int =
        ChatService.getInstance()?.getUnreadMsgsCount() ?: 0

    val getEnableTakEndpoint: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_TAK_ENDPOINT] ?: true
    }

    suspend fun storeEnableTakEndpoint(enableTakEndpoint: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_TAK_ENDPOINT] = enableTakEndpoint
        }
    }

    val getEnableEsriEndpoint: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_ESRI_ENDPOINT] ?: false
    }

    suspend fun storeEnableEsriEndpoint(enableEsriEndpoint: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_ESRI_ENDPOINT] = enableEsriEndpoint
        }
    }

    val getEsriEndpoint: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[ESRI_ENDPOINT] ?: ""
    }

    suspend fun storeEsriEndpoint(esriEndpoint: String) {
        context.dataStore.edit { prefs ->
            prefs[ESRI_ENDPOINT] = esriEndpoint
        }
    }

    val getEsriReportingUpdateInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[ESRI_REPORTING_UPDATE_INTERVAL] ?: DEFAULT_REPORTING_INTERVAL
    }

    suspend fun storeEsriReportingUpdateInterval(esriReportingUpdateInterval: Int) {
        context.dataStore.edit { prefs ->
            prefs[ESRI_REPORTING_UPDATE_INTERVAL] = esriReportingUpdateInterval
        }
    }

    val getEnableTabletCommandEndpoint: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_TABLET_COMMAND_ENDPOINT] ?: false
    }

    suspend fun storeEnableTabletCommandEndpoint(enableTabletCommandEndpoint: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_TABLET_COMMAND_ENDPOINT] = enableTabletCommandEndpoint
        }
    }

    val getTabletCommandEndpoint: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TABLET_COMMAND_ENDPOINT] ?: ""
    }

    suspend fun storeTabletCommandEndpoint(tabletCommandEndpoint: String) {
        context.dataStore.edit { prefs ->
            prefs[TABLET_COMMAND_ENDPOINT] = tabletCommandEndpoint
        }
    }

    val getTabletCommandApikey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TABLET_COMMAND_APIKEY] ?: ""
    }

    suspend fun storeTabletCommandApikey(tabletCommandApikey: String) {
        context.dataStore.edit { prefs ->
            prefs[TABLET_COMMAND_APIKEY] = tabletCommandApikey
        }
    }


    val getTabletCommandReportingUpdateInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[TABLET_COMMAND_REPORTING_UPDATE_INTERVAL] ?: DEFAULT_REPORTING_INTERVAL
    }

    suspend fun storeTabletCommandReportingUpdateInterval(tabletCommandReportingUpdateInterval: Int) {
        context.dataStore.edit { prefs ->
            prefs[TABLET_COMMAND_REPORTING_UPDATE_INTERVAL] = tabletCommandReportingUpdateInterval
        }
    }


    data class TakServerItem(
        var isEnabled: Boolean,
        val name: String,
        val address: String,
        val port: Int,
        val isP12Cert: Boolean,
        val username: String,
        val password: String,
        val p12Cert: String,
        val p12CertPassword: String
    )
    val getTakServerList: Flow<List<TakServerItem>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[TAK_SERVER_LIST_KEY]
            if (jsonString.isNullOrEmpty()) {
                emptyList()
            } else {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<TakServerItem>>() {}
                val type = typeToken.type
                gson.fromJson<List<TakServerItem>>(jsonString, type)
            }
        }

    suspend fun storeTakServerList(newList: List<TakServerItem>) {
        context.dataStore.edit { preferences ->
            // Convert list to JSON
            val jsonString = gson.toJson(newList)
            // Store in DataStore
            preferences[TAK_SERVER_LIST_KEY] = jsonString
        }
    }

    suspend fun addTakServerItem(item: TakServerItem) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[TAK_SERVER_LIST_KEY]
            Log.d(TAG, "addTakServerItem: $item, currJsonList: ${currentJson.toString()}")
            val currentList: MutableList<TakServerItem> = if (!currentJson.isNullOrEmpty()) {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<TakServerItem>>() {}
                val type = typeToken.type
                gson.fromJson(currentJson, type)
            } else {
                mutableListOf()
            }
            currentList.add(item)

            val newJson = gson.toJson(currentList)
            preferences[TAK_SERVER_LIST_KEY] = newJson
            Log.d(TAG, "addTakServerItem newJson: $newJson")
        }
    }

    suspend fun removeTakServerItem(item: TakServerItem) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[TAK_SERVER_LIST_KEY]
            Log.d(TAG, "removeTakServerItem: $item, currJsonList: ${currentJson.toString()}")
            val currentList: MutableList<TakServerItem> = if (!currentJson.isNullOrEmpty()) {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<TakServerItem>>() {}
                val type = typeToken.type
                gson.fromJson(currentJson, type)
            } else {
                mutableListOf()
            }

            currentList.remove(item)

            preferences[TAK_SERVER_LIST_KEY] = gson.toJson(currentList)
            Log.d(TAG, "removeTakServerItem newJson: ${gson.toJson(currentList)}")
        }
    }

    // updateTakServerItem will act as addTakServerItem if oldItemIndex is greater than or equal to size of already stored list
    suspend fun updateTakServerItem(oldItemIndex: Int, newItem: TakServerItem) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[TAK_SERVER_LIST_KEY]
            Log.d(TAG, "updateTakServerItem oldIndex($oldItemIndex), newItem($newItem), currJsonList: ${currentJson.toString()}")
            val currentList: MutableList<TakServerItem> = if (!currentJson.isNullOrEmpty()) {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<TakServerItem>>() {}
                val type = typeToken.type
                gson.fromJson(currentJson, type)
            } else {
                mutableListOf()
            }

            val currentListSize = currentList.size
            if (oldItemIndex != -1) {
                if (oldItemIndex < currentListSize) {
                    currentList[oldItemIndex] = newItem
                } else {
                    currentList.add(newItem)
                    if (oldItemIndex != currentListSize) {
                        Log.w(TAG, "updateTakServerItem: WARNING index($oldItemIndex) passed is greater than stored list size($currentListSize)")
                    }
                }
            }

            val newJson = gson.toJson(currentList)
            preferences[TAK_SERVER_LIST_KEY] = newJson
            Log.d(TAG, "updateTakServerItem newJson: $newJson")
            preferences[TAK_SERVER_LIST_KEY] = gson.toJson(currentList)
        }
    }

    private val _enableTakSockets = MutableStateFlow<Boolean?>(null) // TODO: Do we initialize with null OR from getEnableDynamicReportingStrategy
    val enableTakSockets: StateFlow<Boolean?> = _enableTakSockets

    suspend fun closeTakSockets() {
        _enableTakSockets.emit(false)
    }

    suspend fun openTakSockets() {
        _enableTakSockets.emit(true)
    }

    val getEnableBleBeaconService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_BLE_BEACON_SERVICE] ?: false
    }

    suspend fun storeEnableBleBeaconService(enableBleBeaconService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_BLE_BEACON_SERVICE] = enableBleBeaconService
        }
    }

    val getEnableCompanionAppService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_COMPANION_APP_SERVICE] ?: true
    }

    suspend fun storeEnableCompanionAppService(enableCompanionAppService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_COMPANION_APP_SERVICE] = enableCompanionAppService
        }
    }

    val getEnableWearTakDevMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_WEARTAK_DEV_MODE] ?: false
    }

    // NOTE: Store as false on every app boot!
    suspend fun storeEnableWearTakDevMode(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_WEARTAK_DEV_MODE] = enable
        }
    }

    data class LocalizationBleBeaconItem(
        val uuid: String,
        val major: Int,
        val minor: Int,
        val lat: Double,
        val lon: Double,
        val alt: Double
    )
    val getLocalizationBleBeaconItemList: Flow<List<LocalizationBleBeaconItem>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[LOCALIZATION_BLE_BEACON_LIST_KEY]
            if (jsonString.isNullOrEmpty()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<LocalizationBleBeaconItem>>() {}.type
                gson.fromJson<List<LocalizationBleBeaconItem>>(jsonString, type)
            }
        }

    suspend fun storeLocalizationBleBeaconList(newList: List<LocalizationBleBeaconItem>) {
        context.dataStore.edit { preferences ->
            // Convert list to JSON
            val jsonString = gson.toJson(newList)
            // Store in DataStore
            preferences[LOCALIZATION_BLE_BEACON_LIST_KEY] = jsonString
        }
    }

    private val _enableDynamicReportingStrategy = MutableStateFlow<Boolean?>(null) // TODO: Do we initialize with null OR from getEnableDynamicReportingStrategy
    val enableDynamicReportingStrategy: StateFlow<Boolean?> = _enableDynamicReportingStrategy

    val getEnableDynamicReportingStrategy: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_DYNAMIC_REPORTING_STRATEGY] ?: false
    }

    suspend fun storeEnableDynamicReportingStrategy(enableDynamicRS: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_DYNAMIC_REPORTING_STRATEGY] = enableDynamicRS
        }
        _enableDynamicReportingStrategy.emit(enableDynamicRS)
    }

    private val _constantReportingInterval = MutableStateFlow<Int?>(null)
    val constantReportingInterval: StateFlow<Int?> = _constantReportingInterval

    val androidDeviceUid: String = MainActivity.device_uid

    val getConstantReportingInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[CONSTANT_REPORTING_INTERVAL] ?: DEFAULT_REPORTING_INTERVAL
    }

    suspend fun storeConstantReportingInterval(constantReportingInterval: Int) {
        context.dataStore.edit { prefs ->
            prefs[CONSTANT_REPORTING_INTERVAL] = constantReportingInterval
        }
        _constantReportingInterval.emit(constantReportingInterval)
    }

    private val _dynamicReportingInterval = MutableStateFlow<Int?>(null)
    val dynamicReportingInterval: StateFlow<Int?> = _dynamicReportingInterval

    suspend fun storeDynamicReportingInterval(interval: Int) {
        context.dataStore.edit { prefs ->
            prefs[DYNAMIC_REPORTING_INTERVAL] = interval
        }
        _dynamicReportingInterval.emit(interval)
    }

    private val _stationaryReportingInterval = MutableStateFlow<Int?>(null)
    val stationaryReportingInterval: StateFlow<Int?> = _stationaryReportingInterval

    val getStationaryReportingInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[STATIONARY_REPORTING_INTERVAL] ?: DEFAULT_STATIONARY_REPORTING_INTERVAL
    }

    suspend fun storeStationaryReportingInterval(interval: Int) {
        context.dataStore.edit { prefs ->
            prefs[STATIONARY_REPORTING_INTERVAL] = interval
        }
        Log.d(TAG, "storeStationaryReportingInterval: $interval")
        _stationaryReportingInterval.emit(interval)
    }

    private val _onFootReportingInterval = MutableStateFlow<Int?>(null)
    val onFootReportingInterval: StateFlow<Int?> = _onFootReportingInterval

    val getOnFootReportingInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[ON_FOOT_REPORTING_INTERVAL] ?: DEFAULT_ON_FOOT_REPORTING_INTERVAL
    }

    suspend fun storeOnFootReportingInterval(interval: Int) {
        context.dataStore.edit { prefs ->
            prefs[ON_FOOT_REPORTING_INTERVAL] = interval
        }
        _onFootReportingInterval.emit(interval)
    }

    private val _vehicleReportingInterval = MutableStateFlow<Int?>(null)
    val vehicleReportingInterval: StateFlow<Int?> = _vehicleReportingInterval

    val getVehicleReportingInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[VEHICLE_REPORTING_INTERVAL] ?: DEFAULT_VEHICLE_REPORTING_INTERVAL
    }

    suspend fun storeVehicleReportingInterval(interval: Int) {
        context.dataStore.edit { prefs ->
            prefs[VEHICLE_REPORTING_INTERVAL] = interval
        }
        _vehicleReportingInterval.emit(interval)
    }

    private val _whileAlertingReportingInterval = MutableStateFlow<Int?>(null)
    val whileAlertingReportingInterval: StateFlow<Int?> = _whileAlertingReportingInterval

    val getWhileAlertingReportingInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[WHILE_ALERTING_REPORTING_INTERVAL] ?: DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL
    }

    suspend fun storeWhileAlertingReportingInterval(interval: Int) {
        context.dataStore.edit { prefs ->
            prefs[WHILE_ALERTING_REPORTING_INTERVAL] = interval
        }
        _whileAlertingReportingInterval.emit(interval)
    }

    val getEnableBatteryAlerts: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_BATTERY_ALERTS] ?: false
    }

    suspend fun storeEnableBatteryAlerts(enableBatteryAlerts: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_BATTERY_ALERTS] = enableBatteryAlerts
        }
    }

    val getEnableSmsForAlerts: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_SMS_FOR_ALERTS] ?: false
    }

    suspend fun storeEnableSmsForAlerts(enableSmsForAlerts: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_SMS_FOR_ALERTS] = enableSmsForAlerts
        }
    }

    data class SmsNumber(val telephoneNumber: String)
    val getSmsNumbersList: Flow<List<SmsNumber>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[SMS_NUMBERS_LIST]
            if (jsonString.isNullOrEmpty()) {
                emptyList()
            } else {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<SmsNumber>>() {}
                val type = typeToken.type
                gson.fromJson<List<SmsNumber>>(jsonString, type)
            }
        }

    suspend fun storeSmsNumbersList(newList: List<SmsNumber>) {
        context.dataStore.edit { preferences ->
            // Convert list to JSON
            val jsonString = gson.toJson(newList)
            // Store in DataStore
            preferences[SMS_NUMBERS_LIST] = jsonString
        }
    }

    private val _bloodhoundItemsFlow = MutableStateFlow<List<Utils.BloodhoundItem>>(emptyList())
    val bloodhoundItemsFlow: StateFlow<List<Utils.BloodhoundItem>> = _bloodhoundItemsFlow

    fun addBloodhoundItemToFront(bloodhound: Utils.BloodhoundItem) {
        _bloodhoundItemsFlow.value = listOf(bloodhound) + _bloodhoundItemsFlow.value
    }

    //TODO WEAR-161: Is this the best metric to use here?
    fun removeBloodhound(bloodhoundStr: String) {
        _bloodhoundItemsFlow.value = _bloodhoundItemsFlow.value
            .filterNot { it.target == bloodhoundStr }
    }

//    private val _physioAlertsFlow = MutableStateFlow<List<Utils.PhysioAlert>>(emptyList())
    private val _physioAlertsFlow = MutableStateFlow<List<Utils.PhysioAlert>>(emptyList())
    val physioAlertsFlow: StateFlow<List<Utils.PhysioAlert>> = _physioAlertsFlow

    fun addPhysioAlertToFront(alert: Utils.PhysioAlert) {
        _physioAlertsFlow.value = listOf(alert) + _physioAlertsFlow.value
    }

    fun removePhysioAlerts(alertStr: String) {
        _physioAlertsFlow.value = _physioAlertsFlow.value
            .filterNot { it.alert == alertStr }
    }

    val getEnablePhysiologicalAlerts: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_PHYSIOLOGICAL_ALERTS] ?: false
    }

    suspend fun storeEnablePhysiologicalAlerts(enablePhysiologicalAlerts: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_PHYSIOLOGICAL_ALERTS] = enablePhysiologicalAlerts
        }
    }

    val getHrHighAlertBpmThreshold: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[HR_HIGH_ALERT_BPM_THRESHOLD] ?: 140
    }

    suspend fun storeHrHighAlertBpmThreshold(hrHighAlertBpmThreshold: Int) {
        context.dataStore.edit { prefs ->
            prefs[HR_HIGH_ALERT_BPM_THRESHOLD] = hrHighAlertBpmThreshold
        }
    }

    val getHrHighAlertNumSecsThreshold: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[HR_HIGH_ALERT_NUM_SECS_THRESHOLD] ?: 30
    }

    suspend fun storeHrHighAlertNumSecsThreshold(hrHighAlertNumSecsThreshold: Int) {
        context.dataStore.edit { prefs ->
            prefs[HR_HIGH_ALERT_NUM_SECS_THRESHOLD] = hrHighAlertNumSecsThreshold
        }
    }

    val getBatteryWarnPercentage : Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[BATT_WARN_PERCENT] ?: DEFAULT_BATT_WARNING_PERCENTAGE
    }

    suspend fun storeBatteryWarnPercentage(percent: Int) {
        context.dataStore.edit { prefs ->
            prefs[BATT_WARN_PERCENT] = percent
        }
    }

    val getBatteryAlertPercentage : Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[BATT_ALERT_PERCENT] ?: DEFAULT_BATT_ALERT_PERCENTAGE
    }

    suspend fun storeBatteryAlertPercentage(percent: Int) {
        context.dataStore.edit { prefs ->
            prefs[BATT_ALERT_PERCENT] = percent
        }
    }

    val getPhysiologicalMetricAge: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[AGE] ?: DEFAULT_AGE
    }

    suspend fun storePhysiologicalMetricAge(age: Int) {
        context.dataStore.edit { prefs ->
            prefs[AGE] = age
        }
    }

    val physiologicalMetricAge: StateFlow<Int> = getPhysiologicalMetricAge.stateIn(
        scope = repoScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = DEFAULT_AGE
    )

    val latestExertion: StateFlow<Double> = combine(
        latestHeartRate.filterNotNull(),
        physiologicalMetricAge.filterNotNull(),
        restingHeartRate.filterNotNull()
    ) { hr, age, rhr ->
        Utils.PhysioUtils.calculateExertion(hr, age, rhr)
    }
        .stateIn(
            scope = repoScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = -1.0
        )


    val getPhysiologicalMetricHeightFeet: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[HEIGHT_FEET] ?: 6
    }

    suspend fun storePhysiologicalMetricHeightFeet(heightfeet: Int) {
        context.dataStore.edit { prefs ->
            prefs[HEIGHT_FEET] = heightfeet
        }
    }

    val getPhysiologicalMetricHeightInches: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[HEIGHT_INCHES] ?: 0
    }

    suspend fun storePhysiologicalMetricHeightInches(heightinches: Int) {
        context.dataStore.edit { prefs ->
            prefs[HEIGHT_INCHES] = heightinches
        }
    }

    val getPhysiologicalMetricWeight: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[WEIGHT] ?: 180
    }

    suspend fun storePhysiologicalMetricWeight(weight: Int) {
        context.dataStore.edit { prefs ->
            prefs[WEIGHT] = weight
        }
    }

    val getPhysiologicalMetricSex: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SEX] ?: "N/A"
    }

    suspend fun storePhysiologicalMetricSex(sex: String) {
        context.dataStore.edit { prefs ->
            prefs[SEX] = sex
        }
    }

    val getPhysiologicalMetricBloodType: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[BLOOD_TYPE] ?: "N/A"
    }

    suspend fun storePhysiologicalMetricBloodType(bloodtype: String) {
        context.dataStore.edit { prefs ->
            prefs[BLOOD_TYPE] = bloodtype
        }
    }

    data class AllergyItem(val name: String)
    val getPhysiologicalMetricAllergyList: Flow<List<AllergyItem>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[ALLERGIES_KEY]
            if (jsonString.isNullOrEmpty()) {
                listOf(AllergyItem(name="N/A"))
            } else {
                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<AllergyItem>>() {}
                val type = typeToken.type
                gson.fromJson<List<AllergyItem>>(jsonString, type)
            }
        }

    suspend fun storePhysiologicalMetricAllergyList(newList: List<AllergyItem>) {
        context.dataStore.edit { preferences ->
            // Convert list to JSON
            val jsonString = gson.toJson(newList)
            // Store in DataStore
            preferences[ALLERGIES_KEY] = jsonString
        }
    }

    val getPhysiologicalMetricUserType: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[USER_TYPE] ?: "N/A"
    }

    suspend fun storePhysiologicalMetricUserType(usertype: String) {
        context.dataStore.edit { prefs ->
            prefs[USER_TYPE] = usertype
        }
    }

    companion object {
        private val PASSIVE_DATA_ENABLED = booleanPreferencesKey("passive_data_enabled")
//        private val LATEST_HEART_RATE = intPreferencesKey("latest_heart_rate")
        private val CALLSIGN = stringPreferencesKey("callsign")
        private val TEAM = stringPreferencesKey("team")
        private val TEAM_NAMES = stringPreferencesKey("team_names")
        private val TEAM_COLORS = stringPreferencesKey("team_colors")
        private val CATEGORY_ROLES_ENTRIES_JSON = stringPreferencesKey("category_roles_entries_json")
        private val SELECTED_CATEGORY     = stringPreferencesKey("selected_category")
        private val SELECTED_ROLE         = stringPreferencesKey("role")
        private val LICENSE = stringPreferencesKey("license")
        private val DITTO_APP_ID = stringPreferencesKey("ditto_app_id")
        private val DITTO_AUTH_TOKEN = stringPreferencesKey("ditto_auth_token")
        private val ENABLE_LOCATION_SERVICE = booleanPreferencesKey("enable_location_service")
        private val ENABLE_PHYSIOLOGICAL_SERVICES = booleanPreferencesKey("enable_physiological_services")
        private val ENABLE_SKIN_TEMP_SERVICE = booleanPreferencesKey("enable_skin_temp_service")
        private val ENABLE_HEART_RATE_SERVICE = booleanPreferencesKey("enable_heart_rate_service")
        private val ENABLE_ACTIVITY_RECOGNITION_SERVICE = booleanPreferencesKey("enable_skin_temp_service")
        private val ENABLE_DITTO_SERVICE = booleanPreferencesKey("enable_ditto_service")
        private val ENABLE_CHAT_SERVICE = booleanPreferencesKey("enable_chat_service")
        private val ENABLE_BATTERY_ALERTS = booleanPreferencesKey("enable_battery_alerts")
        private val ENABLE_BLOODHOUND_COMPASS_SERVICE = booleanPreferencesKey("enable_bloodhound_compass_service")
        private val LOCATION_UPDATE_INTERVAL = intPreferencesKey("location_update_interval")
        private val BIOMETRICS_SEND_INTERVAL = intPreferencesKey("biometrics_send_interval")
        private val ENABLE_WIFI_BATTERY_OPTIMIZATION = booleanPreferencesKey("enable_wifi_battery_optimization")
        private val ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION = booleanPreferencesKey("enable_all_wifi_battery_optimization")
        private val ENABLE_HAIL_SERVICE = booleanPreferencesKey("enable_hail_service")
        private val ENABLE_BATDOK_COT = booleanPreferencesKey("enable_batdok_cot")
        private val HAIL_LICENSE_INFO_JSON = stringPreferencesKey("hail_license_info_json_v1") // include suffix for forward-compat
        private val HAIL_LICENSE_STATE = stringPreferencesKey("hail_license_state")
        private val HAIL_COMBAT_ID = intPreferencesKey("hail_combat_id")
        private val ENABLE_MULTICAST = booleanPreferencesKey("enable_multicast")
        private val WIFI_SSID_LIST_KEY = stringPreferencesKey("wifi_ssid_list")
        private val TAK_SERVER_LIST_KEY = stringPreferencesKey("tak_server_list")
        private val ENABLE_BLE_BEACON_SERVICE = booleanPreferencesKey("enable_ble_beacon_service")
        private val LOCALIZATION_BLE_BEACON_LIST_KEY = stringPreferencesKey("localization_ble_beacon_list")
        private val CUSTOM_DEVELOPER_CONFIG_JSON = stringPreferencesKey("custom_developer_config_json")
        private val ENABLE_DYNAMIC_REPORTING_STRATEGY = booleanPreferencesKey("enable_dynamic_reporting_strategy")
        private val CONSTANT_REPORTING_INTERVAL = intPreferencesKey("constant_reporting_interval")
        private val DYNAMIC_REPORTING_INTERVAL = intPreferencesKey("dynamic_reporting_interval")
        private val STATIONARY_REPORTING_INTERVAL = intPreferencesKey("stationary_reporting_interval")
        private val ON_FOOT_REPORTING_INTERVAL = intPreferencesKey("on_foot_reporting_interval")
        private val VEHICLE_REPORTING_INTERVAL = intPreferencesKey("vehicle_reporting_interval")
        private val WHILE_ALERTING_REPORTING_INTERVAL = intPreferencesKey("while_alerting_reporting_interval")
        private val RESTING_HEART_RATE = intPreferencesKey("resting_heart_rate")
        private val ENABLE_SMS_FOR_ALERTS = booleanPreferencesKey("enable_sms_for_alerts")
        private val ENABLE_PHYSIOLOGICAL_ALERTS = booleanPreferencesKey("enable_physiological_alerts")
        private val ENABLE_COMPANION_APP_SERVICE = booleanPreferencesKey("enable_companion_app_service")
        private val ENABLE_WEARTAK_DEV_MODE = booleanPreferencesKey("enable_weartak_dev_mode")
        private val SMS_NUMBERS_LIST = stringPreferencesKey("sms_numbers_list")
        private val HR_HIGH_ALERT_BPM_THRESHOLD = intPreferencesKey("hr_high_alert_bpm_threshold")
        private val HR_HIGH_ALERT_NUM_SECS_THRESHOLD = intPreferencesKey("hr_high_alert_num_secs_threshold")
        private val BATT_WARN_PERCENT = intPreferencesKey("batt_warn_percent")
        private val BATT_ALERT_PERCENT = intPreferencesKey("batt_alert_percent")
        private val AGE = intPreferencesKey("age")
        private val HEIGHT_FEET = intPreferencesKey("height_feet")
        private val HEIGHT_INCHES = intPreferencesKey("height_inches")
        private val WEIGHT = intPreferencesKey("weight")
        private val SEX = stringPreferencesKey("sex")
        private val BLOOD_TYPE = stringPreferencesKey("blood_type")
        private val ALLERGIES_KEY = stringPreferencesKey("allergies")
        private val USER_TYPE = stringPreferencesKey("user_type")
        private val ENABLE_TAK_ENDPOINT = booleanPreferencesKey("enable_tak_endpoint")
        private val ENABLE_ESRI_ENDPOINT = booleanPreferencesKey("enable_esri_endpoint")
        private val ESRI_ENDPOINT = stringPreferencesKey("esri_endpoint")
        private val ESRI_REPORTING_UPDATE_INTERVAL = intPreferencesKey("esri_reporting_update_interval")
        private val ENABLE_TABLET_COMMAND_ENDPOINT = booleanPreferencesKey("enable_tablet_command_endpoint")
        private val TABLET_COMMAND_ENDPOINT = stringPreferencesKey("tablet_command_endpoint")
        private val TABLET_COMMAND_APIKEY = stringPreferencesKey("tablet_command_apikey")
        private val TABLET_COMMAND_REPORTING_UPDATE_INTERVAL = intPreferencesKey("tablet_command_reporting_update_interval")
    }
}
