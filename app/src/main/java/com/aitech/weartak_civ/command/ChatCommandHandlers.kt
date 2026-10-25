/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.command

import com.aitech.weartak_civ.data.LocationInput

/** `#weartakcommandsetcallsign callsign=ALPHA-1` */
class SetCallsignHandler(
    private val isLocked: suspend () -> Boolean,
    private val store: suspend (String) -> Unit
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        val callsign = ChatCommandValidation.callsign(command.args["callsign"])
            ?: return ChatCommandResult(false, "setcallsign needs callsign=<1-32 letters, digits, space . _ ->")
        if (isLocked()) return ChatCommandResult(false, "Settings are managed; callsign not changed")
        store(callsign)
        return ChatCommandResult(true, "Callsign set to $callsign")
    }
}

class SetLocationInputHandler(
    private val isLocked: suspend () -> Boolean,
    private val store: suspend (LocationInput) -> Boolean,
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        val input = when (command.args["input"]) {
            "gnss" -> LocationInput.INTERNAL_GNSS
            "enhancedgnss" -> LocationInput.FUSED
            else -> null
        }
        if (input == null || command.args.keys != setOf("input")) {
            return ChatCommandResult(false, "setlocationinput needs input=gnss or input=enhancedgnss")
        }
        if (isLocked() || !store(input)) {
            return ChatCommandResult(false, "Settings are managed; Location Input not changed")
        }
        return ChatCommandResult(true, "Location Input set to " +
            if (input == LocationInput.FUSED) "Fused Location" else "Internal GNSS")
    }
}

/**
 * `#weartakcommandenrollserver name=NCTAK url=takserver.nctak.gov port=8089`
 * Credentials are never accepted in chat; the user is prompted on the watch.
 */
class EnrollServerHandler(
    private val isLocked: suspend () -> Boolean,
    private val prompt: suspend (ServerEnrollment) -> Unit
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        val extra = command.args.keys - setOf("name", "url", "port")
        if (extra.isNotEmpty()) {
            return ChatCommandResult(false, "enrollserver takes only name, url and port; enter credentials on the watch")
        }
        val name = ChatCommandValidation.serverName(command.args["name"])
        val address = ChatCommandValidation.serverAddress(command.args["url"])
        val port = ChatCommandValidation.port(command.args["port"])
        if (name == null || address == null || port == null) {
            return ChatCommandResult(false, "enrollserver needs name=<name> url=<host> [port=<1-65535>]")
        }
        if (isLocked()) return ChatCommandResult(false, "Network settings are locked; server not added")
        prompt(PendingServerEnrollment.offer(name, address, port))
        return ChatCommandResult(true, "Waiting for the user to enter credentials for $name on the watch")
    }
}