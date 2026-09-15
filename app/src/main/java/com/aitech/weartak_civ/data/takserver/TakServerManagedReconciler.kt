/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.data.DataRepository.TakServerItem

/** Matches managed records to saved records without treating an editable endpoint as identity. */
object TakServerManagedReconciler {
    fun matchExistingIndexes(
        configured: List<TakServerItem>,
        existing: List<TakServerItem>
    ): List<Int?> {
        val matches = MutableList<Int?>(configured.size) { null }
        val claimed = mutableSetOf<Int>()

        assignUniqueMatches(matches, claimed, configured, existing) { managed, saved ->
            val managedId = TakServerId.normalizeOrNull(managed.serverId)
            managedId != null && managedId == TakServerId.normalizeOrNull(saved.serverId)
        }
        assignUniqueMatches(matches, claimed, configured, existing) { managed, saved ->
            TakServerEndpointIdentity.key(managed) == TakServerEndpointIdentity.key(saved)
        }
        assignUniqueMatches(matches, claimed, configured, existing) { managed, saved ->
            managed.name.trim().equals(saved.name.trim(), ignoreCase = true)
        }

        if (configured.size == existing.size) {
            matches.indices.forEach { index ->
                if (matches[index] == null && index !in claimed) {
                    matches[index] = index
                    claimed += index
                }
            }
        }
        return matches
    }

    private inline fun assignUniqueMatches(
        matches: MutableList<Int?>,
        claimed: MutableSet<Int>,
        configured: List<TakServerItem>,
        existing: List<TakServerItem>,
        predicate: (TakServerItem, TakServerItem) -> Boolean
    ) {
        matches.indices.forEach { configuredIndex ->
            if (matches[configuredIndex] != null) return@forEach
            val candidates = existing.indices.filter { existingIndex ->
                existingIndex !in claimed && predicate(configured[configuredIndex], existing[existingIndex])
            }
            if (candidates.size == 1) {
                matches[configuredIndex] = candidates.single()
                claimed += candidates.single()
            }
        }
    }
}
