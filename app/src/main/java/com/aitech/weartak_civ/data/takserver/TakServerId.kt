/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data.takserver

import java.util.UUID

object TakServerId {
    fun newId(): String = UUID.randomUUID().toString()

    fun normalizeOrNull(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val canonical = runCatching { UUID.fromString(candidate) }
            .getOrNull()
            ?.toString()
            ?: return null
        return canonical.takeIf { it.equals(candidate, ignoreCase = true) }
    }

    fun ensure(value: String?): String = normalizeOrNull(value) ?: newId()

    fun ensureUnique(value: String?, usedIds: MutableSet<String>): String {
        val existing = normalizeOrNull(value)
        if (existing != null && usedIds.add(existing)) return existing

        var generated: String
        do {
            generated = newId()
        } while (!usedIds.add(generated))
        return generated
    }
}
