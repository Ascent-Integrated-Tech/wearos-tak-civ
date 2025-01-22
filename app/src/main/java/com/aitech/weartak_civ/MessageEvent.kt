package com.aitech.weartak_civ

class MessageEvent(val message: String) {

    companion object {
        public val DROP_MARKER = "DropMarker"
        public val SERVER_DETAILS_CHANGED = "ServerDetailsChanged"
        public val SERVER_TOGGLED = "ServerToggled"

    }

}