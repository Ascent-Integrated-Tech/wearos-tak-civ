package com.aitech.weartak_civ.service

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionBloodhoundSyncTest {
    private var active: CompanionBloodhoundSync.Target? = null
    private val messages = mutableListOf<JSONObject>()
    private val errors = mutableListOf<String>()
    private var startAllowed = true
    private var stopAllowed = true
    private var sendAllowed = true
    private var time = 0L
    private var nextId = "local-1"
    private val sync = CompanionBloodhoundSync(
        activeTarget = { active },
        startTracking = { target -> startAllowed.also { if (it) active = target } },
        stopTracking = { uid ->
            (stopAllowed && active?.uid == uid).also { if (it) active = null }
        },
        send = { json -> sendAllowed.also { if (it) messages.add(JSONObject(json)) } },
        reportError = errors::add,
        newSessionId = { nextId },
        now = { time },
        updateTracking = { target ->
            (active?.uid == target.uid).also { if (it) active = target }
        }
    )

    @Test
    fun localStartAndStopShareOneSession() {
        sync.connectionChanged(true)
        active = target("marker")
        sync.localTargetChanged()
        val start = messages.single().getJSONObject("payload")
        assertEquals("start", start.getString("action"))
        assertEquals("marker", start.getString("target_uid"))
        assertEquals("local-1", start.getString("session_id"))
        assertEquals(38.0, start.getDouble("lat"), 0.0)
        active = null
        sync.localTargetChanged()
        val stop = messages.last().getJSONObject("payload")
        assertEquals("stop", stop.getString("action"))
        assertEquals("local-1", stop.getString("session_id"))
    }

    @Test
    fun remoteStartStopAndDuplicateCommandsDoNotEcho() {
        sync.connectionChanged(true)
        sync.receiveControl(command("phone-1", "start"))
        sync.localTargetChanged()
        sync.receiveControl(command("phone-1", "start"))
        assertEquals("marker", active?.uid)
        sync.receiveControl(command("phone-1", "stop"))
        sync.localTargetChanged()
        sync.receiveControl(command("phone-1", "stop"))
        assertNull(active)
        assertEquals(4, messages.size)
        assertTrue(messages.all { it.getString("msg_type") == "bloodhound_control_result" })
        assertTrue(messages.all { it.getJSONObject("payload").getBoolean("ok") })
    }

    @Test
    fun oldStopAndReplayedStartCannotAffectNewSession() {
        sync.connectionChanged(true)
        sync.receiveControl(command("phone-1", "start"))
        sync.receiveControl(command("phone-2", "start", "new-marker"))
        sync.receiveControl(command("phone-1", "stop"))
        assertTrue(messages.last().getJSONObject("payload").getBoolean("ok"))
        sync.receiveControl(command("phone-1", "stop", "new-marker"))
        assertFalse(messages.last().getJSONObject("payload").getBoolean("ok"))
        sync.receiveControl(command("phone-1", "start"))
        assertEquals("new-marker", active?.uid)
        assertFalse(messages.last().getJSONObject("payload").getBoolean("ok"))
    }

    @Test
    fun stopCannotAffectUnobservedLocalReplacement() {
        sync.connectionChanged(true)
        sync.receiveControl(command("phone-1", "start"))
        active = target("local-replacement")
        sync.receiveControl(command("phone-1", "stop"))
        assertEquals("local-replacement", active?.uid)
        assertFalse(messages.last().getJSONObject("payload").getBoolean("ok"))
    }

    @Test
    fun disconnectPreservesLocalTrackingAndReconnectDoesNotReplay() {
        sync.connectionChanged(true)
        active = target("marker")
        sync.localTargetChanged()
        messages.clear()
        sync.connectionChanged(false)
        active = target("offline-marker")
        sync.localTargetChanged()
        sync.receiveControl(command("phone-1", "stop"))
        sync.connectionChanged(true)
        sync.localTargetChanged()
        assertEquals("offline-marker", active?.uid)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun failedStartDoesNotReplaceExistingTrackingSession() {
        sync.connectionChanged(true)
        sync.receiveControl(command("phone-1", "start"))
        startAllowed = false
        sync.receiveControl(command("phone-2", "start", "bad-marker"))
        assertEquals("marker", active?.uid)
        assertFalse(messages.last().getJSONObject("payload").getBoolean("ok"))
        sync.receiveControl(command("phone-1", "stop"))
        assertNull(active)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun invalidCoordinatesAreRejectedWithoutStarting() {
        sync.connectionChanged(true)
        sync.receiveControl(command("phone-1", "start").put("lat", 100.0))
        assertNull(active)
        assertFalse(messages.single().getJSONObject("payload").getBoolean("ok"))
    }

    @Test
    fun targetUpdatesUseExistingSessionAndDoNotRestartOrEcho() {
        sync.connectionChanged(true)
        sync.receiveControl(command("phone-1", "start"))
        sync.receiveControl(command("phone-1", "update").put("lat", 39.0))
        sync.localTargetChanged()
        assertEquals(39.0, active?.lat)
        assertEquals(1, messages.size)
        sync.receiveControl(command("other-session", "update").put("lat", 40.0))
        assertEquals(39.0, active?.lat)
        active = active?.copy(lat = 41.0)
        sync.localTargetChanged()
        val update = messages.last().getJSONObject("payload")
        assertEquals("phone-1", update.getString("session_id"))
        assertEquals("update", update.getString("action"))
        assertEquals(41.0, update.getDouble("lat"), 0.0)
        sync.receiveControl(command("phone-1", "stop"))
        sync.receiveControl(command("phone-1", "update"))
        assertNull(active)
    }

    @Test
    fun invalidAcknowledgementIsReportedRatherThanAccepted() {
        sync.connectionChanged(true)
        active = target("marker")
        sync.localTargetChanged()
        sync.receiveResult(JSONObject().put("session_id", "local-1").put("action", "start").put("ok", "true"))
        assertTrue(errors.single().contains("Invalid"))
        time = 10_001L
        sync.checkTimeouts()
        assertEquals(2, errors.size)
    }

    @Test
    fun failedStopIsReportedAndCanBeRetried() {
        sync.connectionChanged(true)
        sync.receiveControl(command("phone-1", "start"))
        stopAllowed = false
        sync.receiveControl(command("phone-1", "stop"))
        assertNotNull(active)
        assertFalse(messages.last().getJSONObject("payload").getBoolean("ok"))
        stopAllowed = true
        sync.receiveControl(command("phone-1", "stop"))
        assertNull(active)
    }

    @Test
    fun simultaneousStartsChooseLexicographicallySmallerSession() {
        sync.connectionChanged(true)
        nextId = "a-local"
        active = target("local-marker")
        sync.localTargetChanged()
        sync.receiveControl(command("z-phone", "start"))
        assertEquals("local-marker", active?.uid)
        assertFalse(messages.last().getJSONObject("payload").getBoolean("ok"))
        sync.receiveControl(command("0-phone", "start"))
        assertEquals("marker", active?.uid)
        time = 20_000L
        sync.checkTimeouts()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun timeoutAndRejectedResultAreVisibleWithoutStoppingLocalTarget() {
        sync.connectionChanged(true)
        active = target("marker")
        sync.localTargetChanged()
        time = 10_001L
        sync.checkTimeouts()
        assertNotNull(active)
        assertTrue(errors.single().contains("did not confirm"))
        active = target("second")
        nextId = "local-2"
        sync.localTargetChanged()
        sync.receiveResult(
            JSONObject().put("session_id", "local-2").put("action", "start")
                .put("ok", false).put("error", "Phone has no location")
        )
        assertEquals("second", active?.uid)
        assertEquals("Phone has no location", errors.last())
    }

    @Test
    fun successfulResultClearsTimeoutAndSendFailureIsVisible() {
        sync.connectionChanged(true)
        active = target("marker")
        sync.localTargetChanged()
        sync.receiveResult(
            JSONObject().put("session_id", "local-1").put("action", "start").put("ok", true)
        )
        time = 20_000L
        sync.checkTimeouts()
        assertTrue(errors.isEmpty())
        sendAllowed = false
        active = target("other")
        sync.localTargetChanged()
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun pairedPeersCanStartFromEitherEndAndStopFromOtherEndWithoutEchoes() {
        val pair = PeerPair()
        pair.watchTarget = target("watch-selected")
        pair.watch.localTargetChanged()
        pair.deliver()
        assertEquals(pair.watchTarget, pair.phoneTarget)
        pair.phoneTarget = null
        pair.phone.localTargetChanged()
        pair.deliver()
        assertNull(pair.watchTarget)
        pair.phoneTarget = target("phone-selected")
        pair.phone.localTargetChanged()
        pair.deliver()
        assertEquals(pair.phoneTarget, pair.watchTarget)
        pair.watchTarget = null
        pair.watch.localTargetChanged()
        pair.deliver()
        assertNull(pair.phoneTarget)
        assertTrue(pair.errors.isEmpty())
    }

    @Test
    fun simultaneousPairedStartsConvergeAndWinningSessionCanStopBoth() {
        val pair = PeerPair()
        pair.watchTarget = target("watch-selected")
        pair.phoneTarget = target("phone-selected")
        pair.watch.localTargetChanged()
        pair.phone.localTargetChanged()
        pair.deliver()
        assertEquals("phone-selected", pair.watchTarget?.uid)
        assertEquals(pair.phoneTarget, pair.watchTarget)
        pair.watchTarget = null
        pair.watch.localTargetChanged()
        pair.deliver()
        assertNull(pair.phoneTarget)
    }

    private class PeerPair {
        var watchTarget: CompanionBloodhoundSync.Target? = null
        var phoneTarget: CompanionBloodhoundSync.Target? = null
        val errors = mutableListOf<String>()
        private val toPhone = ArrayDeque<String>()
        private val toWatch = ArrayDeque<String>()
        val watch = CompanionBloodhoundSync(
            { watchTarget }, { watchTarget = it; true }, { watchTarget = null; true },
            { toPhone.addLast(it); true }, { errors.add(it) }, { "watch-b" }
        )
        val phone = CompanionBloodhoundSync(
            { phoneTarget }, { phoneTarget = it; true }, { phoneTarget = null; true },
            { toWatch.addLast(it); true }, { errors.add(it) }, { "phone-a" }
        )

        init {
            watch.connectionChanged(true)
            phone.connectionChanged(true)
        }

        fun deliver() {
            repeat(10) {
                if (toPhone.isEmpty() && toWatch.isEmpty()) return
                while (toPhone.isNotEmpty()) dispatch(phone, toPhone.removeFirst())
                while (toWatch.isNotEmpty()) dispatch(watch, toWatch.removeFirst())
                watch.localTargetChanged()
                phone.localTargetChanged()
            }
            fail("Control messages should converge without an echo loop")
        }

        private fun dispatch(receiver: CompanionBloodhoundSync, json: String) {
            val envelope = JSONObject(json)
            val payload = envelope.getJSONObject("payload")
            if (envelope.getString("msg_type") == "bloodhound_control") receiver.receiveControl(payload)
            else receiver.receiveResult(payload)
        }
    }

    private fun target(uid: String) = CompanionBloodhoundSync.Target(uid, "Target", 38.0, -77.0, 12.0)

    private fun command(id: String, action: String, uid: String = "marker") =
        JSONObject().put("session_id", id).put("action", action).put("target_uid", uid)
            .put("target_callsign", "Target").put("lat", 38.0).put("lon", -77.0).put("hae", 12.0)
}
