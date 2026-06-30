package com.aitech.weartak_civ.cep.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RingsCopyTest {

    @Test
    fun timedDoubleRingCopyStatePreservesBufferedSamples() {
        val src = TimedDoubleRing(32)
        src.add(10.0, 1000L)
        src.add(20.0, 2000L)
        val dst = TimedDoubleRing(32)
        dst.copyStateFrom(src)
        assertEquals(20.0, dst.lastOrNull()!!, 0.001)
        assertEquals(2, dst.size)
    }

    @Test
    fun timedArrayRingCopyStatePreservesBufferedSamples() {
        val src = TimedArrayRing(16)
        src.add(listOf(1.0, 2.0), 500L)
        src.add(listOf(3.0, 4.0), 600L)
        val dst = TimedArrayRing(16)
        dst.copyStateFrom(src)
        assertEquals(listOf(3.0, 4.0), dst.lastOrNull())
    }
}
