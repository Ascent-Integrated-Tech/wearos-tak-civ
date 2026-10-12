/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.command

import java.time.Year

/** Where validated profile values are written. Implemented over DataRepository at runtime. */
interface ProfileStore {
    suspend fun setCallsign(value: String)
    suspend fun setTeam(value: String)
    suspend fun setRole(category: String, role: String)
    suspend fun setHeight(feet: Int, inches: Int)
    suspend fun setWeight(pounds: Int)
    suspend fun setBirthYear(year: Int)
    suspend fun setSex(value: String)
    suspend fun setUserType(value: String)
    suspend fun setPantsLength(value: Int)
    suspend fun setWaistSize(value: Int)
}

/** The values the settings screens offer, so chat commands accept exactly what the UI does. */
class ProfileOptions(
    val teams: () -> List<String>,
    val roles: () -> List<Pair<String, String>>,
    val sexes: () -> List<String>,
    val userTypes: () -> List<String>
)

/**
 * Handles the per-field commands (`setteam`, `setrole`, `setheight`, ...) and `setprofile`, which
 * takes any combination of fields. Every value is validated before anything is stored, so a bad
 * value changes nothing.
 */
class ProfileHandler(
    private val keys: Set<String>,
    private val identityLocked: suspend () -> Boolean,
    private val options: ProfileOptions,
    private val store: ProfileStore,
    private val currentYear: () -> Int = { Year.now().value }
) : ChatCommandHandler {

    private class Change(val identity: Boolean, val apply: suspend (ProfileStore) -> Unit)

    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        val args = command.args
        if (args.isEmpty()) {
            return ChatCommandResult(false, "${command.name} needs ${keys.sorted().joinToString(", ") { "$it=<value>" }}")
        }
        val unknown = args.keys - keys
        if (unknown.isNotEmpty()) {
            return ChatCommandResult(false, "${command.name} does not take ${unknown.sorted().joinToString(", ")}")
        }

        val changes = mutableListOf<Change>()
        for ((key, value) in args) {
            changes += parse(key, value) ?: return ChatCommandResult(false, "Invalid $key; nothing changed")
        }
        if (changes.any { it.identity } && identityLocked()) {
            return ChatCommandResult(false, "Settings are managed; nothing changed")
        }
        changes.forEach { it.apply(store) }
        return ChatCommandResult(true, "Updated ${args.keys.sorted().joinToString(", ")}")
    }

    private fun parse(key: String, value: String): Change? = when (key) {
        "callsign" -> ChatCommandValidation.callsign(value)?.let { v -> Change(true) { it.setCallsign(v) } }
        "team" -> match(value, options.teams())?.let { v -> Change(true) { it.setTeam(v) } }
        "role" -> options.roles().firstOrNull { it.second.equals(value.trim(), ignoreCase = true) }
            ?.let { r -> Change(true) { it.setRole(r.first, r.second) } }
        "height" -> ChatCommandValidation.height(value)?.let { h -> Change(false) { it.setHeight(h.first, h.second) } }
        "weight" -> intIn(value, 1, 1000)?.let { v -> Change(false) { it.setWeight(v) } }
        "birthyear" -> intIn(value, 1900, currentYear())?.let { v -> Change(false) { it.setBirthYear(v) } }
        "sex" -> match(value, options.sexes())?.let { v -> Change(false) { it.setSex(v) } }
        "usertype" -> match(value, options.userTypes())?.let { v -> Change(false) { it.setUserType(v) } }
        "pants" -> intIn(value, 20, 50)?.let { v -> Change(false) { it.setPantsLength(v) } }
        "waist" -> intIn(value, 20, 60)?.let { v -> Change(false) { it.setWaistSize(v) } }
        else -> null
    }

    private fun match(value: String, allowed: List<String>) =
        allowed.firstOrNull { it.equals(value.trim(), ignoreCase = true) }

    private fun intIn(value: String, min: Int, max: Int) = value.trim().toIntOrNull()?.takeIf { it in min..max }

    companion object {
        val COMMAND_KEYS: Map<String, Set<String>> = mapOf(
            "setteam" to setOf("team"),
            "setrole" to setOf("role"),
            "setheight" to setOf("height"),
            "setweight" to setOf("weight"),
            "setbirthyear" to setOf("birthyear"),
            "setsex" to setOf("sex"),
            "setusertype" to setOf("usertype"),
            "setpants" to setOf("pants"),
            "setwaist" to setOf("waist"),
            "setprofile" to setOf(
                "callsign", "team", "role", "height", "weight", "birthyear", "sex", "usertype", "pants", "waist"
            )
        )
    }
}
