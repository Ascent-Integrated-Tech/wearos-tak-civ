package com.aitech.weartak_civ.cot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CotDispatchPolicyTest {
    @Test
    fun companionOnlyConfigurationDoesNotAddOtherRoutes() {
        val availability = CotEndpointAvailability(
            tak = false, sitx = false, ditto = false, multicast = false,
            companion = true, plugin = false
        )
        listOf(CotMessageKind.CHAT, CotMessageKind.MARKER, CotMessageKind.EMERGENCY).forEach { kind ->
            assertEquals(listOf(CotRoute.COMPANION), CotDispatchPolicy.routesFor(kind, availability))
        }
    }

    @Test
    fun pluginIsOfferedFirstForEveryKindWithoutOtherEndpoints() {
        val availability = CotEndpointAvailability(
            tak = false, sitx = false, ditto = false, multicast = false,
            companion = false, plugin = true
        )
        CotMessageKind.entries.forEach { kind ->
            val routes = CotDispatchPolicy.routesFor(kind, availability)
            assertEquals(CotRoute.PLUGIN, routes.first())
            assertEquals(1, routes.count { it == CotRoute.PLUGIN })
            if (kind != CotMessageKind.PLI) assertEquals(listOf(CotRoute.PLUGIN), routes)
        }
    }

    @Test
    fun pliRoutesOnlyThroughReadyCompanionWhenAvailable() {
        assertEquals(
            listOf(CotRoute.COMPANION),
            CotDispatchPolicy.routesFor(
                kind = CotMessageKind.PLI,
                availability = CotEndpointAvailability(
                    tak = true,
                    sitx = true,
                    ditto = true,
                    multicast = true,
                    companion = true,
                ),
            ),
        )
    }

    @Test
    fun readyCompanionPreservesPluginDeliveryWithoutDirectWatchRoutes() {
        val availability = CotEndpointAvailability(
            tak = true, sitx = true, ditto = true, multicast = true,
            companion = true, plugin = true
        )
        CotMessageKind.entries.forEach { kind ->
            assertEquals(listOf(CotRoute.PLUGIN, CotRoute.COMPANION),
                CotDispatchPolicy.routesFor(kind, availability))
        }
    }

    @Test
    fun allOutboundKindsUseOnlyReadyCompanionAndFallBackAfterDisconnect() {
        val kinds = listOf(
            CotMessageKind.PLI,
            CotMessageKind.CHAT,
            CotMessageKind.MARKER,
            CotMessageKind.MARKER_CLEAR,
            CotMessageKind.EMERGENCY,
        )
        val connected = CotEndpointAvailability(true, true, true, true, true)
        val disconnected = connected.copy(companion = false)

        kinds.forEach { kind ->
            assertEquals("$kind while companion is ready", listOf(CotRoute.COMPANION),
                CotDispatchPolicy.routesFor(kind, connected))
            assertTrue("$kind resumes direct routes after disconnect",
                CotDispatchPolicy.routesFor(kind, disconnected).any { it != CotRoute.COMPANION })
        }
    }

    @Test
    fun mapItemsUseOnlyAvailableLegacyRoutes() {
        assertEquals(
            listOf(CotRoute.MULTICAST, CotRoute.DITTO, CotRoute.SITX),
            CotDispatchPolicy.routesFor(
                kind = CotMessageKind.MARKER,
                availability = CotEndpointAvailability(
                    tak = false,
                    sitx = true,
                    ditto = true,
                    multicast = true,
                    companion = false,
                ),
            ),
        )
    }

    @Test
    fun markerClearPreservesLegacyDoubleMulticastWhenCompanionIsDisconnected() {
        assertEquals(
            listOf(
                CotRoute.DITTO,
                CotRoute.TAK,
                CotRoute.MULTICAST,
                CotRoute.MULTICAST,
                CotRoute.SITX,
            ),
            CotDispatchPolicy.routesFor(
                kind = CotMessageKind.MARKER_CLEAR,
                availability = CotEndpointAvailability(
                    tak = true,
                    sitx = true,
                    ditto = true,
                    multicast = true,
                    companion = false,
                ),
            ),
        )
    }

    @Test
    fun emergencyRouteIsReadyForLicensedMulticastAloneAndNotForDisconnectedEndpoints() {
        assertEquals(
            listOf(CotRoute.MULTICAST),
            CotDispatchPolicy.routesFor(
                CotMessageKind.EMERGENCY,
                CotEndpointAvailability(
                    tak = false,
                    sitx = false,
                    ditto = false,
                    multicast = true,
                    companion = false,
                ),
            ),
        )
        assertEquals(
            emptyList<CotRoute>(),
            CotDispatchPolicy.routesFor(
                CotMessageKind.EMERGENCY,
                CotEndpointAvailability(
                    tak = false,
                    sitx = false,
                    ditto = false,
                    multicast = false,
                    companion = false,
                ),
            ),
        )
    }
}
