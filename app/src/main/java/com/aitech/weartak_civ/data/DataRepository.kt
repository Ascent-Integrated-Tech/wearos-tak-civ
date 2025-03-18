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
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken


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

    private val _latestIsDeviceWorn = MutableStateFlow<Boolean>(false)
    val latestIsDeviceWorn: StateFlow<Boolean> = _latestIsDeviceWorn

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

    suspend fun storeRolesList(rolesList: String) {
        context.dataStore.edit { prefs ->
            prefs[ROLES_LIST] = rolesList
        }
    }

    val getRolesList: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[ROLES_LIST] ?: "Team Member,Team Lead,HQ,Sniper,Medic,Forward Observer,RTO,K9"
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

    val getEnableDittoService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE_DITTO_SERVICE] ?: false
    }

    suspend fun storeEnableDittoService(enableDittoService: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE_DITTO_SERVICE] = enableDittoService
        }
    }

    val getReportingUpdateInterval: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[REPORTING_UPDATE_INTERVAL] ?: 10
    }

    suspend fun storeReportingUpdateInterval(reportingUpdateInterval: Int) {
        context.dataStore.edit { prefs ->
            prefs[REPORTING_UPDATE_INTERVAL] = reportingUpdateInterval
        }
    }

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
                val type = object : TypeToken<List<WifiSsidItem>>() {}.type
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
                val type = object : TypeToken<List<WifiSsidItem>>() {}.type
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
                val type = object : TypeToken<List<WifiSsidItem>>() {}.type
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
                val type = object : TypeToken<List<WifiSsidItem>>() {}.type
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

    data class TakServerItem(
        var isEnabled: Boolean,
        val name: String,
        val address: String,
        val port: Int,
        val isP12Cert: Boolean,
        val username: String,
        val password: String,
        val p12Cert: String,
        val p12CertPassword: String,
        val reportingUpdateInterval: Int
    )
    val getTakServerList: Flow<List<TakServerItem>> = context.dataStore.data
        .map { preferences ->
            val jsonString = preferences[TAK_SERVER_LIST_KEY]
            if (jsonString.isNullOrEmpty()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<TakServerItem>>() {}.type
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
                val type = object : TypeToken<List<TakServerItem>>() {}.type
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
                val type = object : TypeToken<List<TakServerItem>>() {}.type
                gson.fromJson(currentJson, type)
            } else {
                mutableListOf()
            }

            currentList.remove(item)

            preferences[TAK_SERVER_LIST_KEY] = gson.toJson(currentList)
            Log.d(TAG, "removeTakServerItem newJson: ${gson.toJson(currentList)}")
        }
    }

    suspend fun updateTakServerItem(oldItemIndex: Int, newItem: TakServerItem) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[TAK_SERVER_LIST_KEY]
            Log.d(TAG, "updateTakServerItem oldIndex($oldItemIndex), newItem($newItem), currJsonList: ${currentJson.toString()}")
            val currentList: MutableList<TakServerItem> = if (!currentJson.isNullOrEmpty()) {
                val type = object : TypeToken<List<TakServerItem>>() {}.type
                gson.fromJson(currentJson, type)
            } else {
                mutableListOf()
            }

            if (oldItemIndex != -1) {
                currentList[oldItemIndex] = newItem
            }

            val newJson = gson.toJson(currentList)
            preferences[TAK_SERVER_LIST_KEY] = newJson
            Log.d(TAG, "updateTakServerItem newJson: $newJson")
            preferences[TAK_SERVER_LIST_KEY] = gson.toJson(currentList)
        }
    }

    companion object {
        private val PASSIVE_DATA_ENABLED = booleanPreferencesKey("passive_data_enabled")
//        private val LATEST_HEART_RATE = intPreferencesKey("latest_heart_rate")
        private val CALLSIGN = stringPreferencesKey("callsign")
        private val TEAM = stringPreferencesKey("team")
        private val TEAM_NAMES = stringPreferencesKey("team_names")
        private val TEAM_COLORS = stringPreferencesKey("team_colors")
        private val ROLE = stringPreferencesKey("role")
        private val ROLES_LIST = stringPreferencesKey("roles_list")
        private val LICENSE = stringPreferencesKey("license")
        private val ENABLE_LOCATION_SERVICE = booleanPreferencesKey("enable_location_service")
        private val ENABLE_BIOMETRICS_SERVICE = booleanPreferencesKey("enable_biometrics_service")
        private val ENABLE_DITTO_SERVICE = booleanPreferencesKey("enable_ditto_service")
        private val LOCATION_UPDATE_INTERVAL = intPreferencesKey("location_update_interval")
        private val BIOMETRICS_SEND_INTERVAL = intPreferencesKey("biometrics_send_interval")
        private val REPORTING_UPDATE_INTERVAL = intPreferencesKey("reporting_update_interval")
        private val ENABLE_WIFI_BATTERY_OPTIMIZATION = booleanPreferencesKey("enable_wifi_battery_optimization")
        private val ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION = booleanPreferencesKey("enable_all_wifi_battery_optimization")
        private val WIFI_SSID_LIST_KEY = stringPreferencesKey("wifi_ssid_list")
        private val TAK_SERVER_LIST_KEY = stringPreferencesKey("tak_server_list")
    }
}
