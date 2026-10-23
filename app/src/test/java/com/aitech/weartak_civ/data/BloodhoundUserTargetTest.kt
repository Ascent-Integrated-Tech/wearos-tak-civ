package com.aitech.weartak_civ.data

import android.content.Context
import com.aitech.weartak_civ.cot.CotLink
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BloodhoundUserTargetTest {
    private fun user(uid: String = "alice", time: Long = 100L, lat: Double = 38.0,
                     source: String = "server-a") = DataRepository.ReceivedUser(
        uid, "a-f-G-U-C", "Blue", "Alice", "Team Member", time, time + 300_000,
        DataRepository.LocationSnapshot(time, lat, -77.0, 12.0, 5f, 6f, null, null),
        "TAK", source
    )

    @Test fun selectedUserSeedsTargetAndUsesExistingActivationRequest() = runBlocking {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        val user = user()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            repository.requestActivateBloodhound.first()
        }
        assertTrue(repository.addUserTarget(user))
        repository.requestActivateBloodhound(user.uid)
        assertEquals(user.uid, request.await())
        assertEquals(BloodhoundRepository.ActivationResult.FIRST_ACK,
            repository.activate(user.uid).first)
        val target = repository.activeTrackingItem()!!
        assertEquals(user.uid, target.uid)
        assertEquals("Alice", target.target.name)
        assertEquals("server-a", target.sourceServerId)
        assertEquals(38.0, target.target.point.lat, 0.0)
        assertEquals(12.0, target.target.point.hae, 0.0)
        assertNull(target.origin.issuerUid)
    }

    @Test fun movingSelectedUserRefreshesLocationAndPreservesActivationWithoutAddingOtherUsers() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        repository.addUserTarget(user())
        repository.activate("alice")
        repository.refreshUserTargets(listOf(user(time = 200, lat = 39.0), user(uid = "bob")))
        assertEquals(39.0, repository.activeTrackingItem()!!.target.point.lat, 0.0)
        assertTrue(repository.activeTrackingItem()!!.state.acknowledged)
        assertTrue(repository.activeTrackingItem()!!.state.active)
        assertEquals(setOf("alice"), repository.bloodhoundMap.value.keys)
        repository.refreshUserTargets(listOf(user(time = 150, lat = 37.0)))
        assertEquals(39.0, repository.activeTrackingItem()!!.target.point.lat, 0.0)
        repository.refreshUserTargets(listOf(user(time = 300, lat = 40.0, source = "server-b")))
        assertEquals(39.0, repository.activeTrackingItem()!!.target.point.lat, 0.0)
    }

    @Test fun missingOrInvalidPositionDoesNotReplaceTrackingAndRemovalDoesNotRecreateTarget() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        repository.addUserTarget(user())
        repository.activate("alice")
        assertFalse(repository.addUserTarget(user(uid = "bad").copy(location = null)))
        assertFalse(repository.addUserTarget(user(uid = "bad", lat = Double.NaN)))
        assertFalse(repository.addUserTarget(user(uid = "bad", lat = 91.0)))
        assertFalse(repository.addUserTarget(user(uid = "")))
        repository.refreshUserTargets(emptyList())
        assertEquals("alice", repository.activeUid.value)
        assertTrue(repository.addUserTarget(user()))
        assertEquals(1, repository.bloodhoundMap.value.size)
        repository.removeTarget("alice")
        repository.refreshUserTargets(listOf(user(time = 200, lat = 39.0)))
        assertTrue(repository.bloodhoundMap.value.isEmpty())
        assertNull(repository.activeUid.value)
    }

    @Test fun selectingExistingAssignmentPreservesIssuerAndKeepsFollowingAfterAssignmentUpdates() {
        val repository = BloodhoundRepository(mockk<Context>(relaxed = true))
        repository.addUserTarget(user(time = 200))
        val issuer = CotLink("leader", null, "a-f-G", "Leader", "p-p")
        val assignment = repository.bloodhoundMap.value.getValue("alice").bhEvent.copy(
            issuer = issuer, tracksUserPosition = false
        )
        repository.removeTarget("alice")
        repository.upsertFromEvent(assignment)
        repository.activate("alice")
        // An older PLI must not replace the assignment, but future PLI should still refresh it.
        assertTrue(repository.addUserTarget(user(time = 100, lat = 37.0)))
        assertEquals(38.0, repository.activeTrackingItem()!!.target.point.lat, 0.0)
        repository.upsertFromEvent(assignment)
        repository.refreshUserTargets(listOf(user(time = 300, lat = 39.0)))
        assertEquals(39.0, repository.activeTrackingItem()!!.target.point.lat, 0.0)
        assertEquals(issuer, repository.activeTrackingItem()!!.bhEvent.issuer)
        assertTrue(repository.activeTrackingItem()!!.state.active)
        assertEquals(BloodhoundRepository.ActivationResult.NO_CHANGE,
            repository.activate("alice").first)
    }
}
