/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.service.sitx
import com.aitech.weartak_civ.service.SitxTakService
import kotlinx.coroutines.flow.MutableSharedFlow

sealed class SitxServiceCommand {
    data class BeginAuth(val baseUrl: String, val clientId: String, val callsign: String, val deviceName: String, val deviceId: String): SitxServiceCommand()
    object PollDeviceToken: SitxServiceCommand()
    object ConnectSockets: SitxServiceCommand()
    object FetchAvailableGroups : SitxServiceCommand()

    data class SwitchGroup(
        val flowTag: String
    ) : SitxServiceCommand()
    data class ConnectSelectedGroups(
        val groups: List<SitxTakService.TakServerItem>
    ) : SitxServiceCommand()
    data class SendCot(val xml: String, val flowTag: String? = null): SitxServiceCommand()
    object Stop: SitxServiceCommand()
}

object SitxServiceCommandBus {
    val commands = MutableSharedFlow<SitxServiceCommand>(extraBufferCapacity = 64)
    fun trySend(cmd: SitxServiceCommand) = commands.tryEmit(cmd)
}