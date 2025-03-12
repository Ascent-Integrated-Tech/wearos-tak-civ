package com.aitech.weartak_civ.events

// Use this event type for infrequent events
class MessageEvent(val message: String, val addlIntInfo: Int) {

    companion object {
        val DROP_MARKER = "DropMarker"
        val SERVER_DETAILS_CHANGED = "ServerDetailsChanged"
        val SERVER_ADDED = "ServerAdded"
        val SERVER_TOGGLED = "ServerToggled"
        val UPDATE_INTERVAL_CHANGED = "UpdateIntervalChanged"
        val CALLSIGN_TEAM_ROLE_CHANGED = "CallsignTeamRoleChanged"
        val OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED = "OptimizeBatteryOnWifiDetailsChanged"
    }
}
