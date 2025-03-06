package com.aitech.weartak_civ.events

class TakServerConnStatusEvent(val connEvent: Int) {

    companion object {
        val CONNECTING = 0
        val CONNECTION_FAILURE = 1
        val CONNECTION_SUCCESS = 2
        val DISCONNECTED = 3
    }
}
