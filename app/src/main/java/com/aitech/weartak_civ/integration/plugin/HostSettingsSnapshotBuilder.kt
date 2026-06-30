package com.aitech.weartak_civ.integration.plugin

import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.CotDispatchService
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.model.HostSettingsParcel
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds monotonic [HostSettingsParcel] snapshots from live host state.
 */
class HostSettingsSnapshotBuilder(
    private val app: WearTakApplication,
) {
    private val revisionSeq = AtomicLong(0L)

    suspend fun build(): HostSettingsParcel {
        val repo = app.repo
        val tak = runCatching { repo.getEnableTakEndpoint.first() }.getOrDefault(false)
        val sitx = runCatching {
            repo.sitxStatus.value == DataRepository.SitxServerStatus.CONNECTED
        }.getOrDefault(false)
        val callsign = runCatching { repo.getCallsign.first() }.getOrDefault("")
        val team = runCatching { repo.getTeam.first() }.getOrDefault("")
        val role = runCatching { repo.getRole.first() }.getOrDefault("")
        return HostSettingsParcel(
            revision = revisionSeq.incrementAndGet(),
            hostApiVersion = WearTakPluginContract.CONTRACT_API_VERSION,
            hostPackageName = app.packageName,
            snapshotTimeUtcMillis = System.currentTimeMillis(),
            callsign = callsign,
            team = team,
            role = role,
            reportingIntervalSecs = CotDispatchService.actualReportingIntervalSecs,
            desiredReportingIntervalSecs = CotDispatchService.desiredReportingIntervalSecs,
            takConnected = tak,
            sitxConnected = sitx,
            cotConnected = tak || sitx,
        )
    }
}
