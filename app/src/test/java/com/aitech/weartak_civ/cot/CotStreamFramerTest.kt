/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.cot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CotStreamFramerTest {
    @Test
    fun framesEventsAcrossChunksAndReturnsEveryCompleteEvent() {
        val framer = CotStreamFramer()

        assertTrue(framer.append("noise${EVENT_A.take(18)}").isEmpty())
        assertEquals(
            listOf(EVENT_A, EVENT_B),
            framer.append(EVENT_A.drop(18) + EVENT_B)
        )
    }

    @Test
    fun malformedPrefixDoesNotBlockTheFollowingValidEvent() {
        val discarded = mutableListOf<CotStreamDiscardReason>()
        val framer = CotStreamFramer(onDiscard = { discarded += it })

        val events = framer.append("<event uid='broken'>$EVENT_A")

        assertEquals(listOf(EVENT_A), events)
        assertEquals(listOf(CotStreamDiscardReason.MALFORMED_PREFIX), discarded)
    }

    @Test
    fun boundsIncompleteEventBuffers() {
        val discarded = mutableListOf<CotStreamDiscardReason>()
        val framer = CotStreamFramer(maxBufferChars = 20, onDiscard = { discarded += it })

        assertTrue(framer.append("<event uid='too-large'").isEmpty())

        assertEquals(listOf(CotStreamDiscardReason.OVERSIZED_EVENT), discarded)
    }

    private companion object {
        const val EVENT_A = "<event uid='a'></event>"
        const val EVENT_B = "<event uid='b'></event>"
    }
}
