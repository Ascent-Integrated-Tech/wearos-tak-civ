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

data class SourceCotUpsertResult(
    val key: SourceCotKey,
    val applied: Boolean
)

/**
 * Keeps every source-owned copy while exposing the newest event-time copy of each CoT UID.
 * All compound mutations and projections are serialized on [lock].
 */
class SourceAwareCotStore<T>(
    private val sourceIdOf: (T) -> String,
    private val cotUidOf: (T) -> String,
    private val eventTimeOf: (T) -> Long,
    private val receivedAtOf: (T) -> Long
) {
    private data class Stored<T>(
        val value: T,
        val sequence: Long
    )

    private val lock = Any()
    private val storedByKey = LinkedHashMap<SourceCotKey, Stored<T>>()
    private val removedAtByKey = object : LinkedHashMap<SourceCotKey, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SourceCotKey, Long>?) = size > 2048
    }
    private val uidOrder = LinkedHashMap<String, Long>()
    private var sequence = 0L

    private val _allItems = MutableStateFlow<Map<SourceCotKey, T>>(emptyMap())
    val allItems: StateFlow<Map<SourceCotKey, T>> = _allItems.asStateFlow()

    private val _visibleItems = MutableStateFlow<List<T>>(emptyList())
    val visibleItems: StateFlow<List<T>> = _visibleItems.asStateFlow()

    fun upsert(item: T): SourceCotKey = upsertWithResult(item).key

    fun upsertWithResult(item: T): SourceCotUpsertResult = synchronized(lock) {
        val key = SourceCotKey(sourceIdOf(item), cotUidOf(item))
        require(key.sourceId.isNotBlank()) { "Source ID cannot be blank" }
        require(key.cotUid.isNotBlank()) { "CoT UID cannot be blank" }
        if (removedAtByKey[key]?.let { eventTimeOf(item) <= it } == true) {
            return@synchronized SourceCotUpsertResult(key, applied = false)
        }

        val candidate = nextStored(item)
        val current = storedByKey[key]
        if (current != null && !candidate.isNewerThan(current)) {
            return@synchronized SourceCotUpsertResult(key, applied = false)
        }

        uidOrder.putIfAbsent(key.cotUid, sequence)
        storedByKey[key] = candidate
        publishLocked()
        SourceCotUpsertResult(key, applied = true)
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

    /** Records a source removal watermark; equal event-time removals win over live copies. */
    fun removeIfNotOlder(
        key: SourceCotKey,
        eventTime: Long,
        receivedAt: Long
    ): T? = synchronized(lock) {
        // A removal is an event-time watermark, even if this source has no current copy.
        removedAtByKey[key] = maxOf(removedAtByKey[key] ?: Long.MIN_VALUE, eventTime)
        val current = storedByKey[key] ?: return@synchronized null
        if (eventTime < eventTimeOf(current.value)) {
            return@synchronized null
        }

        storedByKey.remove(key)
        removeUnusedUidOrderLocked(key.cotUid)
        publishLocked()
        current.value
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

    /** Bounds only the selected class of objects; legacy contacts/markers keep their behavior. */
    fun pruneSelected(
        selected: (T) -> Boolean,
        expired: (T) -> Boolean,
        maxCount: Int
    ): List<T> = synchronized(lock) {
        require(maxCount > 0)
        val candidates = storedByKey.entries.filter { selected(it.value.value) }
        val expiredKeys = candidates.filter { expired(it.value.value) }.mapTo(mutableSetOf()) { it.key }
        val excess = candidates.filter { it.key !in expiredKeys }
            .sortedBy { receivedAtOf(it.value.value) }.dropLast(maxCount)
        val keys = expiredKeys + excess.map { it.key }
        val removed = keys.mapNotNull { storedByKey.remove(it)?.value }
        if (removed.isNotEmpty()) {
            keys.map { it.cotUid }.forEach(::removeUnusedUidOrderLocked)
            publishLocked()
        }
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
        removedAtByKey.clear()
        uidOrder.clear()
        publishLocked()
    }

    private fun visibleEntryLocked(cotUid: String): Map.Entry<SourceCotKey, Stored<T>>? =
        storedByKey.entries
            .asSequence()
            .filter { it.key.cotUid == cotUid }
            .maxWithOrNull(
                compareBy<Map.Entry<SourceCotKey, Stored<T>>> { eventTimeOf(it.value.value) }
                    .thenBy { receivedAtOf(it.value.value) }
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
        return isNewer(
            eventTime = eventTimeOf(value),
            receivedAt = receivedAtOf(value),
            sequence = sequence,
            other = other
        )
    }

    private fun isNewer(
        eventTime: Long,
        receivedAt: Long,
        sequence: Long,
        other: Stored<T>
    ): Boolean {
        val eventTimeComparison = eventTime.compareTo(eventTimeOf(other.value))
        if (eventTimeComparison != 0) return eventTimeComparison > 0

        val receivedAtComparison = receivedAt.compareTo(receivedAtOf(other.value))
        return receivedAtComparison > 0 ||
            (receivedAtComparison == 0 && sequence > other.sequence)
    }

    private fun nextStored(item: T): Stored<T> {
        sequence += 1
        return Stored(item, sequence)
    }
}
