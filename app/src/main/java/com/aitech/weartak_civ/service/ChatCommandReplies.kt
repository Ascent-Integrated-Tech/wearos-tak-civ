package com.aitech.weartak_civ.service

import android.content.Context
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.command.ChatCommandRequest
import com.aitech.weartak_civ.cot.CotMessageBuilder
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.sitx.SitxServiceCommand
import com.aitech.weartak_civ.service.sitx.SitxServiceCommandBus

internal object ChatCommandReplies {
    suspend fun send(context: Context, repo: DataRepository, request: ChatCommandRequest, text: String): Boolean {
        val snapshot = repo.snapshotChatEvent(
            roomUid = if (request.replyIsGroup) requireNotNull(request.replyRoomUid) else request.senderUid,
            roomTitle = if (request.replyIsGroup) requireNotNull(request.replyRoomTitle) else request.senderCallsign,
            message = text, isGroup = request.replyIsGroup, participantUids = request.replyParticipantUids
        )
        val xml = CotMessageBuilder.chat(snapshot, MainActivity.callsign, MainActivity.device_uid)
        val source = request.sourceServerId
        return when {
            source.startsWith("ditto:") -> DittoService.publishCommandReply(xml)
            source.startsWith("multicast:") -> {
                val target = source.removePrefix("multicast:").split(':')
                target.size == 2 && NetworkUtils.MulticastPublisher.sendCommandReply(
                    xml, target[0], target[1].toIntOrNull() ?: return false)
            }
            source.startsWith("sitx:") -> SitxServiceCommandBus.trySend(
                SitxServiceCommand.SendCot(xml, source.removePrefix("sitx:")))
            source.startsWith("companion-") -> {
                if (!repo.isCompanionConnected.value) return false
                val capRepo = (context.applicationContext as WearTakApplication).capRepo
                if (source == "companion-gateway") capRepo.offerGatewayCot(xml)
                else {
                    if (!capRepo.companionAppReady.value) return false
                    capRepo.enqueueChatSnapshotForBle(snapshot)
                    true
                }
            }
            else -> TAKConnectService.sendCommandReply(source, xml)
        }
    }
}
