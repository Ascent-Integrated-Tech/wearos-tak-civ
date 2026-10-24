package com.aitech.weartak_civ.service

import android.app.Application
import android.location.Location
import com.aitech.weartak_civ.data.DataRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OutboundPliAnnouncementGateTest {
    private fun fix(nanos: Long = 10_000_000_000L) = Location("gps").apply {
        latitude = 41.0
        longitude = -87.0
        altitude = 100.0
        accuracy = 5f
        time = 1_000_000L
        elapsedRealtimeNanos = nanos
    }

    @Test fun waitsForReadyAndForANewActualFix() {
        val gate = OutboundPliAnnouncementGate()
        assertFalse(gate.updateReady(emptySet(), 8_000_000_000L))
        assertFalse(gate.canDispatch(fix(), 10_000_000_000L))
        assertTrue(gate.updateReady(setOf("ditto:1"), 10_000_000_000L))
        assertFalse(gate.canDispatch(fix(9_000_000_000L), 10_000_000_000L))
        assertTrue(gate.canDispatch(fix(), 10_000_000_000L))
        gate.complete()
        assertFalse(gate.hasPending())
        assertFalse(gate.updateReady(setOf("ditto:1"), 11_000_000_000L))
        assertFalse(gate.canDispatch(fix(11_000_000_000L), 11_000_000_000L))
    }

    @Test fun coalescesAddedRoutesAndKeepsPendingWhenAcquisitionTimesOut() {
        val gate = OutboundPliAnnouncementGate()
        gate.updateReady(setOf("tak:a:1"), 8_000_000_000L)
        gate.updateReady(setOf("tak:a:1", "ditto:2"), 10_000_000_000L)
        assertFalse(gate.canDispatch(fix(9_000_000_000L), 10_000_000_000L))
        assertFalse(gate.canDispatch(fix(), 16_000_000_000L))
        assertTrue(gate.hasPending())
        assertTrue(gate.canDispatch(fix(20_000_000_000L), 20_000_000_000L))
        gate.complete()
        assertFalse(gate.canDispatch(fix(20_000_000_000L), 20_000_000_000L))
    }

    @Test fun removingDisabledDisallowedOrSuppressedRoutesCancelsPending() {
        val gate = OutboundPliAnnouncementGate()
        gate.updateReady(setOf("direct:1"), 8_000_000_000L)
        gate.updateReady(emptySet(), 9_000_000_000L)
        assertFalse(gate.hasPending())
        assertFalse(gate.canDispatch(fix(), 10_000_000_000L))
        gate.updateReady(setOf("companion:2"), 10_000_000_000L)
        assertTrue(gate.canDispatch(fix(), 10_000_000_000L))
    }

    @Test fun generationSurvivesConflatedDisconnectAndReconnect() {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.setEndpointConnected(DataRepository.Endpoint.DITTO)
        val first = repo.outboundReadyRoutes.value
        repo.setEndpointConnected(DataRepository.Endpoint.DITTO)
        assertEquals(first, repo.outboundReadyRoutes.value)
        val gate = OutboundPliAnnouncementGate()
        fun tokens() = repo.outboundReadyRoutes.value.map { "${it.key}:${it.value.generation}" }.toSet()
        gate.updateReady(tokens(), 8_000_000_000L)
        gate.complete()
        repo.setEndpointDisconnected(DataRepository.Endpoint.DITTO)
        repo.setEndpointConnected(DataRepository.Endpoint.DITTO)
        assertNotEquals(first, repo.outboundReadyRoutes.value)
        assertTrue(gate.updateReady(tokens(), 10_000_000_000L))
        assertTrue(gate.canDispatch(fix(), 10_000_000_000L))
    }

    @Test fun secondServerAndMulticastDestinationChangesHaveIndependentReadyIdentity() {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.setEndpointConnected(DataRepository.Endpoint.TAK_SERVER, setOf("a"))
        val a = repo.outboundReadyRoutes.value["TAK_SERVER:a"]
        repo.setEndpointConnected(DataRepository.Endpoint.TAK_SERVER, setOf("a", "b"))
        assertEquals(a, repo.outboundReadyRoutes.value["TAK_SERVER:a"])
        assertNotEquals(a!!.generation, repo.outboundReadyRoutes.value["TAK_SERVER:b"]!!.generation)
        repo.setEndpointConnected(DataRepository.Endpoint.MULTICAST, setOf("wifi:239.2.3.1:6969"))
        repo.setEndpointConnected(DataRepository.Endpoint.MULTICAST, setOf("wifi:239.2.3.2:6969"))
        assertFalse(repo.outboundReadyRoutes.value.containsKey("MULTICAST:wifi:239.2.3.1:6969"))
        assertEquals(3, repo.outboundReadyRoutes.value.size)
        repo.setEndpointDisconnected(DataRepository.Endpoint.TAK_SERVER)
        assertEquals(setOf(DataRepository.Endpoint.MULTICAST), repo.endpointConnected.value)
        assertEquals(1, repo.outboundReadyRoutes.value.size)
    }

    @Test fun rejectsMissingStaleFutureAndUnusableFixesButNotRealZeroCoordinates() {
        val now = 10_000_000_000L
        assertFalse(OutboundPliAnnouncementGate.fresh(fix().apply { time = 0 }, now))
        assertFalse(OutboundPliAnnouncementGate.fresh(fix(0), now))
        assertFalse(OutboundPliAnnouncementGate.fresh(fix(now + 1), now))
        assertFalse(OutboundPliAnnouncementGate.fresh(fix(4_999_999_999L), now))
        assertFalse(OutboundPliAnnouncementGate.fresh(fix().apply { removeAccuracy() }, now))
        assertFalse(OutboundPliAnnouncementGate.fresh(fix().apply { latitude = Double.NaN }, now))
        assertFalse(OutboundPliAnnouncementGate.fresh(fix().apply { altitude = Double.NaN }, now))
        assertTrue(OutboundPliAnnouncementGate.fresh(fix().apply { latitude = 0.0; longitude = 0.0 }, now))
    }
}
