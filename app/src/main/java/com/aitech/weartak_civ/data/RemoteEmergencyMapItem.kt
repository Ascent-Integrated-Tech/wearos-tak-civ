package com.aitech.weartak_civ.data

import com.aitech.weartak_civ.cot.CotPoint

data class RemoteEmergencyMapItem(
    val uid: String,
    val callsign: String,
    val category: String,
    val point: CotPoint,
    val sourceServerId: String?,
    val isStale: Boolean = false
)

fun BloodhoundRepository.BloodhoundItem.toRemoteEmergencyMapItemOrNull(): RemoteEmergencyMapItem? {
    val alert = bhEvent.remoteAlert ?: return null
    if (!alert.hasLocation) return null
    return RemoteEmergencyMapItem(
        uid, alert.senderCallsign ?: uid, alert.category, target.point, sourceServerId, alert.isStale
    )
}
