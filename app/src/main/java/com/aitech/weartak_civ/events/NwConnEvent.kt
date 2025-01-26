package com.aitech.weartak_civ.events

class NwConnEvent(val isConn: Boolean, val transport: Int) {

    companion object {
        val WIFI = 0
        val CELLULAR = 1
    }
}
