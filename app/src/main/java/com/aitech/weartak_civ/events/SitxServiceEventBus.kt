package com.aitech.weartak_civ.service.sitx

import com.aitech.weartak_civ.service.SitxTakService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed class SitxServiceEvent {

    data class Info(val message: String) : SitxServiceEvent()

    data class Error(val message: String) : SitxServiceEvent()

    object Disconnected : SitxServiceEvent()

    object NoAuthorizedGroups : SitxServiceEvent()

    data class NeedsUserAction(
        val userCode: String,
        val verificationUriComplete: String
    ) : SitxServiceEvent()

    data class Pending(
        val userCode: String,
        val verificationUriComplete: String,
        val message: String
    ) : SitxServiceEvent()

    data class Authorized(
        val message: String
    ) : SitxServiceEvent()

    //Retrieves all groups you can connect to
    data class GroupsAvailable(
        val groups: List<SitxTakService.TakServerItem>
    ) : SitxServiceEvent()
    data class GroupSwitched(
        val flowTag: String
    ) : SitxServiceEvent()

    data class SocketsConnected(
        val count: Int,
        val message: String
    ) : SitxServiceEvent()

    data class SocketStatus(
        val flowTag: String,
        val status: String,
        val message: String? = null
    ) : SitxServiceEvent()
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