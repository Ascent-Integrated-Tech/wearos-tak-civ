/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.service.sitx

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed class SitxServiceEvent {
    data class Info(val message: String) : SitxServiceEvent()
    data class Error(val message: String) : SitxServiceEvent()
    object Disconnected: SitxServiceEvent()
    data class NeedsUserAction(
        val userCode: String,
        val verificationUriComplete: String
    ) : SitxServiceEvent()

    data class Pending(
        val userCode: String,
        val verificationUriComplete: String,
        val message: String
    ) : SitxServiceEvent()
    data class Authorized(val message: String) : SitxServiceEvent()

    data class SocketsConnected(val count: Int, val message: String) : SitxServiceEvent()
    data class SocketStatus(val flowTag: String, val status: String, val message: String? = null) : SitxServiceEvent()
}

object SitxServiceEventBus {
    private val _events = MutableSharedFlow<SitxServiceEvent>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<SitxServiceEvent> = _events

    fun tryEmit(event: SitxServiceEvent) {
        _events.tryEmit(event)
    }
}