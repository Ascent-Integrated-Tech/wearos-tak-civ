package com.aitech.weartak_civ.service

import org.junit.Assert.*
import org.junit.Test

class PhoneManagementLeaseTest {
    private val lease = PhoneManagementLease()

    @Test fun expiresAtThirtySecondsEvenWhenTransportStillAppearsConnected() {
        assertFalse(lease.isActive())
        lease.start("AA:BB", 1, 100)
        assertFalse(lease.expire(30_099))
        assertTrue(lease.isActive())
        assertTrue(lease.expire(30_100))
        assertFalse(lease.isActive())
        assertFalse(lease.expire(60_000))
        assertNull(lease.probe("AA:BB", 1, 60_000))
    }

    @Test fun onlyFreshCurrentPeerResponseRenewsLease() {
        lease.start("AA:BB", 1, 0)
        val token = requireNotNull(lease.probe("AA:BB", 1, 0))
        assertNull(lease.probe("AA:BB", 1, 9_999))
        assertFalse(lease.acknowledge("CC:DD", 1, token, 1_000))
        assertFalse(lease.acknowledge("AA:BB", 2, token, 1_000))
        assertFalse(lease.acknowledge("AA:BB", 1, "wrong", 1_000))
        assertTrue(lease.acknowledge("aa:bb", 1, token, 5_000))
        assertFalse(lease.acknowledge("AA:BB", 1, token, 6_000))
        assertFalse(lease.expire(34_999))
        assertTrue(lease.expire(35_000))
    }

    @Test fun expiredResponseCannotRelockWithoutNewManagementHandshake() {
        lease.start("AA:BB", 1, 0)
        val old = requireNotNull(lease.probe("AA:BB", 1, 0))
        assertFalse(lease.acknowledge("AA:BB", 1, old, 30_000))
        assertTrue(lease.expire(30_000))
        lease.start("AA:BB", 2, 40_000)
        val fresh = requireNotNull(lease.probe("AA:BB", 2, 40_000))
        assertFalse(lease.acknowledge("AA:BB", 1, old, 41_000))
        assertTrue(lease.acknowledge("AA:BB", 2, fresh, 41_000))
    }

    @Test fun disconnectedOrReplacedSessionCannotRenewOldLease() {
        lease.start("AA:BB", 1, 0)
        assertTrue(lease.ownsSession("aa:bb", 1))
        assertFalse(lease.ownsSession(null, 1))
        assertFalse(lease.ownsSession("AA:BB", 2))
        assertFalse(lease.ownsSession("CC:DD", 1))
        assertNull(lease.probe("AA:BB", 2, 10_000))
        assertNull(lease.probe("CC:DD", 1, 10_000))
        assertTrue(lease.expire(30_000))
        assertFalse(lease.ownsSession("AA:BB", 1))
    }

    @Test fun explicitStopPreventsFurtherHeartbeats() {
        lease.start("AA:BB", 1, 0)
        val token = requireNotNull(lease.probe("AA:BB", 1, 0))
        lease.stop()
        assertNull(lease.probe("AA:BB", 1, 10_000))
        assertFalse(lease.acknowledge("AA:BB", 1, token, 10_000))
        assertFalse(lease.expire(30_000))
    }
}
