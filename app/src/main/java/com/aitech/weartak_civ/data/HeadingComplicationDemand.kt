package com.aitech.weartak_civ.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

internal class HeadingComplicationDemand(private val preferences: SharedPreferences) {
    private val ids = MutableStateFlow(preferences.getStringSet("active", emptySet()).orEmpty().toSet())
    val activeIds = ids.asStateFlow()

    @Synchronized
    fun setActive(id: Int, active: Boolean) {
        val key = id.toString()
        val next = if (active) ids.value + key else ids.value - key
        if (next == ids.value) return
        if (!preferences.edit().putStringSet("active", next).commit())
            throw IOException("Cannot persist compass complication ownership")
        ids.value = next
    }
}
