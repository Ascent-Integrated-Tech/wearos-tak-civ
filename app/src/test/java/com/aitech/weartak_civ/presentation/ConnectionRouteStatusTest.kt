package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.data.CompanionAppRepository.BleConnectionState
import com.aitech.weartak_civ.data.DataRepository.SitxServerStatus
import org.junit.Assert.*
import org.junit.Test

class ConnectionRouteStatusTest {
    private val allEnabled = ConnectionRouteSnapshot(
        dittoEnabled = true, companionEnabled = true, takEnabled = true,
        takServers = listOf(RouteTakServerStatus(true)), multicastEnabled = true, sitxEnabled = true
    )

    @Test fun disabledAndUnconfiguredRoutesAreHiddenEvenWithStaleRuntimeState() {
        assertTrue(connectionRouteStatuses(ConnectionRouteSnapshot(
            dittoSyncActive = true, companionReady = true, multicastRunning = true,
            sitxState = SitxServerStatus.CONNECTED,
            takServers = listOf(RouteTakServerStatus(true, "CONNECTED"))
        )).isEmpty())
        assertTrue(connectionRouteStatuses(ConnectionRouteSnapshot(takEnabled = true)).isEmpty())
        assertTrue(connectionRouteStatuses(ConnectionRouteSnapshot(takEnabled = true,
            takServers = listOf(RouteTakServerStatus(false, "CONNECTED")))).isEmpty())
    }

    @Test fun eachEnabledRouteHasItsOwnDisconnectedIndicator() {
        val routes = connectionRouteStatuses(allEnabled)
        assertEquals(ConnectionRoute.entries, routes.map { it.route })
        assertTrue(routes.all { it.state == RouteConnectionState.OFF })
        assertEquals(ConnectionRoute.entries.size, ConnectionRoute.entries.map { it.icon }.distinct().size)
    }

    @Test fun liveRoutesDoNotCollapseIntoOneTakIndicator() {
        val routes = connectionRouteStatuses(allEnabled.copy(
            dittoSyncActive = true, multicastRunning = true, sitxState = SitxServerStatus.CONNECTED,
            takServers = listOf(RouteTakServerStatus(true, "CONNECTED"))
        )).associate { it.route to it.state }
        assertEquals(5, routes.size)
        assertEquals(RouteConnectionState.OFF, routes[ConnectionRoute.COMPANION])
        routes.filterKeys { it != ConnectionRoute.COMPANION }.values.forEach {
            assertEquals(RouteConnectionState.CONNECTED, it)
        }
    }

    @Test fun companionHandshakeIsNotReadyAndDoesNotSuppressDirectTransports() {
        listOf(BleConnectionState.ADVERTISING, BleConnectionState.CONNECTING,
            BleConnectionState.CONNECTED, BleConnectionState.SERVICES_READY).forEach { ble ->
            val routes = connectionRouteStatuses(allEnabled.copy(
                companionBle = ble, dittoSyncActive = true)).associate { it.route to it.state }
            assertEquals(RouteConnectionState.CONNECTING, routes[ConnectionRoute.COMPANION])
            assertEquals(RouteConnectionState.CONNECTED, routes[ConnectionRoute.DITTO])
        }
    }

    @Test fun companionErrorsAndDisconnectsAreOffNotConnecting() {
        listOf(BleConnectionState.ERROR, BleConnectionState.CONNECTION_FAILED,
            BleConnectionState.DISCONNECTING, BleConnectionState.DISCONNECTED).forEach { ble ->
            val companion = connectionRouteStatuses(allEnabled.copy(companionBle = ble))
                .single { it.route == ConnectionRoute.COMPANION }
            assertEquals(RouteConnectionState.OFF, companion.state)
        }
    }

    @Test fun readyCompanionSuppressesEveryDirectRouteEvenBeforeRuntimeCatchesUp() {
        val routes = connectionRouteStatuses(allEnabled.copy(
            companionReady = true, dittoSyncActive = true, multicastRunning = true,
            sitxState = SitxServerStatus.CONNECTED,
            takServers = listOf(RouteTakServerStatus(true, "CONNECTED"))
        ))
        assertEquals(RouteConnectionState.CONNECTED, routes.single { it.route == ConnectionRoute.COMPANION }.state)
        assertTrue(routes.filter { it.route != ConnectionRoute.COMPANION }
            .all { it.state == RouteConnectionState.SUPPRESSED })
    }

    @Test fun disconnectGraceKeepsWatchRoutesSuppressedUntilItExpires() {
        val routes = connectionRouteStatuses(allEnabled.copy(disconnectGrace = true))
        assertTrue(routes.filter { it.route != ConnectionRoute.COMPANION }
            .all { it.state == RouteConnectionState.SUPPRESSED })
        assertTrue(connectionRouteStatuses(allEnabled).all { it.state == RouteConnectionState.OFF })
    }

    @Test fun partialTakCountsOnlyEnabledServersAndHandlesMissingRuntimes() {
        val partial = connectionRouteStatuses(allEnabled.copy(takServers = listOf(
            RouteTakServerStatus(true, "CONNECTED"), RouteTakServerStatus(true),
            RouteTakServerStatus(false, "CONNECTION_FAILED")
        ))).single { it.route == ConnectionRoute.TAK }
        assertEquals(RouteConnectionState.PARTIAL, partial.state)
        assertEquals(1, partial.connectedServers)
        assertEquals(2, partial.totalServers)
        val connected = connectionRouteStatuses(allEnabled.copy(takServers = listOf(
            RouteTakServerStatus(true, "CONNECTED"), RouteTakServerStatus(false)
        ))).single { it.route == ConnectionRoute.TAK }
        assertEquals(RouteConnectionState.CONNECTED, connected.state)
        assertEquals(1, connected.totalServers)
    }

    @Test fun connectingRoutesUseTheirOwnSignals() {
        val routes = connectionRouteStatuses(allEnabled.copy(
            dittoReconfiguring = true, dittoSyncActive = true, sitxState = SitxServerStatus.CONNECTING,
            takServers = listOf(RouteTakServerStatus(true, "CONNECTING"))
        )).associate { it.route to it.state }
        listOf(ConnectionRoute.DITTO, ConnectionRoute.TAK, ConnectionRoute.SITX).forEach {
            assertEquals(RouteConnectionState.CONNECTING, routes[it])
        }
        assertEquals(RouteConnectionState.OFF, routes[ConnectionRoute.MULTICAST])
    }
}
