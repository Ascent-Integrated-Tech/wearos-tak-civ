package com.aitech.weartak_civ.cot

import org.junit.Assert.*
import org.junit.Test

class CotCoalescingPolicyTest {
    private fun xml(detail: String = "", type: String = "a-f-G-U-C", how: String = "m-g") =
        """<event version="2.0" uid="track" type="$type" how="$how"
            time="2026-10-26T00:00:00Z" start="2026-10-26T00:00:00Z" stale="2026-10-26T00:05:00Z">
            <point lat="1" lon="2" hae="0" ce="1" le="1"/><detail>$detail</detail></event>"""

    @Test fun automaticPliIsEligibleButEmergencyBearingAtomTypesAreNeverEligible() {
        val pli = CotMessageParser.parseUserPositionOrNull(xml())!!
        assertTrue(pli.detail.canCoalesceTrack(pli.type, pli.how, "tcp"))
        listOf("<emergency type=\"911\"/>", "<alert/>", "<__chat/>",
            "<ackrequest/>", "<x:emergency xmlns:x=\"urn:test\"/>",
            "<status emergency=\"true\"/>").forEach {
            val urgent = CotMessageParser.parseUserPositionOrNull(xml(it))!!
            assertFalse(it, urgent.detail.canCoalesceTrack(urgent.type, urgent.how, "tcp"))
        }
    }

    @Test fun humanActionsAssignmentsRemarksAndNonTracksRemainImmediate() {
        val detail = CotMessageParser.parseUserPositionOrNull(xml())!!.detail
        listOf("h-e", "h-g-i-g-o", "").forEach { assertFalse(detail.canCoalesceTrack("a-f-G", it, "tcp")) }
        assertFalse(detail.canCoalesceTrack("a-f-G", "m-g", null))
        assertFalse(detail.canCoalesceTrack("a-f-G", "m-g", "plugin:example"))
        listOf("b-a-o-tbl", "b-t-f", "t-x-d-d", "b-m-p-s-m").forEach {
            assertFalse(detail.canCoalesceTrack(it, "m-g", "tcp"))
        }
        listOf("<remarks>#houndmaster</remarks>", "<link uid=\"issuer\"/>").forEach {
            val assignment = CotMessageParser.parseUserPositionOrNull(xml(it))!!
            assertFalse(assignment.detail.canCoalesceTrack(assignment.type, assignment.how, "tcp"))
        }
    }
}
