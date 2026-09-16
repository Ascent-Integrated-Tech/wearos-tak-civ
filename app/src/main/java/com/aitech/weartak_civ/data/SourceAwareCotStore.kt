/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SourceCotKey(
    val sourceId: String,
    val cotUid: String
)

/**
 * Keeps every source-owned copy while exposing the newest copy of each CoT UID.
 * All compound mutations and projections are serialized on [lock].
 */
class SourceAwareCotStore<T>(
    private val sourceIdOf: (T) -> String,
    private val cotUidOf: (T) -> String,
    private val receivedAtOf: (T) -> Long
) {
    private data class Stored<T>(
        val value: T,
        val sequence: Long
    )

    private val lock = Any()
    private val storedByKey = LinkedHashMap<SourceCotKey, Stored<T>>()
    private val uidOrder = LinkedHashMap<String, Long>()
    private var sequence = 0L

    private val _allItems = MutableStateFlow<Map<SourceCotKey, T>>(emptyMap())
    val allItems: StateFlow<Map<SourceCotKey, T>> = _allItems.asStateFlow()

    private val _visibleItems = MutableStateFlow<List<T>>(emptyList())
    val visibleItems: StateFlow<List<T>> = _visibleItems.asStateFlow()

    fun upsert(item: T): SourceCotKey = synchronized(lock) {
        val key = SourceCotKey(sourceIdOf(item), cotUidOf(item))
        require(key.sourceId.isNotBlank()) { "Source ID cannot be blank" }
        require(key.cotUid.isNotBlank()) { "CoT UID cannot be blank" }

        sequence += 1
        uidOrder.putIfAbsent(key.cotUid, sequence)
        storedByKey[key] = Stored(item, sequence)
        publishLocked()
        key
    }

    fun item(key: SourceCotKey): T? = synchronized(lock) {
        storedByKey[key]?.value
    }

    fun visibleItem(cotUid: String): T? = synchronized(lock) {
        visibleEntryLocked(cotUid)?.value?.value
    }

    fun visibleKey(cotUid: String): SourceCotKey? = synchronized(lock) {
        visibleEntryLocked(cotUid)?.key
    }

    fun update(key: SourceCotKey, transform: (T) -> T): T? = synchronized(lock) {
        val current = storedByKey[key] ?: return@synchronized null
        val updated = transform(current.value)
        storedByKey[key] = current.copy(value = updated)
        publishLocked()
        updated
    }

    fun updateVisible(cotUid: String, transform: (T) -> T): T? = synchronized(lock) {
        val key = visibleEntryLocked(cotUid)?.key ?: return@synchronized null
        val current = storedByKey[key] ?: return@synchronized null
        val updated = transform(current.value)
        storedByKey[key] = current.copy(value = updated)
        publishLocked()
        updated
    }

    fun transformAll(transform: (SourceCotKey, T) -> T) = synchronized(lock) {
        storedByKey.entries.forEach { entry ->
            entry.setValue(
                entry.value.copy(value = transform(entry.key, entry.value.value))
            )
        }
        publishLocked()
    }

    fun remove(key: SourceCotKey): T? = synchronized(lock) {
        val removed = storedByKey.remove(key)?.value ?: return@synchronized null
        removeUnusedUidOrderLocked(key.cotUid)
        publishLocked()
        removed
    }

    fun removeSource(sourceId: String): List<T> = synchronized(lock) {
        val removed = storedByKey
            .filterKeys { it.sourceId == sourceId }
            .values
            .map { it.value }
        if (removed.isEmpty()) return@synchronized emptyList()

        val affectedUids = storedByKey.keys
            .filter { it.sourceId == sourceId }
            .mapTo(mutableSetOf()) { it.cotUid }
        storedByKey.keys.removeAll { it.sourceId == sourceId }
        affectedUids.forEach(::removeUnusedUidOrderLocked)
        publishLocked()
        removed
    }

    fun removeUid(cotUid: String): List<T> = synchronized(lock) {
        val removed = storedByKey
            .filterKeys { it.cotUid == cotUid }
            .values
            .map { it.value }
        if (removed.isEmpty()) return@synchronized emptyList()

        storedByKey.keys.removeAll { it.cotUid == cotUid }
        uidOrder.remove(cotUid)
        publishLocked()
        removed
    }

    fun clear() = synchronized(lock) {
        storedByKey.clear()
        uidOrder.clear()
        publishLocked()
    }

    private fun visibleEntryLocked(cotUid: String): Map.Entry<SourceCotKey, Stored<T>>? =
        storedByKey.entries
            .asSequence()
            .filter { it.key.cotUid == cotUid }
            .maxWithOrNull(
                compareBy<Map.Entry<SourceCotKey, Stored<T>>> { receivedAtOf(it.value.value) }
                    .thenBy { it.value.sequence }
            )

    private fun removeUnusedUidOrderLocked(cotUid: String) {
        if (storedByKey.keys.none { it.cotUid == cotUid }) {
            uidOrder.remove(cotUid)
        }
    }

    private fun publishLocked() {
        _allItems.value = storedByKey.mapValues { it.value.value }
        val visibleByUid = HashMap<String, Stored<T>>(uidOrder.size)
        storedByKey.forEach { (key, candidate) ->
            val current = visibleByUid[key.cotUid]
            if (current == null || candidate.isNewerThan(current)) {
                visibleByUid[key.cotUid] = candidate
            }
        }
        _visibleItems.value = uidOrder.keys.mapNotNull { uid ->
            visibleByUid[uid]?.value
        }
    }

    private fun Stored<T>.isNewerThan(other: Stored<T>): Boolean {
        val receivedAtComparison = receivedAtOf(value).compareTo(receivedAtOf(other.value))
        return receivedAtComparison > 0 ||
            (receivedAtComparison == 0 && sequence > other.sequence)
    }
}
