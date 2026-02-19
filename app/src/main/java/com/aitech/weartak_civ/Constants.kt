/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */


package com.aitech.weartak_civ

const val DEFAULT_ENABLE_LOCATION_SERVICE: Boolean = true
const val DEFAULT_ENABLE_BLE_BEACON_SERVICE: Boolean = false
const val DEFAULT_ENABLE_PHYSIOLOGICAL_SERVICES: Boolean = true
const val DEFAULT_ENABLE_HEART_RATE_SERVICE: Boolean = true
const val DEFAULT_ENABLE_ACTIVITY_RECOGNITION_SERVICE: Boolean = false
const val DEFAULT_ENABLE_SKIN_TEMPERATURE_SERVICE: Boolean = true
const val DEFAULT_ENABLE_DITTO_SERVICE: Boolean = false
const val DEFAULT_ENABLE_COMPANION_APP_SERVICE: Boolean = false
const val DEFAULT_ENABLE_BLOODHOUND_COMPASS_SERVICE: Boolean = false
const val DEFAULT_ENABLE_CHAT_SERVICE: Boolean = true
const val DEFAULT_ENABLE_BATTERY_ALERTS: Boolean = false
const val DEFAULT_ENABLE_HAIL_SERVICE: Boolean = false
const val DEFAULT_ENABLE_GEOFENCE_SERVICE: Boolean = false
const val DEFAULT_ENABLE_MULTICAST_ON_WIFI: Boolean = true
const val DEFAULT_ENABLE_BATDOK_COT: Boolean = true
const val DEFAULT_ENABLE_WIFI_BATTERY_OPTIMIZATION: Boolean = true
const val DEFAULT_ENABLE_ALL_WIFI_BATTERY_OPTIMIZATION: Boolean = true
const val DEFAULT_ENABLE_TAK_ENDPOINT: Boolean = true
const val DEFAULT_ENABLE_ESRI_ENDPOINT: Boolean = false
const val DEFAULT_ENABLE_TABLET_COMMAND_ENDPOINT: Boolean = false
const val DEFAULT_ENABLE_DYNAMIC_REPORTING: Boolean = true
const val DEFAULT_ENABLE_SMS_ALERTS: Boolean = false
const val DEFAULT_ENABLE_PHYSIOLOGICAL_ALERTS: Boolean = false
const val DEFAULT_RESTING_HR : Int = 60
const val DEFAULT_AGE : Int = 28
const val DEFAULT_HEIGHT_FEET : Int = 6
const val DEFAULT_HEIGHT_INCHES : Int = 0
const val DEFAULT_WEIGHT : Int = 180
const val DEFAULT_SEX : String = "N/A"
const val DEFAULT_BLOOD_TYPE : String = "N/A"
const val DEFAULT_ALLERGY_LIST : String = "N/A"
const val DEFAULT_USER_TYPE : String = "N/A"

const val DEFAULT_UNIFORM_WAIST_SIZE: Int = 33
const val DEFAULT_STRIDE_LENGTH: Int = 30
const val DEFAULT_UNIFORM_PANTS_LENGTH: Int = 31
const val DEFAULT_LOADOUT_WEIGHT: Int = 72

//const val DEFAULT_SAVE_BATTERY_ON_WIFI_STRING : String = ALL_WIFI_NETWORKS
const val DEFAULT_ESRI_REPORTING_INTERVAL : Int = 60
const val DEFAULT_TABLET_COMMAND_REPORTING_INTERVAL : Int = 60
const val DEFAULT_HIGH_HEART_RATE_ALERT_THRESHOLD : Int = 163
const val DEFAULT_HIGH_HEART_RATE_ALERT_THRESHOLD_NUMBER_SECONDS : Int = 60

const val DEFAULT_CONSTANT_REPORTING_INTERVAL = 60
const val DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL = 10
const val DEFAULT_ON_FOOT_REPORTING_INTERVAL = 60
const val DEFAULT_VEHICLE_REPORTING_INTERVAL = 60
const val DEFAULT_STATIONARY_REPORTING_INTERVAL = 3600
const val DEFAULT_DOCKED_REPORTING_INTERVAL_SECS = 15 * 60 // 15 min * 60 secs = 900 secs

const val DEFAULT_BATT_WARNING_PERCENTAGE = 50
const val DEFAULT_BATT_ALERT_PERCENTAGE = 25

const val CAT_MANUAL_ALERT = "Manual Alert"
const val DESC_SOS_ALERT = "Manual Alert Pressed by User"

const val DEFAULT_SELECTED_PHYSIO_METRIC = "key_heart_rate"
const val CAT_PHYSIOLOGICAL_ALERT = "Physiological Alert"
const val DESC_HIGH_HEART_RATE_ALERT = "HIGH Heart Rate"
const val DESC_LOW_HEART_RATE_ALERT = "LOW Heart Rate"
const val DESC_HIGH_EXERTION_ALERT = "HIGH Exertion"

const val CAT_FALL_DETECTED = "Fall Detected"
const val DESC_GOOGLE_SDK_FALL_DETECTED = "Hard fall detected with no motion for 30 seconds"

// TODO Senni: This should be an enum in MarkerActivity.kt. No other service/activity uses this??
const val MARKER_HOSTILE = "Hostile"
const val MARKER_FRIENDLY = "Friendly"
const val MARKER_UNKNOWN = "Unknown"
const val MARKER_NEUTRAL = "Neutral"


const val DEFAULT_MULTICAST_ADDRESS = "239.2.3.1"
const val DEFAULT_MULTICAST_PORT = 6969
const val DEFAULT_MULTICAST_PROTOCOL = "UDP"
