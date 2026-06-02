package com.aitech.weartak_civ.cep.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricAggregationStateOpsTest {
    @Test
    fun `off wrist invalidation clears partial active window without dropping completed history`() {
        val state = MetricAggregationStateData()
        state.activeWindowStartMs = 1_000L
        state.activeWindowValues.addAll(listOf(20.0, 21.0))
        state.qualifiedWindows.addLast(900L to 19.0)

        MetricAggregationStateOps.invalidateActiveWindow(state)

        assertNull(state.activeWindowStartMs)
        assertEquals(0, state.activeWindowValues.size)
        assertEquals(1, state.qualifiedWindows.size)
    }

    @Test
    fun `re entry starts fresh aggregation after off wrist invalidation`() {
        val state = MetricAggregationStateData()
        val first = MetricAggregationStateOps.appendSampleAndMaybeEmitQualifiedValue(
            state = state,
            nowMs = 1_000L,
            value = 30.0,
            qualifiedWindowMs = 60_000L
        )
        assertNull(first)
        assertEquals(1, state.activeWindowValues.size)

        MetricAggregationStateOps.invalidateActiveWindow(state)
        assertEquals(0, state.activeWindowValues.size)
        assertNull(state.activeWindowStartMs)

        val second = MetricAggregationStateOps.appendSampleAndMaybeEmitQualifiedValue(
            state = state,
            nowMs = 2_000L,
            value = 35.0,
            qualifiedWindowMs = 60_000L
        )
        assertNull(second)
        assertEquals(1, state.activeWindowValues.size)
        assertEquals(2_000L, state.activeWindowStartMs)
    }

    @Test
    fun `partial state does not emit qualified window before full duration`() {
        val state = MetricAggregationStateData()
        MetricAggregationStateOps.appendSampleAndMaybeEmitQualifiedValue(state, 1_000L, 25.0, 60_000L)
        MetricAggregationStateOps.invalidateActiveWindow(state)

        val emitted = MetricAggregationStateOps.appendSampleAndMaybeEmitQualifiedValue(
            state = state,
            nowMs = 10_000L,
            value = 28.0,
            qualifiedWindowMs = 60_000L
        )
        assertNull(emitted)
        assertEquals(0, state.qualifiedWindows.size)
    }
}
