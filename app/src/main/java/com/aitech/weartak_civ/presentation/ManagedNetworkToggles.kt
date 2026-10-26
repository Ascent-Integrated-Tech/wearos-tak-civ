package com.aitech.weartak_civ.presentation

import android.os.Bundle

internal data class ManagedNetworkToggles(
    val tak: Boolean?,
    val esri: Boolean?,
    val tabletCommand: Boolean?,
    val multicast: Boolean?,
    val sitx: Boolean?,
    val ditto: Boolean?,
    val companion: Boolean?
) {
    companion object {
        @Suppress("DEPRECATION")
        fun parse(restrictions: Bundle): ManagedNetworkToggles {
            val endpoints = if (restrictions.containsKey("select_data_endpoints")) {
                val value = restrictions.get("select_data_endpoints")
                require(value is Array<*> && value.all { it is String }) {
                    "select_data_endpoints must be a string array"
                }
                require(value.all { it in setOf("TAK", "ESRI", "Tablet Command") }) {
                    "select_data_endpoints contains an unknown endpoint"
                }
                value.toList()
            } else null
            fun boolean(key: String): Boolean? {
                if (!restrictions.containsKey(key)) return null
                val value = restrictions.get(key)
                require(value is Boolean) { "$key must be a boolean" }
                return value
            }
            return ManagedNetworkToggles(
                tak = endpoints?.contains("TAK"),
                esri = endpoints?.contains("ESRI"),
                tabletCommand = endpoints?.contains("Tablet Command"),
                multicast = boolean("multicast_service_enabled"),
                sitx = boolean("sitx_service_enabled"),
                ditto = boolean("ditto_service_enabled"),
                companion = boolean("enable_companion_plugin")
            )
        }
    }
}
