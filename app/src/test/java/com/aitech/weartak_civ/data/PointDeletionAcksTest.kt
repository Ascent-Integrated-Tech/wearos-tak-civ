package com.aitech.weartak_civ.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class PointDeletionAcksTest {
    @Test
    fun acknowledgementReportsPeerResultOnce() {
        val acks = PointDeletionAcks()
        acks.sent("uid", "tok", 3)
        assertEquals(PointDeletionAcks.Outcome.APPLIED, acks.acknowledged("uid", "tok", 3, true)?.outcome)
        assertNull(acks.acknowledged("uid", "tok", 3, true))
        assertNull(acks.timedOut("uid", "tok", 3))
    }

    @Test
    fun rejectedAndTimedOutAreDistinct() {
        val acks = PointDeletionAcks()
        acks.sent("a", "t1", 1)
        acks.sent("b", "t2", 1)
        assertEquals(PointDeletionAcks.Outcome.REJECTED, acks.acknowledged("a", "t1", 1, false)?.outcome)
        assertEquals(PointDeletionAcks.Outcome.NOT_ACKNOWLEDGED, acks.timedOut("b", "t2", 1)?.outcome)
        assertNull(acks.acknowledged("b", "t2", 1, true))
    }

    @Test
    fun unknownTokenOrOtherConnectionIsIgnored() {
        val acks = PointDeletionAcks()
        acks.sent("uid", "tok", 1)
        assertNull(acks.acknowledged("uid", "other", 1, true))
        assertNull(acks.acknowledged("uid", "tok", 2, true))
        assertNotNull(acks.timedOut("uid", "tok", 1))
    }

    @Test
    fun ackEnvelopeRoundTripsAndRejectsMalformed() {
        val payload = CompanionPointRevisions.deletionAck("uid", "tok-1", false).getJSONObject("payload")
        assertEquals(Triple("uid", "tok-1", false), CompanionPointRevisions.parseDeletionAck(payload))
        assertNull(CompanionPointRevisions.parseDeletionAck(JSONObject().put("targetUid", "u").put("revision", "t")))
        assertNull(CompanionPointRevisions.parseDeletionAck(payload.put("result", "maybe")))
    }
}