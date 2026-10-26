package com.aitech.weartak_civ.command

import android.os.Bundle

internal object NetworkCommandPolicy {
    @Suppress("DEPRECATION")
    fun rejection(restrictions: Bundle?, tak: Boolean): String? {
        val key = if (tak) "select_data_endpoints" else "multicast_service_enabled"
        if (restrictions?.containsKey(key) != true) return null
        val value = restrictions.get(key)
        val valid = if (tak) value is Array<*> &&
            value.all { it is String && it in setOf("TAK", "ESRI", "Tablet Command") }
        else value is Boolean
        return if (valid) "explicit administrator policy"
        else "invalid administrator policy ($key)"
    }
}
