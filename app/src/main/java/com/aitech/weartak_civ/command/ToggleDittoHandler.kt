package com.aitech.weartak_civ.command

class ToggleDittoHandler(
    private val isLocked: suspend () -> Boolean,
    private val apply: suspend (Boolean) -> Boolean
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        val state = command.args["state"]
        if (command.args.keys != setOf("state") || state !in setOf("on", "off")) {
            return ChatCommandResult(false, "Ditto not changed: toggleditto needs on or off")
        }
        if (isLocked()) return ChatCommandResult(false, "Ditto not changed: settings locked")
        if (state == "off" && request.sourceServerId.startsWith("ditto:")) {
            return ChatCommandResult(true, "Ditto disabling: acknowledgement delivery is not confirmed",
                afterReply = { check(apply(false)) { "Ditto disable failed" } })
        }
        return if (apply(state == "on")) ChatCommandResult(true, "Ditto ${if (state == "on") "enabled" else "disabled"}")
        else ChatCommandResult(false, "Ditto update failed")
    }
}
