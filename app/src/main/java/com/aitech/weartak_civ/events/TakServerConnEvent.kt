package com.aitech.weartak_civ

class TakServerConnEvent(val connEvent: Int) {

    companion object {
        public val CONNECTING = 0
        public val CONNECTION_FAILURE = 1
        public val CONNECTION_SUCCESS = 2
    }
}
