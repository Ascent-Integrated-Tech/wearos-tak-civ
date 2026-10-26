package com.aitech.weartak_civ.command

class NetworkMasterCommandHandler(
    private val rejection: suspend (tak: Boolean) -> String?,
    private val apply: suspend (tak: Boolean, enabled: Boolean) -> String?
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        if (command.name !in COMMANDS || command.args.isNotEmpty()) {
            return ChatCommandResult(false, "Network setting not changed: command takes no arguments")
        }
        val tak = command.name.endsWith("takservers")
        val enabled = command.name.startsWith("enable")
        val label = if (tak) "TAK servers" else "Multicast"
        rejection(tak)?.let { return ChatCommandResult(false, "$label not changed: $it") }
        if (!enabled) {
            return ChatCommandResult(
                true,
                "$label disable requested. This path may stop receiving commands. " +
                    "Re-enable on the watch, or via another configured, active command transport. " +
                    "Applying only after this warning is written to the originating path; delivery is not confirmed.",
                afterSuccessfulReply = {
                    apply(tak, false)?.let { "$label not changed: $it" }
                }
            )
        }
        val reason = apply(tak, true)
        return if (reason == null) ChatCommandResult(true, "$label enabled")
        else ChatCommandResult(false, "$label not changed: $reason")
    }

    companion object {
        val COMMANDS = setOf("enabletakservers", "disabletakservers", "enablemulticast", "disablemulticast")
    }
}
