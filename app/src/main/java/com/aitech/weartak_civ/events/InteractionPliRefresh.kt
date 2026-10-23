package com.aitech.weartak_civ.events

import android.location.Location

enum class PliRefreshReason {
    CALLSIGN_CHANGED, TEAM_CHANGED, ROLE_CHANGED, MAP_OPENED,
    MANUAL_ALERT_ACTIVATED, MANUAL_ALERT_CLEARED,
    PHYSIO_ALERT_ACTIVATED, PHYSIO_ALERT_CLEARED, CHAT_SENT,
    SELF_MARKER, NAVIGATION_STARTED, MAP_RECENTERED, POSITION_VIEWED
}

data class InteractionPliRefresh(val reason: PliRefreshReason)

data class InteractionLocationRcvd(val location: Location, val reasons: Set<PliRefreshReason>)
