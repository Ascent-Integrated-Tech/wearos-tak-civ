package com.aitech.weartak_civ.command

import com.aitech.weartak_civ.data.DittoPdlSource
import com.aitech.weartak_civ.service.DittoCommunityLicenseHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

class SetDittoPdlHandler(
    private val isLocked: suspend () -> Boolean,
    private val communityPdl: () -> String,
    private val save: suspend (DittoPdlSource, String?) -> Unit,
    private val apply: suspend () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) : ChatCommandHandler {
    private val mutex = Mutex()

    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult =
        mutex.withLock {
            val args = command.args
            val community = args["source"] == "community"
            val custom = args["pdl"]
            if (args.keys.any { it !in setOf("source", "pdl") } ||
                (args.containsKey("source") && !community) ||
                community == (custom != null)) {
                return@withLock ChatCommandResult(false, "Ditto not changed: invalid arguments")
            }
            if (isLocked()) return@withLock ChatCommandResult(false, "Ditto not changed: settings locked")
            val source = if (community) DittoPdlSource.COMMUNITY else DittoPdlSource.CUSTOM
            val license = try {
                DittoCommunityLicenseHelper.parse(if (community) communityPdl() else requireNotNull(custom))
                    .also {
                        // Only a canonical UUID is safe to include in a metadata reply.
                        require(UUID.fromString(it.appId).toString() == it.appId.lowercase())
                        require(it.expiresAtMillis > now())
                        Instant.ofEpochMilli(it.expiresAtMillis)
                    }
            } catch (error: Exception) {
                return@withLock ChatCommandResult(false, "Ditto not changed: invalid or expired identity")
            }
            try {
                save(source, custom)
                if (!apply()) return@withLock ChatCommandResult(false, "Ditto update failed")
                ChatCommandResult(
                    true,
                    "Ditto updated: source=${source.name.lowercase()} app=${license.appId.take(8)} " +
                        "expiry=${Instant.ofEpochMilli(license.expiresAtMillis)}"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // SDK and storage exceptions may contain credentials. Do not forward their messages.
                ChatCommandResult(false, "Ditto update failed")
            }
        }
}
