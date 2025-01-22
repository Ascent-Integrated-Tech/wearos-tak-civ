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
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.location.Location
import android.util.Log
import com.aitech.weartak_civ.service.CotDispatchService


private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "data")

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

//    val latestHeartRate: Flow<Int> = context.dataStore.data.map { prefs ->
//        prefs[LATEST_HEART_RATE] ?: -1
//    }
//
//    suspend fun storeLatestHeartRate(heartRate: Int) {
//        context.dataStore.edit { prefs ->
//            prefs[LATEST_HEART_RATE] = heartRate
//        }
//    }

    private val _latestHeartRate = MutableStateFlow<Int?>(null)
    val latestHeartRate: StateFlow<Int?> = _latestHeartRate

    private val _latestLocation = MutableStateFlow<Location?>(null)
    val latestLocation: StateFlow<Location?> = _latestLocation

    private val _latestBatteryPercentage = MutableStateFlow<Int?>(null)
    val latestBatteryPercentage: StateFlow<Int?> = _latestBatteryPercentage

    /*
    private val _markerEventId = MutableStateFlow<Int>(0)
    val markerEventId: StateFlow<Int> = _markerEventId

    fun dropMarker() {
        // Increment to represent a new event
        _markerEventId.value = _markerEventId.value + 1
        Log.d(TAG, "dropMarker Called")
    }

     */

    // Functions to update the data
    suspend fun storeLatestHeartRate(heartRate: Int) {
        _latestHeartRate.emit(heartRate)
    }

    suspend fun storeLatestLocation(location: Location) {
        _latestLocation.emit(location)
    }

    suspend fun storeLatestBatteryPercentage(batteryPercentage: Int) {
        _latestBatteryPercentage.emit(batteryPercentage)
    }

    val getCallsign: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CALLSIGN] ?: ("WEAROS-" + Random.nextInt(1, 1000).toString())
    }

    suspend fun storeCallsign(callsign: String) {
        context.dataStore.edit { prefs ->
            prefs[CALLSIGN] = callsign
        }
    }

    val getTeam: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TEAM] ?: "Orange"
    }

    suspend fun storeTeam(team: String) {
        context.dataStore.edit { prefs ->
            prefs[TEAM] = team
        }
    }
    val getRole: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[ROLE] ?: "Team Member"
    }

    suspend fun storeRole(role: String) {
        context.dataStore.edit { prefs ->
            prefs[ROLE] = role
        }
    }

    val getLicense: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[LICENSE] ?: "License"
    }

    suspend fun storeLicense(license: String) {
        context.dataStore.edit { prefs ->
            prefs[LICENSE] = license
        }
    }

    //Server textfields
    val getName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[NAME] ?: "name"
    }

    suspend fun storeName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[NAME] = name
        }
    }

    val getAddress: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[ADDRESS] ?: "address"
    }

    suspend fun storeAddress(address: String) {
        context.dataStore.edit { prefs ->
            prefs[ADDRESS] = address
        }
    }

    val getPort: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PORT] ?: "port"
    }

    suspend fun storePort(port: String) {
        context.dataStore.edit { prefs ->
            prefs[PORT] = port
        }
    }

    val getUsername: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[USERNAME] ?: "username"
    }

    suspend fun storeUsername(username: String) {
        context.dataStore.edit { prefs ->
            prefs[USERNAME] = username
        }
    }

    val getPassword: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PASSWORD] ?: "password"
    }

    suspend fun storePassword(password: String) {
        context.dataStore.edit { prefs ->
            prefs[PASSWORD] = password
        }
    }

    val getEnableLocationService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_LOCATION_SERVICE] ?: true
    }

    suspend fun storeEnableLocationService(enableLocationService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_LOCATION_SERVICE] = enableLocationService
        }
    }

    val getEnableBiometricsService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_BIOMETRICS_SERVICE] ?: true
    }

    suspend fun storeEnableBiometricsService(enableBiometricsService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_BIOMETRICS_SERVICE] = enableBiometricsService
        }
    }

    val getEnableTAKConnectService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_TAKCONNECT_SERVICE] ?: true
    }

    suspend fun storeEnableTAKConnectService(enableTAKConnectService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_TAKCONNECT_SERVICE] = enableTAKConnectService
        }
    }

    val getLocationUpdateInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[LOCATION_UPDATE_INTERVAL] ?: 2
    }

    suspend fun storeLocationUpdateInterval(locationUpdateInterval: Int) {
        context.dataStore.edit { prefs ->
            prefs[LOCATION_UPDATE_INTERVAL] = locationUpdateInterval
        }
    }

    val getBiometricsSendInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[BIOMETRICS_SEND_INTERVAL] ?: 5
    }

    suspend fun storeBiometricsSendInterval(biometricsSendInterval: Int) {
        context.dataStore.edit { prefs ->
            prefs[BIOMETRICS_SEND_INTERVAL] = biometricsSendInterval
        }
    }

    companion object {
        private val PASSIVE_DATA_ENABLED = booleanPreferencesKey("passive_data_enabled")
//        private val LATEST_HEART_RATE = intPreferencesKey("latest_heart_rate")
        private val CALLSIGN = stringPreferencesKey("callsign")
        private val TEAM = stringPreferencesKey("team")
        private val ROLE = stringPreferencesKey("role")
        private val LICENSE = stringPreferencesKey("license")
        private val NAME = stringPreferencesKey("name")
        private val ADDRESS = stringPreferencesKey("address")
        private val PORT = stringPreferencesKey("port")
        private val USERNAME = stringPreferencesKey("username")
        private val PASSWORD = stringPreferencesKey("password")
        private val ENABLE_LOCATION_SERVICE = booleanPreferencesKey("enable_location_service")
        private val ENABLE_BIOMETRICS_SERVICE = booleanPreferencesKey("enable_biometrics_service")
        private val ENABLE_TAKCONNECT_SERVICE = booleanPreferencesKey("enable_takconnect_service")
        private val LOCATION_UPDATE_INTERVAL = intPreferencesKey("location_update_interval")
        private val BIOMETRICS_SEND_INTERVAL = intPreferencesKey("biometrics_send_interval")
    }
}
