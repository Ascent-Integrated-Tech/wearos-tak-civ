package com.aitech.weartak_civ.events

class IsDeviceWornEvent(val isDeviceWorn: Boolean) {

    companion object {
        val IS_WORN = true
        val IS_NOT_WORN = false
    }
}
