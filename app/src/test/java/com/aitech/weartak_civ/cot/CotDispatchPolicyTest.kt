package com.aitech.weartak_civ.cot

import org.junit.Assert.assertEquals
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
    fun pliPreservesLegacyTakSitxCompanionAndAlwaysMulticastRoutes() {
        assertEquals(
            listOf(CotRoute.TAK, CotRoute.SITX, CotRoute.COMPANION, CotRoute.MULTICAST),
            CotDispatchPolicy.routesFor(
                kind = CotMessageKind.PLI,
                availability = CotEndpointAvailability(
                    tak = true,
                    sitx = false,
                    ditto = true,
                    multicast = false,
                    companion = true,
                ),
            ),
        )
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
    fun markerClearPreservesLegacyDoubleMulticastAndRouteOrder() {
        assertEquals(
            listOf(
                CotRoute.COMPANION,
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
                    companion = true,
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
