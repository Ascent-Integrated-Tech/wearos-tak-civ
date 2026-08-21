package com.aitech.weartak_civ.cot

import org.junit.Assert.assertEquals
import org.junit.Test

class CotDispatchPolicyTest {
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
}
