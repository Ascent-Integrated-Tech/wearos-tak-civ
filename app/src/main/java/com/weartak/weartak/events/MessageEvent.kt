/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.weartak.weartak.events

// Use this event type for infrequent events
class MessageEvent(val message: String, val addlIntInfo: Int) {

    companion object {
        //val DROP_MARKER = "DropMarker"
        val SERVER_CHANGED = "ServerChanged"
        val SERVER_ADDED = "ServerAdded"
        val SERVER_ADDED_VIA_UI = "ServerAddedViaUI"
        val SERVER_REMOVED = "ServerRemoved"
        val SERVER_TOGGLED = "ServerToggled"
        val UPDATE_INTERVAL_CHANGED = "UpdateIntervalChanged"
        val CALLSIGN_TEAM_ROLE_CHANGED = "CallsignTeamRoleChanged"
        val OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED = "OptimizeBatteryOnWifiDetailsChanged"
        val LOCALIZATION_BEACONS_CHANGED = "LocalizationBeaconsChanged"
        val CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED = "CustomDeveloperConfigJsonChanged"
        val PHYSIOLOGICAL_ALERTS_ENABLED_CHANGED = "PhysiologicalAlertsEnabledChanged"

        val HR_ALERT_THRESHOLDS_CHANGED = "HrAlertThresholdsChanged"
    }
}
