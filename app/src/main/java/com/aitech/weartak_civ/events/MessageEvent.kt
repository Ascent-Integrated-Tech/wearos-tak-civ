package com.aitech.weartak_civ.events

class MessageEvent(val message: String) {

    companion object {
        val RECV_LOCATION = "RecvLocation"
        val DROP_MARKER = "DropMarker"
        val SERVER_DETAILS_CHANGED = "ServerDetailsChanged"
        val SERVER_TOGGLED = "ServerToggled"
    }
}
