package com.aitech.weartak_civ.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.cot.ManualAlertTypes
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.savedCustomAlertsDataStore by preferencesDataStore(name = "saved_custom_alerts")

object SavedCustomAlerts {
    const val MAX_COUNT = 20
    const val MAX_LENGTH = 60

    fun normalize(text: String): String? {
        if (text.any(Char::isISOControl)) return null
        val normalized = buildString {
            var pendingSpace = false
            for (character in text) {
                if (character.isWhitespace()) {
                    if (isNotEmpty()) pendingSpace = true
                } else {
                    if (pendingSpace) append(' ')
                    append(character)
                    pendingSpace = false
                }
            }
        }
        return normalized.takeIf { it.length in 1..MAX_LENGTH }
    }

    fun add(existing: List<String>, text: String): List<String>? {
        val normalized = normalize(text) ?: return null
        if (existing.size >= MAX_COUNT || isBuiltIn(normalized) || existing.containsIgnoreCase(normalized)) return null
        return existing + normalized
    }

    fun remove(existing: List<String>, text: String): List<String> {
        val normalized = normalize(text)
        return existing.filterNot {
            it.equals(text, ignoreCase = true) ||
                (normalized != null && (normalize(it) ?: it).equals(normalized, ignoreCase = true))
        }
    }

    fun merge(existing: List<String>, incoming: List<String>): List<String>? {
        if (existing.size > MAX_COUNT) return null
        val normalizedIncoming = mutableListOf<String>()
        for (text in incoming) {
            normalizedIncoming += normalize(text) ?: return null
        }

        val result = existing.toMutableList()
        for (text in normalizedIncoming) {
            if (isBuiltIn(text) || result.containsIgnoreCase(text)) continue
            if (result.size >= MAX_COUNT) return null
            result += text
        }
        return result
    }

    private fun isBuiltIn(text: String): Boolean =
        ManualAlertTypes.options.any { it.displayName.equals(text, ignoreCase = true) }

    private fun List<String>.containsIgnoreCase(text: String): Boolean =
        any { (normalize(it) ?: it).equals(text, ignoreCase = true) }
}

class SavedCustomAlertsStore(context: Context) {
    private val dataStore = context.applicationContext.savedCustomAlertsDataStore
    private val gson = Gson()
    private val key = stringPreferencesKey("alerts_json")
    private val listType = object : TypeToken<List<String>>() {}.type

    val alerts: Flow<List<String>> = dataStore.data.map { preferences -> decode(preferences[key]) }

    suspend fun add(text: String): Boolean {
        var added = false
        dataStore.edit { preferences ->
            val updated = SavedCustomAlerts.add(decode(preferences[key]), text)
            if (updated != null) {
                preferences[key] = gson.toJson(updated)
                added = true
            }
        }
        return added
    }

    suspend fun remove(text: String) {
        dataStore.edit { preferences ->
            preferences[key] = gson.toJson(SavedCustomAlerts.remove(decode(preferences[key]), text))
        }
    }

    suspend fun merge(incoming: List<String>): Boolean {
        var merged = false
        dataStore.edit { preferences ->
            val updated = SavedCustomAlerts.merge(decode(preferences[key]), incoming)
            if (updated != null) {
                preferences[key] = gson.toJson(updated)
                merged = true
            }
        }
        return merged
    }

    internal fun decode(json: String?): List<String> = runCatching {
        gson.fromJson<List<String>>(json ?: "[]", listType)
            .orEmpty()
    }.getOrDefault(emptyList())
}