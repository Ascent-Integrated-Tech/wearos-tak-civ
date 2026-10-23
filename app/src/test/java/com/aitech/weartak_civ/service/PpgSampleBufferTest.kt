package com.aitech.weartak_civ.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PpgSampleBufferTest {
    @Test fun snapshotIsDetachedFromCallbacksAndReset() {
        val buffer = PpgSampleBuffer()
        buffer.add(2f)
        buffer.add(8f)
        val snapshot = buffer.drain()
        buffer.add(100f)
        buffer.clear()
        assertEquals(listOf(2f, 8f), snapshot)
        assertEquals(6f, snapshot.maxOrNull()!! - snapshot.minOrNull()!!)
        assertTrue(buffer.drain().isEmpty())
    }

    @Test fun concurrentCallbacksAndCompletionsNeverLoseOrMutateDrainedSamples() {
        val buffer = PpgSampleBuffer()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val producer = executor.submit {
                start.await()
                repeat(50_000) { buffer.add(it.toFloat()) }
            }
            val consumer = executor.submit<List<Float>> {
                start.await()
                val collected = mutableListOf<Float>()
                repeat(2_000) {
                    val snapshot = buffer.drain()
                    snapshot.minOrNull()
                    snapshot.maxOrNull()
                    collected.addAll(snapshot)
                }
                collected
            }
            start.countDown()
            producer.get(10, TimeUnit.SECONDS)
            val collected = consumer.get(10, TimeUnit.SECONDS) + buffer.drain()
            assertEquals((0 until 50_000).map { it.toFloat() }, collected)
        } finally {
            executor.shutdownNow()
        }
    }
}
