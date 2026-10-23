package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.cot.TakServerCotEnvelope
import java.io.IOException

internal fun requireDataPackageSession(
    detail: TAKConnectService.TakServerDetail,
    envelope: TakServerCotEnvelope,
    serviceStopping: Boolean
): TAKConnectService.ConnectionAttempt {
    val runtime = detail.runtime
    val attempt = runtime.currentAttempt
    val socket = detail.secureSocket
    if (serviceStopping || runtime.terminated || !detail.isEnabled ||
        detail.serverId != envelope.sourceServerId || detail.address != envelope.sourceServerAddress ||
        attempt == null || envelope.sourceSessionId != attempt.sessionId ||
        runtime.generation != attempt.generation ||
        socket == null || socket !== attempt.socket || socket.isClosed || !socket.isConnected
    ) {
        throw IOException("Package source session is no longer active; resend after reconnecting")
    }
    return attempt
}
