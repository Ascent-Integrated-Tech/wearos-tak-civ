/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.command

import java.util.UUID

/** A server enrollment requested over chat that is waiting for the user to enter credentials. */
data class ServerEnrollment(val token: String, val name: String, val address: String, val port: Int)

/**
 * Holds at most one pending enrollment in memory. It never contains credentials; those are entered
 * on the watch. The token ties the prompt that was shown to the settings screen that opens it.
 */
object PendingServerEnrollment {
    @Volatile private var pending: ServerEnrollment? = null

    fun offer(name: String, address: String, port: Int): ServerEnrollment =
        ServerEnrollment(UUID.randomUUID().toString(), name, address, port).also { pending = it }

    fun take(token: String?): ServerEnrollment? {
        val current = pending ?: return null
        if (token == null || current.token != token) return null
        pending = null
        return current
    }

    fun clear() { pending = null }
}