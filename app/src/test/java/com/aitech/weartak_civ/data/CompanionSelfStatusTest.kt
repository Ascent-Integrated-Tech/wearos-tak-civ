package com.aitech.weartak_civ.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionSelfStatusTest {
    private val now = 1_800_000_000_000L
    private val input = SelfStatusInputs(
        SelfStatusPolicy(true, SavedMoppStatus(MoppStatus.LEVEL_2, null),
            true, true, true, true, 30, 60),
        PhysioSample(72, now - 1000), PhysioSample(93.4f, now - 2000)
    )

    @Test fun noCapabilityMeansNoContributionAndReconnectRequiresNewCapability() {
        val sender = CompanionSelfStatus()
        val sent = mutableListOf<JSONObject>()
        fun send(gen: Long, elapsed: Long = 0) = sender.sendIfDue(gen, "watch", input, now, elapsed) {
            sent += JSONObject(it); true
        }
        assertNull(send(1))
        sender.negotiate(1)
        assertTrue(send(1)!!)
        assertTrue(sender.active(1))
        val first = sent.last().getJSONObject("payload")
        assertEquals(0L, first.getLong("seq"))
        assertTrue(first.getJSONObject("ppe").isNull("savedAt"))
        assertEquals("MOPP 2", first.getJSONObject("ppe").getString("level"))
        sender.negotiate(1)
        assertTrue(send(1, 60_000)!!)
        assertEquals(first.getString("session"), sent.last().getJSONObject("payload").getString("session"))
        assertEquals(1L, sent.last().getJSONObject("payload").getLong("seq"))
        sender.reset()
        assertFalse(sender.active(1))
        assertNull(send(2))
        sender.negotiate(2)
        assertTrue(send(2)!!)
        assertNotEquals(first.getString("session"), sent.last().getJSONObject("payload").getString("session"))
        assertEquals(0L, sent.last().getJSONObject("payload").getLong("seq"))
        assertFalse(sender.acceptsSession(1, first.getString("session")))
    }

    @Test fun independentFreshnessNeverRenewsSampleTimesFromReceiptOrKeepalive() {
        val sender = CompanionSelfStatus().apply { negotiate(1) }
        val sent = mutableListOf<JSONObject>()
        fun send(wall: Long, elapsed: Long) = sender.sendIfDue(1, "watch", input, wall, elapsed) {
            sent += JSONObject(it).getJSONObject("payload"); true
        }
        send(now, 0)
        send(now + 60_000, 60_000)
        val original = sent.first().getJSONObject("physio")
        val keepalive = sent.last().getJSONObject("physio")
        assertEquals(original.toString(), keepalive.toString())
        assertEquals(Instant.ofEpochMilli(now - 1000).toString(), original.getString("measuredAt"))
        assertEquals(Instant.ofEpochMilli(now - 2000).toString(), original.getString("sktFMeasuredAt"))
        assertEquals(original.getString("measuredAt"), original.getString("exertMeasuredAt"))
        assertTrue(original.getDouble("exert") in 0.0..1.0)
        send(now + 118_001, 118_001)
        assertFalse(sent.last().getJSONObject("physio").has("sktF"))
        assertTrue(sent.last().getJSONObject("physio").has("hr"))
        send(now + 119_001, 119_001)
        assertTrue(sent.last().isNull("physio"))
    }

    @Test fun monitoringOptOutOffWristAndIndividualSwitchesOmitReadings() {
        for (policy in listOf(input.policy.copy(monitoring = false), input.policy.copy(worn = false),
            input.policy.copy(heartRateEnabled = false, skinTemperatureEnabled = false))) {
            assertNull(selfStatusSnapshot(input.copy(policy = policy), now).physio)
        }
        assertNull(selfStatusSnapshot(input.copy(policy = input.policy.copy(heartRateEnabled = false)), now).physio!!.hr)
        assertNotNull(selfStatusSnapshot(input.copy(policy = input.policy.copy(heartRateEnabled = false)), now).physio!!.sktF)
        assertNull(selfStatusSnapshot(input.copy(policy = input.policy.copy(skinTemperatureEnabled = false)), now).physio!!.sktF)
        assertNull(selfStatusSnapshot(input.copy(policy = input.policy.copy(moppEnabled = false)), now).ppe)
        assertNull(selfStatusSnapshot(input.copy(policy = input.policy.copy(savedMopp = null)), now).ppe)
    }

    @Test fun boundsFutureAndStaleSamplesAreRejectedIndividually() {
        for (bpm in listOf(-1, 0, 19, 251)) {
            assertNull(selfStatusSnapshot(input.copy(heartRate = PhysioSample(bpm, now)), now).physio!!.hr)
        }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, 58.9f, 113.1f)) {
            assertNull(selfStatusSnapshot(input.copy(skinTemperature = PhysioSample(value, now)), now).physio!!.sktF)
        }
        for (time in listOf(0, now - 120_001, now + 30_001)) {
            assertNull(selfStatusSnapshot(input.copy(heartRate = PhysioSample(72, time)), now).physio!!.hr)
        }
        for (time in listOf(now - 120_000, now + 30_000)) {
            assertEquals(72, selfStatusSnapshot(input.copy(heartRate = PhysioSample(72, time)), now).physio!!.hr)
        }
    }

    @Test fun samplesThrottleButSavedPpeAndOptOutClearImmediately() {
        val sender = CompanionSelfStatus().apply { negotiate(1) }
        val sent = mutableListOf<JSONObject>()
        fun send(value: SelfStatusInputs, elapsed: Long) = sender.sendIfDue(1, "watch", value, now, elapsed) {
            sent += JSONObject(it).getJSONObject("payload"); true
        }
        assertTrue(send(input, 0)!!)
        val next = input.copy(heartRate = PhysioSample(73, now))
        assertNull(send(next, 1000))
        assertTrue(send(next, 15_000)!!)
        val none = next.copy(policy = next.policy.copy(savedMopp = SavedMoppStatus(MoppStatus.NONE, now)))
        assertTrue(send(none, 15_001)!!)
        assertEquals("None", sent.last().getJSONObject("ppe").getString("level"))
        val zero = none.copy(policy = none.policy.copy(savedMopp = SavedMoppStatus(MoppStatus.LEVEL_0, now)))
        assertTrue(send(zero, 15_002)!!)
        assertEquals("MOPP 0", sent.last().getJSONObject("ppe").getString("level"))
        assertTrue(send(zero.copy(policy = zero.policy.copy(monitoring = false)), 15_003)!!)
        assertTrue(sent.last().isNull("physio"))
    }

    @Test fun queueRejectionDoesNotConsumeSequenceOrPretendSnapshotWasSent() {
        val sender = CompanionSelfStatus().apply { negotiate(1) }
        assertEquals(false, sender.sendIfDue(1, "watch", input, now, 0) { false })
        assertFalse(sender.active(1))
        var json = JSONObject()
        assertEquals(true, sender.sendIfDue(1, "watch", input, now, 1) { json = JSONObject(it); true })
        assertEquals(0L, json.getJSONObject("payload").getLong("seq"))
    }

    @Test fun skinOnlyAndDerivedExertionRetainIndependentRealSampleTimes() {
        val skinOnly = input.copy(heartRate = null)
        val sender = CompanionSelfStatus().apply { negotiate(1) }
        var payload = JSONObject()
        sender.sendIfDue(1, "watch", skinOnly, now, 0) {
            payload = JSONObject(it).getJSONObject("payload"); true
        }
        val skin = payload.getJSONObject("physio")
        assertEquals(setOf("sktF", "sktFMeasuredAt"), skin.keys().asSequence().toSet())
        assertEquals(Instant.ofEpochMilli(now - 2000).toString(), skin.getString("sktFMeasuredAt"))
        val heartOnly = input.copy(skinTemperature = null)
        val snapshot = selfStatusSnapshot(heartOnly, now).physio!!
        assertNull(snapshot.sktF)
        assertEquals(input.heartRate!!.measuredAtMillis, snapshot.exertMeasuredAt)
        val changedAge = selfStatusSnapshot(
            heartOnly.copy(policy = heartOnly.policy.copy(age = 45)), now
        ).physio!!
        assertNotEquals(snapshot.exert, changedAge.exert)
        assertEquals(snapshot.exertMeasuredAt, changedAge.exertMeasuredAt)
        assertNull(selfStatusSnapshot(heartOnly, now + 120_001).physio)
    }

    @Test fun exactMetricBoundsAndOutOfRangeExertionDoNotFabricateOrClampValues() {
        for (bpm in listOf(20, 250)) {
            assertEquals(bpm, selfStatusSnapshot(input.copy(heartRate = PhysioSample(bpm, now)), now).physio!!.hr)
        }
        for (temp in listOf(59f, 113f)) {
            assertEquals(temp, selfStatusSnapshot(input.copy(skinTemperature = PhysioSample(temp, now)), now).physio!!.sktF)
        }
        val highHr = selfStatusSnapshot(input.copy(heartRate = PhysioSample(250, now)), now).physio!!
        assertEquals(250, highHr.hr)
        assertNull(highHr.exert)
        assertNull(highHr.exertMeasuredAt)
        for (time in listOf(now - 120_001, now + 30_001)) {
            assertNull(selfStatusSnapshot(input.copy(skinTemperature = PhysioSample(93.4f, time)), now).physio!!.sktF)
        }
        for (time in listOf(now - 120_000, now + 30_000)) {
            assertEquals(93.4f, selfStatusSnapshot(input.copy(skinTemperature = PhysioSample(93.4f, time)), now).physio!!.sktF)
        }
    }

    @Test fun keepaliveHasSchedulerMarginAndPreservesOriginalSensorTimes() {
        val sender = CompanionSelfStatus().apply { negotiate(1) }
        val sent = mutableListOf<JSONObject>()
        fun tick(elapsed: Long) = sender.sendIfDue(1, "watch", input, now + elapsed, elapsed) {
            sent += JSONObject(it).getJSONObject("payload"); true
        }
        assertEquals(true, tick(0))
        assertNull(tick(54_999))
        assertEquals(true, tick(55_000))
        assertEquals(sent.first().getJSONObject("physio").toString(), sent.last().getJSONObject("physio").toString())
        assertEquals(1L, sent.last().getLong("seq"))
    }
}
